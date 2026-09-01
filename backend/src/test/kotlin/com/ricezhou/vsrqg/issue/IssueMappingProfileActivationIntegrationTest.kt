package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.issue.adapter.FixedIssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfileCommand
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfileResult
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRecord
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.StartIssueSync
import com.ricezhou.vsrqg.issue.application.StartIssueSyncCommand
import com.ricezhou.vsrqg.issue.application.StartIssueSyncResult
import com.ricezhou.vsrqg.issue.application.IssueSourceRuntimeDescriptor
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.mockito.Mockito.doAnswer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class IssueMappingProfileActivationIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var activateProfile: ActivateIssueMappingProfile

    @Autowired
    private lateinit var jdbc: JdbcClient

    @MockitoSpyBean
    private lateinit var profileRepository: IssueMappingProfileRepository

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var startIssueSync: StartIssueSync

    @MockitoSpyBean
    private lateinit var issueSyncRepository: IssueSyncRepository

    private val objectMapper = ObjectMapper()
    private lateinit var projectId: String
    private lateinit var otherProjectId: String
    private lateinit var sourceId: String
    private lateinit var releaseManager: Principal
    private lateinit var administrator: Principal
    private lateinit var engineer: Principal

    @BeforeEach
    fun prepareAuthorityAndSource() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        projectId = "project_map_$suffix"
        otherProjectId = "project_other_$suffix"
        sourceId = "source_map_$suffix"
        insertProject(projectId)
        insertProject(otherProjectId)
        releaseManager = insertPrincipal("rm-$suffix", "RELEASE_MANAGER", projectId)
        administrator = insertPrincipal("admin-$suffix", "ADMINISTRATOR", projectId)
        engineer = insertPrincipal("engineer-$suffix", "ENGINEER", projectId)
        insertSource(sourceId, projectId)
    }

    @AfterEach
    fun removeFailureTriggers() {
        listOf("audit", "outbox").forEach { target ->
            jdbc.sql("DROP TRIGGER IF EXISTS reject_mapping_$target ON ${if (target == "audit") "audit_event" else "outbox_event"}").update()
            jdbc.sql("DROP FUNCTION IF EXISTS reject_mapping_$target()").update()
        }
    }

    @Test
    fun `release manager and administrator with configure scope activate profiles`() {
        listOf(releaseManager, administrator).forEachIndexed { index, principal ->
            val definition = validDefinition("open-$index")
            val response = postDefinition("success-$index", definition, principal, 201)
            val result = objectMapper.readTree(response)

            assertThat(result.path("sourceId").asText()).isEqualTo(sourceId)
            assertThat(result.path("schemaVersion").asText()).isEqualTo("jira-mapping-profile/v1")
            assertThat(result.path("mappingVersion").asText()).startsWith("sha256:")
            assertThat(sourceSelector("adapter_version")).isEqualTo("jira-cli-pilot-adapter-v1")
            assertThat(sourceSelector("mapping_version")).isEqualTo(result.path("mappingVersion").asText())
            assertThat(count("issue_mapping_profile", "id", result.path("profileId").asText())).isOne()
            assertThat(
                objectMapper.readTree(
                    json("issue_mapping_profile", "definition", "id", result.path("profileId").asText()),
                ),
            ).isEqualTo(definition)
            assertThat(count("audit_event", "aggregate_id", result.path("profileId").asText())).isOne()
            assertThat(count("outbox_event", "aggregate_id", result.path("profileId").asText())).isOne()
        }
    }

    @Test
    fun `engineer is denied by project authorization before side effects`() {
        assertThatThrownBy { activateProfile.activate(command(engineer, "engineer", validDefinition())) }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThat(count("issue_mapping_profile", "source_id", sourceId)).isZero()
        assertThat(count("idempotency_record", "idempotency_key", "engineer")).isZero()
    }

    @Test
    fun `sync scope alone is denied at HTTP boundary`() {
        mockMvc.post(endpoint()) {
            with(jwtFor(releaseManager, "issue:sync"))
            header("Idempotency-Key", "sync-only")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = validDefinition().toString()
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `cross project activation is denied without leaking profile content`() {
        val outsider = insertPrincipal("other-manager", "RELEASE_MANAGER", otherProjectId)
        val secret = "SECRET-CROSS-PROJECT-ALIAS"
        val response = mockMvc.post(endpoint()) {
            with(jwtFor(outsider, "issue:configure"))
            header("Idempotency-Key", "cross-project")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = validDefinition(secret).toString()
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCESS_DENIED") }
        }.andReturn().response.contentAsString

        assertThat(response).doesNotContain(secret).doesNotContain("old-adapter-v0").doesNotContain("JIRA")
        assertThat(count("issue_mapping_profile", "source_id", sourceId)).isZero()
    }

    @Test
    fun `caller supplied versions and invalid profiles return fixed redacted 422`() {
        listOf("mappingVersion", "adapterVersion").forEach { injectedField ->
            val secret = "SECRET-$injectedField"
            val definition = validDefinition().deepCopy<JsonNode>().also {
                (it as com.fasterxml.jackson.databind.node.ObjectNode).put(injectedField, secret)
            }
            val response = postDefinition("inject-$injectedField", definition, releaseManager, 422)
            assertThat(response).contains("MAPPING_PROFILE_INVALID").contains("PROFILE_STRUCTURE_INVALID")
            assertThat(response).doesNotContain(secret).doesNotContain("statusAliases").doesNotContain("Open")
        }

        val secretAlias = "SECRET-INVALID-ALIAS"
        val invalid = validDefinition(secretAlias).deepCopy<JsonNode>().also {
            (it.path("statusAliases") as com.fasterxml.jackson.databind.node.ObjectNode)
                .set<JsonNode>("UNKNOWN", objectMapper.createArrayNode().add(secretAlias))
        }
        val response = postDefinition("invalid", invalid, releaseManager, 422)
        assertThat(response).contains("MAPPING_PROFILE_INVALID").contains("STATUS_TARGET_INVALID")
        assertThat(response).doesNotContain(secretAlias).doesNotContain("UNKNOWN")
    }

    @Test
    fun `same idempotency request replays one metadata response and different request conflicts`() {
        val first = postDefinition("replay-key", orderedDefinition(), releaseManager, 201)
        val replay = postDefinition("replay-key", reorderedEquivalentDefinition(), releaseManager, 201)
        assertThat(replay).isEqualTo(first)
        val profileId = objectMapper.readTree(first).path("profileId").asText()
        assertThat(count("issue_mapping_profile", "source_id", sourceId)).isOne()
        assertThat(count("audit_event", "aggregate_id", profileId)).isOne()
        assertThat(count("outbox_event", "aggregate_id", profileId)).isOne()

        val conflict = postDefinition("replay-key", validDefinition("different"), releaseManager, 409)
        assertThat(conflict).contains("IDEMPOTENCY_KEY_REUSED")
        assertThat(count("issue_mapping_profile", "source_id", sourceId)).isOne()
    }

    @Test
    fun `concurrent canonical equivalent requests share one activation transaction`() {
        val key = "concurrent-canonical"
        val definitions = listOf(orderedDefinition(), reorderedEquivalentDefinition())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(definitions.size)
        val results = try {
            val futures = definitions.map { definition ->
                executor.submit<ActivateIssueMappingProfileResult> {
                    check(start.await(5, TimeUnit.SECONDS)) { "Concurrent activation start timed out" }
                    activateProfile.activate(command(releaseManager, key, definition))
                }
            }
            start.countDown()
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Concurrent activation executor did not stop" }
        }

        assertThat(results).hasSize(2).allMatch { it == results.first() }
        val result = results.first()
        assertThat(count("issue_mapping_profile", "source_id", sourceId)).isOne()
        assertThat(count("audit_event", "aggregate_id", result.profileId)).isOne()
        assertThat(count("outbox_event", "aggregate_id", result.profileId)).isOne()
        assertThat(count("idempotency_record", "idempotency_key", key)).isOne()
        assertThat(sourceSelector("adapter_version")).isEqualTo("jira-cli-pilot-adapter-v1")
        assertThat(sourceSelector("mapping_version")).isEqualTo(result.mappingVersion)
    }

    @Test
    fun `audit and outbox contain metadata only`() {
        val secret = "SECRET-METADATA-BOUNDARY"
        val result = activateProfile.activate(command(releaseManager, "metadata", validDefinition(secret)))
        val visible = listOf(
            json("audit_event", "after_state", "aggregate_id", result.profileId),
            json("outbox_event", "payload", "aggregate_id", result.profileId),
            json("idempotency_record", "response_body", "idempotency_key", "metadata"),
        ).joinToString()

        assertThat(visible).contains(result.profileId, sourceId, result.schemaVersion, result.mappingVersion)
        assertThat(visible).contains("jira-cli-pilot-adapter-v1")
        assertThat(visible).doesNotContain(secret).doesNotContain("statusAliases").doesNotContain("severityAliases")
    }

    @Test
    fun `repository find records cannot mutate later reads or stored definition`() {
        val definition = validDefinition("repository-authority")
        val result = activateProfile.activate(command(releaseManager, "repository-copy", definition))
        val first = profileRepository.find(sourceId, result.mappingVersion)!!

        (first.definition as com.fasterxml.jackson.databind.node.ObjectNode).put("schemaVersion", "mutated-read")

        val second = profileRepository.find(sourceId, result.mappingVersion)!!
        assertThat(second.definition).isEqualTo(definition)
        assertThat(
            objectMapper.readTree(json("issue_mapping_profile", "definition", "id", result.profileId)),
        ).isEqualTo(definition)
    }

    @Test
    fun `audit or outbox failure rolls back profile selectors governance and idempotency`() {
        listOf("audit", "outbox").forEach { target ->
            installFailure(target)
            val key = "rollback-$target"
            assertThatThrownBy { activateProfile.activate(command(releaseManager, key, validDefinition(target))) }
                .isInstanceOf(DataAccessException::class.java)

            assertThat(sourceSelector("adapter_version")).isEqualTo("old-adapter-v0")
            assertThat(sourceSelector("mapping_version")).isEqualTo("old-mapping-v0")
            assertThat(count("issue_mapping_profile", "source_id", sourceId)).isZero()
            assertThat(count("idempotency_record", "idempotency_key", key)).isZero()
            assertThat(count("audit_event", "correlation_id", "request-$key")).isZero()
            removeFailureTriggers()
        }
    }

    @Test
    fun `profile activation preserves run A and historical issue while new run pins profile B`() {
        val profileA = activateProfile.activate(command(releaseManager, "profile-a", validDefinition("open-a")))
        val runA = startIssueSync.start(syncCommand("run-a", 'a'))
        val pinnedA = requireNotNull(issueSyncRepository.findRun(runA.syncRunId))
        val historicalDigest = "sha256:" + "c".repeat(64)
        insertHistoricalIssue(profileA.mappingVersion, historicalDigest)

        val profileB = activateProfile.activate(command(releaseManager, "profile-b", validDefinition("open-b")))

        assertThat(requireNotNull(issueSyncRepository.findRun(runA.syncRunId))).isEqualTo(pinnedA)
        assertThat(pinnedA.adapterVersion).isEqualTo("jira-cli-pilot-adapter-v1")
        assertThat(pinnedA.mappingVersion).isEqualTo(profileA.mappingVersion)
        assertThat(historicalIssueDigest()).isEqualTo(historicalDigest)

        val runB = startIssueSync.start(syncCommand("run-b", 'b'))
        val pinnedB = requireNotNull(issueSyncRepository.findRun(runB.syncRunId))
        assertThat(pinnedB.adapterVersion).isEqualTo("jira-cli-pilot-adapter-v1")
        assertThat(pinnedB.mappingVersion).isEqualTo(profileB.mappingVersion)
        assertThat(pinnedB.mappingVersion).isNotEqualTo(pinnedA.mappingVersion)
        assertThat(historicalIssueDigest()).isEqualTo(historicalDigest)
    }

    @Test
    fun `start waits for real profile activation lock and pins the committed descriptor snapshot`() {
        activateProfile.activate(command(releaseManager, "race-profile-a", validDefinition("race-open-a")))
        val activationHasLock = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val startAttempted = CountDownLatch(1)
        val syncLockEntered = CountDownLatch(1)
        val syncLockReturned = CountDownLatch(1)
        doAnswer { invocation ->
            val locked = invocation.callRealMethod()
            activationHasLock.countDown()
            check(releaseActivation.await(10, TimeUnit.SECONDS)) {
                "Timed out waiting to release profile activation"
            }
            locked
        }.`when`(profileRepository).lockSource(sourceId)
        doAnswer { invocation ->
            syncLockEntered.countDown()
            val locked = invocation.callRealMethod()
            syncLockReturned.countDown()
            locked
        }.`when`(issueSyncRepository).lockSource(sourceId)

        val pool = Executors.newFixedThreadPool(2)
        try {
            val activation = pool.submit<ActivateIssueMappingProfileResult> {
                activateProfile.activate(
                    command(releaseManager, "race-profile-b", validDefinition("race-open-b")),
                )
            }
            check(activationHasLock.await(10, TimeUnit.SECONDS)) {
                "Profile activation did not acquire the source lock"
            }
            val started = pool.submit<StartIssueSyncResult> {
                startAttempted.countDown()
                startIssueSync.start(syncCommand("race-run-b", 'd'))
            }
            check(startAttempted.await(5, TimeUnit.SECONDS)) { "Sync start was not attempted" }
            check(syncLockEntered.await(5, TimeUnit.SECONDS)) { "Sync did not enter source lock acquisition" }

            assertThat(syncLockReturned.await(750, TimeUnit.MILLISECONDS)).isFalse()
            assertThatThrownBy { started.get(750, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseActivation.countDown()
            val activated = activation.get(10, TimeUnit.SECONDS)
            check(syncLockReturned.await(10, TimeUnit.SECONDS)) { "Sync source lock did not return" }
            val run = requireNotNull(issueSyncRepository.findRun(started.get(10, TimeUnit.SECONDS).syncRunId))
            assertThat(run.adapterVersion).isEqualTo("jira-cli-pilot-adapter-v1")
            assertThat(run.mappingVersion).isEqualTo(activated.mappingVersion)
        } finally {
            releaseActivation.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(10, TimeUnit.SECONDS)) { "Race executor did not stop" }
        }
    }

    private fun command(principal: Principal, key: String, definition: JsonNode) = ActivateIssueMappingProfileCommand(
        principal = principal,
        sourceId = sourceId,
        idempotencyKey = key,
        definition = definition,
        requestId = "request-$key",
    )

    private fun syncCommand(key: String, digestCharacter: Char) = StartIssueSyncCommand(
        principal = releaseManager,
        sourceId = sourceId,
        idempotencyKey = key,
        requestDigest = "sha256:" + digestCharacter.toString().repeat(64),
        requestId = "request-$key",
    )

    private fun postDefinition(key: String, definition: JsonNode, principal: Principal, status: Int): String =
        mockMvc.post(endpoint()) {
            with(jwtFor(principal, "issue:configure"))
            header("Idempotency-Key", key)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = definition.toString()
        }.andExpect { status { isEqualTo(status) } }.andReturn().response.contentAsString

    private fun jwtFor(principal: Principal, scope: String) = jwt()
        .jwt { it.issuer(ISSUER).subject(principal.subject).claim("principal_type", "USER") }
        .authorities(org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_$scope"))

    private fun endpoint() = "/api/v1/issue-sources/$sourceId/mapping-profiles:activate"

    private fun validDefinition(alias: String = "Open"): JsonNode = objectMapper.readTree(
        """
        {
          "schemaVersion":"jira-mapping-profile/v1",
          "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
          "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "statusAliases":{"OPEN":["$alias"]},
          "severityAliases":{"HIGH":["Major"]}
        }
        """.trimIndent(),
    )

    private fun orderedDefinition(): JsonNode = objectMapper.readTree(
        """
        {
          "schemaVersion":"jira-mapping-profile/v1",
          "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
          "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "statusAliases":{"OPEN":["same-open"],"CLOSED":["same-closed"]},
          "severityAliases":{"HIGH":["same-high"],"LOW":["same-low"]}
        }
        """.trimIndent(),
    )

    private fun reorderedEquivalentDefinition(): JsonNode = objectMapper.readTree(
        """
        {
          "severityAliases":{"LOW":["same-low"],"HIGH":["same-high"]},
          "statusAliases":{"CLOSED":["same-closed"],"OPEN":["same-open"]},
          "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
          "schemaVersion":"jira-mapping-profile/v1"
        }
        """.trimIndent(),
    )

    private fun insertProject(id: String) {
        jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :id, :id, now())")
            .param("id", id).update()
    }

    private fun insertPrincipal(subject: String, role: String, assignedProjectId: String): Principal {
        val principal = Principal(ISSUER, subject, service = false)
        val id = "principal_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        jdbc.sql(
            "INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at) " +
                "VALUES (:id, :issuer, :subject, 'USER', false, now())",
        ).param("id", id).param("issuer", ISSUER).param("subject", subject).update()
        jdbc.sql(
            "INSERT INTO project_assignment(project_id, principal_id, role, created_at) " +
                "VALUES (:projectId, :principalId, :role, now())",
        ).param("projectId", assignedProjectId).param("principalId", id).param("role", role).update()
        return principal
    }

    private fun insertSource(id: String, assignedProjectId: String) {
        jdbc.sql(
            """
            INSERT INTO issue_source(
              id, project_id, source_key, source_type, adapter_version,
              mapping_version, enabled, created_at, updated_at
            ) VALUES (
              :id, :projectId, :id, 'JIRA', 'old-adapter-v0',
              'old-mapping-v0', true, now(), now()
            )
            """.trimIndent(),
        ).param("id", id).param("projectId", assignedProjectId).update()
    }

    private fun sourceSelector(column: String): String = jdbc.sql("SELECT $column FROM issue_source WHERE id = :id")
        .param("id", sourceId).query(String::class.java).single()

    private fun insertHistoricalIssue(mappingVersion: String, digest: String) {
        jdbc.sql(
            """
            INSERT INTO normalized_issue(
              id, project_id, source_id, source_issue_id, title, severity, status,
              source_version, source_reference, observed_at, mapping_version, fact_digest, created_at
            ) VALUES (
              :id, :projectId, :sourceId, 'SYNTHETIC-1', 'Synthetic issue', 'HIGH', 'OPEN',
              'v1', 'fixture:synthetic-1', now(), :mappingVersion, :digest, now()
            )
            """.trimIndent(),
        )
            .param("id", "history_${sourceId.takeLast(8)}")
            .param("projectId", projectId)
            .param("sourceId", sourceId)
            .param("mappingVersion", mappingVersion)
            .param("digest", digest)
            .update()
    }

    private fun historicalIssueDigest(): String = jdbc.sql(
        "SELECT fact_digest FROM normalized_issue WHERE source_id = :sourceId AND source_issue_id = 'SYNTHETIC-1'",
    )
        .param("sourceId", sourceId)
        .query(String::class.java)
        .single()

    private fun count(table: String, column: String, value: String): Int = jdbc
        .sql("SELECT count(*) FROM $table WHERE $column = :value")
        .param("value", value).query(Int::class.java).single()

    private fun json(table: String, column: String, predicateColumn: String, value: String): String = jdbc
        .sql("SELECT $column::text FROM $table WHERE $predicateColumn = :value")
        .param("value", value).query(String::class.java).single()

    private fun installFailure(target: String) {
        val table = if (target == "audit") "audit_event" else "outbox_event"
        jdbc.sql(
            """
            CREATE FUNCTION reject_mapping_$target() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN RAISE EXCEPTION 'injected mapping $target failure'; END;
            ${'$'}${'$'}
            """.trimIndent(),
        ).update()
        jdbc.sql(
            "CREATE TRIGGER reject_mapping_$target BEFORE INSERT ON $table " +
                "FOR EACH ROW EXECUTE FUNCTION reject_mapping_$target()",
        ).update()
    }

    private companion object {
        const val ISSUER = "https://idp.vsrqg.test"
    }
}

class IssueMappingProfileActivationIntegrationTestDescriptorContextTest {
    @Test
    fun `duplicate source type descriptor fails application context startup deterministically`() {
        val duplicate = IssueSourceRuntimeDescriptor("JIRA", "other", "other-v1", setOf("x"), "x")

        ApplicationContextRunner()
            .withBean(
                FixedIssueSourceDescriptorRegistry::class.java,
                { FixedIssueSourceDescriptorRegistry(listOf(duplicate, duplicate)) },
            )
            .run { context ->
                assertThat(context.startupFailure).isNotNull
                val visibleFailure = generateSequence(context.startupFailure) { it.cause }
                    .joinToString(" | ") { it.message.orEmpty() }
                assertThat(visibleFailure).contains("DUPLICATE_ISSUE_SOURCE_DESCRIPTOR")
            }
    }
}

class IssueMappingProfileRecordTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `record definition is isolated from caller and getter mutations`() {
        val callerDefinition = objectMapper.readTree(
            """{"schemaVersion":"jira-mapping-profile/v1","aliases":{"OPEN":["open"]}}""",
        )
        val expected = callerDefinition.deepCopy<JsonNode>()
        val record = IssueMappingProfileRecord(
            id = "map_record",
            projectId = "project_record",
            sourceId = "source_record",
            schemaVersion = "jira-mapping-profile/v1",
            mappingVersion = "sha256:" + "a".repeat(64),
            definition = callerDefinition,
            createdBy = "principal_record",
            createdAt = Instant.parse("2026-09-01T00:00:00Z"),
        )

        (callerDefinition as com.fasterxml.jackson.databind.node.ObjectNode).put("schemaVersion", "mutated-caller")
        assertThat(record.definition).isEqualTo(expected)

        (record.definition as com.fasterxml.jackson.databind.node.ObjectNode).put("schemaVersion", "mutated-getter")
        assertThat(record.definition).isEqualTo(expected)
    }
}
