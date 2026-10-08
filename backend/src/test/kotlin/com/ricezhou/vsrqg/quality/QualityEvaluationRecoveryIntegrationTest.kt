package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.evidence.EvidenceFixture
import com.ricezhou.vsrqg.evidence.ownedTestRoot
import com.ricezhou.vsrqg.evidence.adapter.ControlledPayloadStore
import com.ricezhou.vsrqg.evidence.adapter.JdbcEvidenceRepository
import com.ricezhou.vsrqg.evidence.application.EvidenceReconciler
import com.ricezhou.vsrqg.evidence.application.QualityEvidenceSource
import com.ricezhou.vsrqg.quality.adapter.JdbcQualityRepository
import com.ricezhou.vsrqg.quality.application.QualityDecisionRunner
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRecord
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRepository
import com.ricezhou.vsrqg.quality.application.QualityPinnedInput
import com.ricezhou.vsrqg.quality.application.QualitySourceReader
import com.ricezhou.vsrqg.quality.application.digest
import com.ricezhou.vsrqg.quality.application.toQualityValue
import com.ricezhou.vsrqg.quality.domain.QualityCanonicalEncoder
import com.ricezhou.vsrqg.testmanagement.application.TestJson
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate

@Timeout(60)
class QualityEvaluationRecoveryIntegrationTest : EvidenceFixture() {
    @MockitoBean private lateinit var sources: QualitySourceReader
    @Autowired private lateinit var evaluations: QualityEvaluationRepository
    @Autowired private lateinit var evidence: QualityEvidenceSource
    @Autowired private lateinit var runner: QualityDecisionRunner
    @Autowired private lateinit var transactions: TransactionTemplate

    @Test
    fun `independent database and payload copy preserve the quality decision digest`() {
        start()
        val (upload, metadata) = available()
        val evidenceId = metadata.path("evidenceId").asText()
        val runId = metadata.path("testRunId").asText()
        val attemptId = metadata.path("attemptId").asText()
        val setId = "set_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val setVersionId = "qrs_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val rule = mapper.readTree(Files.readString(
            Path.of("../contracts/examples/v0.2/quality-evaluation/rule-set.json")))
            .path("rules")[0]
        val definition = mapper.readTree(Files.readString(
            Path.of("../contracts/examples/v0.2/quality-evaluation/rule-set.json"))).deepCopy<ObjectNode>()
        definition.put("project", project)
        definition.put("ruleSetId", setId)
        definition.put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
        val setDigest = digest(QualityCanonicalEncoder().encode(toQualityValue(definition)))
        jdbc.sql("""INSERT INTO quality_rule_set_versions(id,project_id,rule_set_id,rule_set_version,
            state,definition,catalog_version,engine_version,required_issue_refs,selected_case_refs,
            content_digest,author_id,created_at)
            VALUES (:id,:project,:setId,1,'DRAFT',CAST(:definition AS jsonb),2,
            'VSRQG-QUALITY-ENGINE-1','[]'::jsonb,CAST(:cases AS jsonb),:digest,
            :author,:at)""")
            .param("id", setVersionId).param("project", project).param("setId", setId)
            .param("definition", definition.toString())
            .param("cases", definition.path("selectedCaseRefs").toString())
            .param("digest", setDigest).param("author", user.subject)
            .param("at", Timestamp.from(now)).update()
        jdbc.sql("""INSERT INTO quality_rule_versions(id,rule_set_version_id,ordinal,rule_id,
            rule_version,validated_ast) VALUES (:id,:setId,0,'REQUIRED_ISSUE_VERIFIED',1,CAST(:ast AS jsonb))""")
            .param("id", "qrv_" + UUID.randomUUID().toString().replace("-", "").take(16))
            .param("setId", setVersionId).param("ast", rule.toString()).update()
        assertThat(JdbcQualityRepository(jdbc, mapper).publish(
            setVersionId, 0, serviceId, "recovery fixture", now)).isTrue()

        val request = mapper.createObjectNode()
        request.set<JsonNode>("ruleSet", mapper.createObjectNode().put("ruleSetId", setId).put("version", 1))
        request.putArray("testRunIds").add(runId)
        request.put("traceabilitySnapshotId", "trace-fixture")
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        val record = QualityEvaluationRecord("qev_$suffix", "job_$suffix", project, release,
            setVersionId, user.subject, "request-$suffix", request, TestJson.digest(request), now)
        val pinnedEvidence = evidence.pin(setOf(evidenceId), project, release, runId, attemptId)
        evidence.verify(pinnedEvidence)
        val snapshot = mapper.readTree(Files.readString(
            Path.of("../contracts/examples/v0.2/quality-evaluation/snapshot.json"))).deepCopy<ObjectNode>()
        snapshot.put("project", project).put("releaseId", release).put("ruleSetDigest", setDigest)
        (snapshot.path("facts").path("release") as ObjectNode)
            .put("releaseId", release).put("project", project)
        (snapshot.path("facts").path("testResults")[0] as ObjectNode)
            .put("runId", runId).put("attemptId", attemptId)
        (snapshot.path("selections")[0] as ObjectNode).put("runId", runId)
        (snapshot.path("selections")[0].path("selectedAttempt") as ObjectNode)
            .put("attemptId", attemptId)
        snapshot.set<JsonNode>("ruleSet", request.path("ruleSet").deepCopy())
        (snapshot.path("versions") as ObjectNode).put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
        pinnedEvidence.forEach { item ->
            snapshot.withArray("evidenceRefs").addObject()
                .put("evidenceId", item.evidenceId).put("attemptId", attemptId)
                .put("runId", runId).put("type", item.type)
                .put("digest", item.payload.sha256).put("sizeBytes", item.payload.size)
        }
        snapshot.remove("snapshotId")
        snapshot.remove("inputDigest")
        snapshot.put("inputDigest", digest(QualityCanonicalEncoder().encode(toQualityValue(snapshot))))
        val pinned = QualityPinnedInput(snapshot, pinnedEvidence)
        Mockito.`when`(sources.read(project, release, request)).thenReturn(pinned)
        transactions.executeWithoutResult { evaluations.enqueue(record) }
        val claim = evaluations.claimNext(now)!!
        val sealed = evaluations.seal(claim, pinned, now)
        val initialRules = JdbcQualityRepository(jdbc, mapper).rules(setVersionId)
        val decision = runner.evaluate(sealed, initialRules, "qrl_original")
        evaluations.complete(claim, sealed, decision.ruleResults, decision.result, decision.errorCode, now)
        val originalDigest = decision.result!!.path("resultDigest").asText()
        val inventory = recovery.backupInventory(setOf(evidenceId))
        val restoredRoot = ownedTestRoot(Files.createTempDirectory("quality-restored-payload-"))
        inventory.forEach { item ->
            Files.copy(EvidenceFixture.storage.resolve(item.uploadId + ".payload"),
                restoredRoot.resolve(item.uploadId + ".payload"))
        }
        val database = "quality_restore_" + UUID.randomUUID().toString().replace("-", "")
        val dump = "/tmp/" + database + ".dump"
        fun command(vararg arguments: String) {
            val result = postgres.execInContainer(*arguments)
            assertThat(result.exitCode).describedAs(result.stderr).isZero()
        }
        command("pg_dump", "-U", postgres.username, "-d", postgres.databaseName, "-Fc", "-f", dump)
        command("createdb", "-U", postgres.username, database)
        try {
            command("pg_restore", "-U", postgres.username, "-d", database, dump)
            val restoredJdbc = JdbcClient.create(DriverManagerDataSource(
                postgres.jdbcUrl.substringBeforeLast('/') + "/" + database,
                postgres.username, postgres.password))
            val restoredEvidence = EvidenceReconciler(
                JdbcEvidenceRepository(restoredJdbc, mapper), ControlledPayloadStore(restoredRoot), clock)
            assertThat(restoredEvidence.verifyRestored(inventory).map { it.code }).containsExactly("VERIFIED")
            val restoredSnapshot = mapper.readTree(restoredJdbc.sql(
                "SELECT content::text FROM quality_input_snapshots WHERE evaluation_id=:id")
                .param("id", record.id).query(String::class.java).single())
            val restoredRules = JdbcQualityRepository(restoredJdbc, mapper).rules(setVersionId)
            val replay = runner.evaluate(restoredSnapshot, restoredRules, "qrl_after_restore")
            assertThat(replay.result!!.path("resultDigest").asText()).isEqualTo(originalDigest)
            assertThat(restoredJdbc.sql("SELECT result_digest FROM quality_results WHERE evaluation_id=:id")
                .param("id", record.id).query(String::class.java).single()).isEqualTo(originalDigest)
            Files.delete(restoredRoot.resolve(upload.path("uploadId").asText() + ".payload"))
            assertThat(restoredEvidence.verifyRestored(inventory).single().code).isEqualTo("INTEGRITY_ERROR")
            assertThat(runner.evaluate(restoredSnapshot, restoredRules, "qrl_historical_replay")
                .result!!.path("resultDigest").asText()).isEqualTo(originalDigest)
        } finally {
            command("dropdb", "-U", postgres.username, "--force", database)
        }
    }
}
