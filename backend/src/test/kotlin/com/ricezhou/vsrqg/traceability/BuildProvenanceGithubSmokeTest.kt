package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceCanonicalizer
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer

@EnabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
        "vsrqg.traceability.ingestion.enabled=true",
    ],
)
class BuildProvenanceGithubSmokeTest {
    @LocalServerPort
    private var port: Int = 0

    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var canonicalizer: BuildProvenanceCanonicalizer

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    private lateinit var context: GithubContext
    private lateinit var fixture: BuildProvenanceTestFixture

    @BeforeEach
    fun seedLockedAuthority() {
        context = GithubContext.load()
        val controlledArtifact = "vsrqg-m2.4:${context.commit}:${context.runId}:${context.runAttempt}".toByteArray()
        val artifactSha256 = digestHex(String(controlledArtifact, Charsets.UTF_8))
        fixture = BuildProvenanceFixtureSeeder(jdbc, transactionTemplate).seed(artifactSha256)
        transactionTemplate.executeWithoutResult {
            val manifestId = "mft_ing_${fixture.suffix}"
            val releaseId = "rel_ing_${fixture.suffix}"
            jdbc.sql(
                "UPDATE manifest_revision SET state = 'LOCKED', row_version = row_version + 1, updated_at = now() " +
                    "WHERE id = :manifestId AND state = 'REGISTERED'",
            ).param("manifestId", manifestId).update()
            jdbc.sql("UPDATE release_record SET locked_manifest_id = :manifestId WHERE id = :releaseId")
                .param("manifestId", manifestId)
                .param("releaseId", releaseId)
                .update()
        }
        Mockito.`when`(jwtDecoder.decode(SERVICE_TOKEN)).thenReturn(
            jwt(SERVICE_TOKEN, fixture.serviceSubject, "SERVICE", fixture.projectReference),
        )
        Mockito.`when`(jwtDecoder.decode(USER_TOKEN)).thenReturn(
            jwt(USER_TOKEN, fixture.userSubject, "USER", fixture.projectReference),
        )
        Mockito.`when`(jwtDecoder.decode(WRONG_PROJECT_TOKEN)).thenReturn(
            jwt(WRONG_PROJECT_TOKEN, fixture.serviceSubject, "SERVICE", "wrong-${fixture.suffix}"),
        )
    }

    @Test
    fun `exact head GitHub context produces a replayable redacted provenance chain over HTTP`() {
        val envelope = githubEnvelope()
        val first = post(envelope, "github-smoke-${fixture.suffix}", SERVICE_TOKEN)
        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)

        val sameKey = post(envelope, "github-smoke-${fixture.suffix}", SERVICE_TOKEN)
        assertThat(sameKey.statusCode).isEqualTo(HttpStatus.OK)
        val sameKeyReplay = sameKey.body == first.body
        assertThat(sameKeyReplay).isTrue()

        val differentKey = post(envelope, "github-smoke-replay-${fixture.suffix}", SERVICE_TOKEN)
        assertThat(differentKey.statusCode).isEqualTo(HttpStatus.OK)
        val differentKeyReplay = differentKey.body == first.body
        assertThat(differentKeyReplay).isTrue()

        val conflicting = envelope.deepCopy().put("sourceRevision", alternateRevision(context.commit))
        val conflict = post(conflicting, "github-smoke-conflict-${fixture.suffix}", SERVICE_TOKEN)
        assertProblem(conflict.statusCode, conflict.body, HttpStatus.CONFLICT, "BUILD_PROVENANCE_CONFLICT")

        val userDenied = post(envelope, "github-smoke-user-${fixture.suffix}", USER_TOKEN)
        assertProblem(userDenied.statusCode, userDenied.body, HttpStatus.FORBIDDEN, "PROJECT_SCOPE_MISMATCH")

        val wrongProject = post(envelope, "github-smoke-project-${fixture.suffix}", WRONG_PROJECT_TOKEN)
        assertProblem(wrongProject.statusCode, wrongProject.body, HttpStatus.FORBIDDEN, "PROJECT_SCOPE_MISMATCH")

        val accepted = requireNotNull(first.body)
        val result = objectMapper.readTree(accepted)
        assertThat(result.path("verificationStatus").asText()).isEqualTo("VALID")
        assertThat(result.path("confidence").asText()).isEqualTo("MEDIUM")
        assertThat(result.path("edgeRevisions")).hasSize(3)
        val readBack = readBackAuthority(result, envelope)
        writeEvidence(
            result = result,
            readBack = readBack,
            sameKeyReplay = sameKeyReplay,
            differentKeyReplay = differentKeyReplay,
            acceptedRequests = listOf(first, sameKey, differentKey).count { it.statusCode.is2xxSuccessful },
            rejectedRequests = listOf(conflict, userDenied, wrongProject).count { !it.statusCode.is2xxSuccessful },
            diagnostics = listOf(conflict, userDenied, wrongProject).map {
                objectMapper.readTree(requireNotNull(it.body)).path("code").asText()
            }.distinct(),
        )
    }

    private fun githubEnvelope(): ObjectNode {
        val draft = fixture.envelope(
            objectMapper = objectMapper,
            proofReference = context.proofReference,
            proofDigest = PLACEHOLDER_DIGEST,
        ).put("repository", context.repository)
            .put("sourceRevision", context.commit)
            .put("pipeline", context.job)
            .put("buildId", context.runId)
            .put("buildAttempt", context.runAttempt)
            .put("workflowReference", context.workflowReference)
        val proofDigest = canonicalizer.canonicalize(draft.toDomainEnvelope()).recomputedProofDigest
        return draft.put("proofDigest", proofDigest)
    }

    private fun ObjectNode.toDomainEnvelope() = com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope(
        schemaVersion = path("schemaVersion").asInt(),
        projectReference = path("project").asText(),
        releaseIssueSnapshotId = path("releaseIssueSnapshotId").asText(),
        provider = com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId("github-actions"),
        repository = path("repository").asText(),
        sourceRevision = path("sourceRevision").asText(),
        pipeline = path("pipeline").asText(),
        buildId = path("buildId").asText(),
        buildAttempt = path("buildAttempt").asInt(),
        workflowReference = path("workflowReference").asText(),
        proofReference = path("proofReference").asText(),
        proofDigest = path("proofDigest").asText(),
        sourceIssueIds = path("sourceIssueIds").map(JsonNode::asText),
        artifactSha256s = path("artifactSha256s").map(JsonNode::asText),
    )

    private fun post(body: ObjectNode, key: String, token: String) = restTemplate.exchange(
        "http://127.0.0.1:$port/api/v1/traceability/facts:ingest",
        HttpMethod.POST,
        HttpEntity(objectMapper.writeValueAsBytes(body), HttpHeaders().also {
            it.contentType = MediaType.APPLICATION_JSON
            it.setBearerAuth(token)
            it.set("Idempotency-Key", key)
        }),
        String::class.java,
    )

    private fun assertProblem(
        actualStatus: HttpStatusCode,
        body: String?,
        expectedStatus: HttpStatusCode,
        expectedCode: String,
    ) {
        assertThat(actualStatus).isEqualTo(expectedStatus)
        assertThat(objectMapper.readTree(requireNotNull(body)).path("code").asText()).isEqualTo(expectedCode)
    }

    private fun readBackAuthority(result: JsonNode, envelope: JsonNode): SmokeAuthorityReadBack {
        val rows = jdbc.sql(AUTHORITY_READ_BACK_SQL)
            .param("receiptId", result.path("receiptId").asText())
            .param("artifactId", fixture.artifactId)
            .query { rs, _ ->
                SmokeAuthorityRow(
                    receiptId = rs.getString("receipt_id"), snapshotId = rs.getString("snapshot_id"),
                    commitId = rs.getString("commit_id"), buildRecordId = rs.getString("build_record_id"),
                    envelopeDigest = rs.getString("envelope_digest"), provider = rs.getString("provider"),
                    commitRepository = rs.getString("commit_repository"), commitRevision = rs.getString("commit_revision"),
                    buildRepository = rs.getString("build_repository"), buildRevision = rs.getString("build_revision"),
                    pipeline = rs.getString("pipeline"), runId = rs.getString("provider_build_id"),
                    runAttempt = rs.getInt("build_attempt"), validatorVersion = rs.getString("validator_version"),
                    status = rs.getString("receipt_status"), confidence = rs.getString("receipt_confidence"),
                    artifactDigest = "sha256:${rs.getString("checksum_value")}", edgeType = rs.getString("edge_type"),
                    edgeId = rs.getString("edge_id"), revisionId = rs.getString("revision_id"),
                    revision = rs.getInt("revision"), fromId = rs.getString("from_entity_id"),
                    toId = rs.getString("to_entity_id"), sourceType = rs.getString("source_type"),
                    sourceReference = rs.getString("source_reference"), proofReference = rs.getString("proof_reference"),
                    proofDigest = rs.getString("proof_digest"), reasonCode = rs.getString("reason_code"),
                    factDigest = rs.getString("content_digest"), revisionStatus = rs.getString("revision_status"),
                    revisionConfidence = rs.getString("revision_confidence"), receipts = rs.getInt("receipt_count"),
                    rejectedReceipts = rs.getInt("rejected_receipt_count"), edgeIdentities = rs.getInt("edge_identity_count"),
                    edgeRevisions = rs.getInt("edge_revision_count"), auditEvents = rs.getInt("audit_count"),
                    outboxEvents = rs.getInt("outbox_count"), artifactReleaseEdges = rs.getInt("artifact_release_count"),
                    auditPayload = rs.getString("audit_payload"), outboxPayload = rs.getString("outbox_payload"),
                )
            }.list()
        assertThat(rows).hasSize(3)
        val responseEdges = result.path("edgeRevisions").associateBy { it.path("edgeId").asText() }
        val expectedEndpoints = mapOf(
            "ISSUE_COMMIT" to (fixture.issueId to result.path("sourceCommitId").asText()),
            "COMMIT_BUILD" to (result.path("sourceCommitId").asText() to result.path("buildRecordId").asText()),
            "BUILD_ARTIFACT" to (result.path("buildRecordId").asText() to fixture.artifactId),
        )
        rows.forEach { row ->
            assertThat(row.receiptId).isEqualTo(result.path("receiptId").asText())
            assertThat(row.snapshotId).isEqualTo(fixture.snapshotId).isEqualTo(result.path("releaseIssueSnapshotId").asText())
            assertThat(row.commitId).isEqualTo(result.path("sourceCommitId").asText())
            assertThat(row.buildRecordId).isEqualTo(result.path("buildRecordId").asText())
            assertThat(row.envelopeDigest).isEqualTo(result.path("envelopeDigest").asText())
            assertThat(row.provider).isEqualTo("github-actions")
            assertThat(row.commitRepository).isEqualTo(context.repository)
            assertThat(row.commitRevision).isEqualTo(context.commit)
            assertThat(row.buildRepository).isEqualTo(context.repository)
            assertThat(row.buildRevision).isEqualTo(context.commit)
            assertThat(row.pipeline).isEqualTo(context.job)
            assertThat(row.runId).isEqualTo(context.runId)
            assertThat(row.runAttempt).isEqualTo(context.runAttempt)
            assertThat(row.validatorVersion).isEqualTo(result.path("validatorVersion").asText())
            assertThat(row.status).isEqualTo("VALID")
            assertThat(row.confidence).isEqualTo("MEDIUM")
            assertThat(row.fromId to row.toId).isEqualTo(expectedEndpoints.getValue(row.edgeType))
            val responseRevision = responseEdges.getValue(row.edgeId)
            assertThat(row.revisionId).isEqualTo(responseRevision.path("revisionId").asText())
            assertThat(row.revision).isEqualTo(responseRevision.path("revision").asInt())
            assertThat(row.factDigest).isEqualTo(responseRevision.path("factDigest").asText())
            assertThat(row.revisionStatus).isEqualTo(responseRevision.path("verificationStatus").asText()).isEqualTo("VALID")
            assertThat(row.revisionConfidence).isEqualTo(responseRevision.path("confidence").asText()).isEqualTo("MEDIUM")
            assertThat(row.sourceType).isEqualTo("github-actions")
            assertThat(row.sourceReference).isEqualTo(context.workflowReference)
            assertThat(row.proofReference).isEqualTo(context.proofReference)
            assertThat(row.proofDigest).isEqualTo(envelope.path("proofDigest").asText())
            assertThat(row.reasonCode).isEqualTo("PROOF_MATCHED")
        }
        val authority = SmokeAuthorityReadBack(rows)
        assertThat(authority.edgeTypes).containsExactlyInAnyOrder("ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT")
        assertThat(authority.counts).isEqualTo(SmokeCounts(1, 1, 3, 3, 2, 1, 0))
        val audits = objectMapper.readTree(authority.auditPayload)
        assertThat(audits.map { it.path("action").asText() })
            .containsExactlyInAnyOrder("BUILD_PROVENANCE_INGESTED", "BUILD_PROVENANCE_REJECTED")
        assertThat(audits.single { it.path("action").asText() == "BUILD_PROVENANCE_INGESTED" }
            .path("aggregateId").asText()).isEqualTo(result.path("receiptId").asText())
        assertThat(audits.single { it.path("action").asText() == "BUILD_PROVENANCE_REJECTED" }
            .path("afterState").path("acceptedReceiptId").asText()).isEqualTo(result.path("receiptId").asText())
        val outbox = objectMapper.readTree(authority.outboxPayload)
        assertThat(outbox).hasSize(1)
        assertThat(outbox[0].path("eventType").asText()).isEqualTo("traceability.build-provenance.ingested")
        assertThat(outbox[0].path("aggregateId").asText()).isEqualTo(result.path("receiptId").asText())
        assertThat(authority.auditPayload).doesNotContain(context.repository, context.commit, context.proofReference)
        assertThat(authority.outboxPayload).doesNotContain(context.repository, context.commit, context.proofReference)
        return authority
    }

    private fun writeEvidence(
        result: JsonNode,
        readBack: SmokeAuthorityReadBack,
        sameKeyReplay: Boolean,
        differentKeyReplay: Boolean,
        acceptedRequests: Int,
        rejectedRequests: Int,
        diagnostics: List<String>,
    ) {
        val outputDirectory = Path.of("build", "m2").toAbsolutePath().normalize()
        val authority = readBack.rows.first()
        val document = objectMapper.createObjectNode()
            .put("schemaVersion", 2)
            .put("exactCommit", context.commit)
            .put("runId", context.runId)
            .put("runAttempt", context.runAttempt)
            .put("validatorVersion", authority.validatorVersion)
            .put("envelopeDigest", authority.envelopeDigest)
            .put("artifactDigest", authority.artifactDigest)
        document.putArray("edgeRevisionIds").addAll(readBack.rows.map { revision ->
            objectMapper.createObjectNode()
                .put("edgeType", revision.edgeType)
                .put("edgeId", revision.edgeId)
                .put("revisionId", revision.revisionId)
        })
        document.putObject("replayResults")
            .put("sameIdempotencyKey", sameKeyReplay)
            .put("differentIdempotencyKey", differentKeyReplay)
        document.putArray("fixedDiagnostics").addAll(diagnostics.map(objectMapper.nodeFactory::textNode))
        document.putObject("testCounts")
            .put("acceptedRequests", acceptedRequests).put("rejectedRequests", rejectedRequests)
            .put("receipts", readBack.counts.receipts).put("rejectedReceipts", readBack.counts.rejectedReceipts)
            .put("edgeIdentities", readBack.counts.edgeIdentities).put("edgeRevisions", readBack.counts.edgeRevisions)
            .put("auditEvents", readBack.counts.auditEvents).put("outboxEvents", readBack.counts.outboxEvents)
            .put("artifactReleaseEdges", readBack.counts.artifactReleaseEdges)
        BuildProvenanceSmokeEvidencePublisher(objectMapper).publish(
            outputDirectory.resolve("build-provenance-smoke.json"), document,
            SmokeEvidenceContext(context.commit, context.runId, context.runAttempt),
        )
    }

    private fun jwt(token: String, subject: String, principalType: String, project: String): Jwt =
        Jwt.withTokenValue(token)
            .header("alg", "none")
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(Instant.parse("2026-09-03T00:00:00Z"))
            .expiresAt(Instant.parse("2099-09-03T00:00:00Z"))
            .claim("principal_type", principalType)
            .claim("project", project)
            .claim("scope", "traceability:ingest")
            .build()

    private fun alternateRevision(commit: String): String =
        (if (commit[0] == 'a') "b" else "a") + commit.drop(1)

    private data class SmokeCounts(
        val receipts: Int,
        val rejectedReceipts: Int,
        val edgeIdentities: Int,
        val edgeRevisions: Int,
        val auditEvents: Int,
        val outboxEvents: Int,
        val artifactReleaseEdges: Int,
    )

    private data class SmokeAuthorityReadBack(val rows: List<SmokeAuthorityRow>) {
        val edgeTypes = rows.map(SmokeAuthorityRow::edgeType)
        val counts = rows.first().let {
            SmokeCounts(it.receipts, it.rejectedReceipts, it.edgeIdentities, it.edgeRevisions, it.auditEvents, it.outboxEvents, it.artifactReleaseEdges)
        }
        val auditPayload = rows.first().auditPayload
        val outboxPayload = rows.first().outboxPayload
    }

    private data class SmokeAuthorityRow(
        val receiptId: String, val snapshotId: String, val commitId: String, val buildRecordId: String,
        val envelopeDigest: String, val provider: String, val commitRepository: String, val commitRevision: String,
        val buildRepository: String, val buildRevision: String,
        val pipeline: String, val runId: String, val runAttempt: Int, val validatorVersion: String,
        val status: String, val confidence: String, val artifactDigest: String, val edgeType: String,
        val edgeId: String, val revisionId: String, val revision: Int, val fromId: String, val toId: String,
        val sourceType: String, val sourceReference: String, val proofReference: String, val proofDigest: String,
        val reasonCode: String, val factDigest: String, val revisionStatus: String, val revisionConfidence: String,
        val receipts: Int, val rejectedReceipts: Int,
        val edgeIdentities: Int, val edgeRevisions: Int, val auditEvents: Int, val outboxEvents: Int,
        val artifactReleaseEdges: Int, val auditPayload: String, val outboxPayload: String,
    )

    private data class GithubContext(
        val repository: String,
        val commit: String,
        val workflowReference: String,
        val runId: String,
        val runAttempt: Int,
        val job: String,
    ) {
        val proofReference: String
            get() = "https://github.com/$repository/actions/runs/$runId/attempts/$runAttempt"

        companion object {
            fun load() = GithubContext(
                repository = required("GITHUB_REPOSITORY"),
                commit = required("GITHUB_SHA"),
                workflowReference = required("GITHUB_WORKFLOW_REF"),
                runId = required("GITHUB_RUN_ID"),
                runAttempt = required("GITHUB_RUN_ATTEMPT").toIntOrNull()
                    ?: error("GITHUB_RUN_ATTEMPT_INVALID"),
                job = required("GITHUB_JOB"),
            )

            private fun required(name: String): String =
                System.getenv(name)?.takeIf(String::isNotBlank) ?: error("${name}_MISSING")
        }
    }

    companion object {
        private val AUTHORITY_READ_BACK_SQL = """
            WITH typed_revision AS (
                SELECT 'ISSUE_COMMIT'::text edge_type, id revision_id, edge_id, revision, project_id,
                       issue_id from_entity_id, commit_id to_entity_id, source_type, source_reference,
                       proof_reference, proof_digest, reason_code, validator_version, verification_status,
                       confidence, content_digest
                FROM issue_commit_edge_revision
                UNION ALL
                SELECT 'COMMIT_BUILD', id, edge_id, revision, project_id, commit_id, build_id, source_type,
                       source_reference, proof_reference, proof_digest, reason_code, validator_version,
                       verification_status, confidence, content_digest
                FROM commit_build_edge_revision
                UNION ALL
                SELECT 'BUILD_ARTIFACT', id, edge_id, revision, project_id, build_id, artifact_id, source_type,
                       source_reference, proof_reference, proof_digest, reason_code, validator_version,
                       verification_status, confidence, content_digest
                FROM build_artifact_edge_revision
            ), latest_revision AS (
                SELECT DISTINCT ON (edge_id) * FROM typed_revision ORDER BY edge_id, revision DESC
            )
            SELECT receipt.id receipt_id, snapshot.id snapshot_id, commit_authority.id commit_id,
                   build_authority.id build_record_id, receipt.envelope_digest, receipt.provider,
                   commit_authority.repository commit_repository, commit_authority.commit_id commit_revision,
                   build_authority.repository build_repository, build_authority.source_revision build_revision, receipt.pipeline,
                   receipt.provider_build_id, receipt.build_attempt, receipt.validator_version,
                   receipt.verification_status receipt_status, receipt.confidence receipt_confidence, artifact.checksum_value,
                   edge.edge_type, edge.edge_id, revision.revision_id, revision.revision,
                   edge.from_entity_id, edge.to_entity_id, revision.source_type, revision.source_reference,
                   revision.proof_reference, revision.proof_digest, revision.reason_code, revision.content_digest,
                   revision.verification_status revision_status, revision.confidence revision_confidence,
                   (SELECT count(*) FROM build_provenance_receipt r WHERE r.project_id = receipt.project_id) receipt_count,
                   (SELECT count(*) FROM build_provenance_rejected_receipt r WHERE r.project_id = receipt.project_id) rejected_receipt_count,
                   (SELECT count(*) FROM traceability_edge_identity e WHERE e.project_id = receipt.project_id) edge_identity_count,
                   (SELECT count(*) FROM typed_revision r WHERE r.project_id = receipt.project_id) edge_revision_count,
                   (SELECT count(*) FROM audit_event a WHERE a.project_id = receipt.project_id
                       AND a.action IN ('BUILD_PROVENANCE_INGESTED', 'BUILD_PROVENANCE_REJECTED')) audit_count,
                   (SELECT count(*) FROM outbox_event o WHERE o.aggregate_id = receipt.id
                       AND o.event_type = 'traceability.build-provenance.ingested') outbox_count,
                   (SELECT count(*) FROM traceability_edge_identity e WHERE e.project_id = receipt.project_id
                       AND e.edge_type = 'ARTIFACT_RELEASE') artifact_release_count,
                   (SELECT coalesce(jsonb_agg(jsonb_build_object(
                       'action', a.action, 'aggregateType', a.aggregate_type, 'aggregateId', a.aggregate_id,
                       'afterState', a.after_state)), '[]'::jsonb)::text FROM audit_event a
                       WHERE a.project_id = receipt.project_id
                         AND a.action IN ('BUILD_PROVENANCE_INGESTED', 'BUILD_PROVENANCE_REJECTED')) audit_payload,
                   (SELECT coalesce(jsonb_agg(jsonb_build_object(
                       'eventType', o.event_type, 'aggregateType', o.aggregate_type,
                       'aggregateId', o.aggregate_id, 'payload', o.payload)), '[]'::jsonb)::text FROM outbox_event o
                       WHERE o.aggregate_id = receipt.id
                         AND o.event_type = 'traceability.build-provenance.ingested') outbox_payload
            FROM build_provenance_receipt receipt
            JOIN release_issue_snapshot snapshot
              ON snapshot.id = receipt.release_issue_snapshot_id AND snapshot.project_id = receipt.project_id
            JOIN source_commit commit_authority
              ON commit_authority.id = receipt.source_commit_id AND commit_authority.project_id = receipt.project_id
            JOIN build_record build_authority
              ON build_authority.id = receipt.build_record_id AND build_authority.project_id = receipt.project_id
            JOIN traceability_edge_identity edge ON edge.project_id = receipt.project_id
            JOIN latest_revision revision
              ON revision.edge_id = edge.edge_id AND revision.project_id = edge.project_id
             AND revision.edge_type = edge.edge_type AND revision.from_entity_id = edge.from_entity_id
             AND revision.to_entity_id = edge.to_entity_id
            JOIN artifact artifact ON artifact.id = :artifactId
            WHERE receipt.id = :receiptId
            ORDER BY edge.edge_type
        """.trimIndent()
        private const val SERVICE_TOKEN = "m2-smoke-service"
        private const val USER_TOKEN = "m2-smoke-user"
        private const val WRONG_PROJECT_TOKEN = "m2-smoke-wrong-project"
        private const val PLACEHOLDER_DIGEST =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000"
        private val postgres = PostgreSQLContainer<Nothing>("postgres:17.11").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
