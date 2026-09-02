package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.issue.adapter.DefaultIssueSourceRuntimeRegistry
import com.ricezhou.vsrqg.issue.adapter.FixedIssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeFactory
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeRegistry
import com.ricezhou.vsrqg.issue.adapter.IssueSyncJobWorker
import com.ricezhou.vsrqg.issue.adapter.JcsIssueMappingProfileCodec
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotProperties
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotRuntimeFactory
import com.ricezhou.vsrqg.issue.adapter.JiraProcessResult
import com.ricezhou.vsrqg.issue.adapter.JiraProcessRunner
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRecord
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileCodec
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRepository
import com.ricezhou.vsrqg.issue.application.IssueSourceRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncRunRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncResultSetMode
import com.ricezhou.vsrqg.issue.application.IssueSyncStatus
import com.ricezhou.vsrqg.issue.application.QueuedIssueSync
import com.ricezhou.vsrqg.issue.application.RunIssueSync
import com.ricezhou.vsrqg.issue.domain.IssuePage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class IssueSourceRuntimeRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    private val objectMapper = ObjectMapper()
    private val codec = JcsIssueMappingProfileCodec(objectMapper)

    @Test
    fun `jira descriptor owns full result set semantics`() {
        val descriptor = FixedIssueSourceDescriptorRegistry().require("JIRA")

        assertThat(descriptor.resultSetMode).isEqualTo(IssueSyncResultSetMode.FULL)
        assertThat(descriptor.filterReference).isEqualTo("all-relevant-issues/v1")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runtimeFailures")
    fun `runtime authority failures are fail closed before process execution`(
        expectedCode: String,
        scenario: RuntimeFailureScenario,
    ) {
        val fixture = fixture(scenario)

        assertThat(fixture.worker.runNext()).isTrue()

        assertThat(fixture.repository.run.status).isEqualTo(IssueSyncStatus.FAILED)
        assertThat(fixture.repository.run.diagnosticCode).isEqualTo(expectedCode)
        assertThat(fixture.repository.jobStatus).isEqualTo(IssueSyncStatus.FAILED)
        assertThat(fixture.repository.jobDiagnostic).isEqualTo(expectedCode)
        assertThat(fixture.repository.successfulCursor).isEqualTo(EXISTING_CURSOR)
        assertThat(fixture.processCalls()).isZero()
        if (scenario == RuntimeFailureScenario.ADAPTER_VERSION_MISMATCH) {
            assertThat(fixture.mappingRepository.requestedMappingVersion).isNull()
        } else {
            assertThat(fixture.mappingRepository.requestedMappingVersion)
                .isEqualTo(fixture.repository.run.mappingVersion)
        }
    }

    @Test
    fun `matching run descriptor and profile execute through the pinned runtime`() {
        val fixture = fixture(RuntimeFailureScenario.NONE)

        assertThat(fixture.worker.runNext()).isTrue()

        assertThat(fixture.repository.run.status).isEqualTo(IssueSyncStatus.SUCCEEDED)
        assertThat(fixture.repository.run.diagnosticCode).isNull()
        assertThat(fixture.repository.jobStatus).isEqualTo(IssueSyncStatus.SUCCEEDED)
        assertThat(fixture.repository.successfulCursor).isNull()
        assertThat(fixture.processCalls()).isOne()
    }

    @Test
    fun `worker source contains no global port selection or ambiguous adapter fallback`() {
        val sourcePath = generateSequence(
            Path.of(IssueSourceRuntimeRegistryTest::class.java.protectionDomain.codeSource.location.toURI())
                .toAbsolutePath(),
        ) { it.parent }
            .map { it.resolve("src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSyncJobWorker.kt") }
            .first(Files::isRegularFile)
        val source = Files.readString(sourcePath)

        assertThat(source).doesNotContain("ObjectProvider<IssueSourcePort>", "ADAPTER_NOT_CONFIGURED")
    }

    @Test
    fun `unexpected runtime failure terminates run and job and remains the visible cause`() {
        val repository = InMemoryIssueSyncRepository(
            run(ADAPTER_VERSION, "sha256:${"a".repeat(64)}", EXISTING_CURSOR),
        )
        val original = IllegalStateException("unexpected runtime failure")
        val worker = IssueSyncJobWorker(
            repository,
            RunIssueSync(repository),
            IssueSourceRuntimeRegistry { throw original },
        )

        assertThatThrownBy(worker::runNext)
            .isInstanceOfSatisfying(IllegalStateException::class.java) { terminal ->
                assertThat(terminal).hasMessage("ISSUE_SYNC_JOB_FAILED")
                assertThat(terminal.cause).isSameAs(original)
            }
        assertThat(repository.run.status).isEqualTo(IssueSyncStatus.FAILED)
        assertThat(repository.run.diagnosticCode).isEqualTo("INTERNAL_ERROR")
        assertThat(repository.jobStatus).isEqualTo(IssueSyncStatus.FAILED)
        assertThat(repository.jobDiagnostic).isEqualTo("INTERNAL_ERROR")
        assertThat(repository.successfulCursor).isEqualTo(EXISTING_CURSOR)
    }

    @Test
    fun `terminal write failure is suppressed without replacing the original runtime cause`() {
        val repository = InMemoryIssueSyncRepository(
            initialRun = run(ADAPTER_VERSION, "sha256:${"a".repeat(64)}", EXISTING_CURSOR),
            failRunTerminalWrite = true,
        )
        val original = IllegalStateException("unexpected runtime failure")
        val worker = IssueSyncJobWorker(
            repository,
            RunIssueSync(repository),
            IssueSourceRuntimeRegistry { throw original },
        )

        assertThatThrownBy(worker::runNext)
            .isInstanceOfSatisfying(IllegalStateException::class.java) { terminal ->
                assertThat(terminal).hasMessage("ISSUE_SYNC_JOB_FAILED")
                assertThat(terminal.cause).isSameAs(original)
                assertThat(original.suppressed.map { it.message }).containsExactly("run terminal write failed")
            }
        assertThat(repository.jobStatus).isEqualTo(IssueSyncStatus.FAILED)
        assertThat(repository.jobDiagnostic).isEqualTo("INTERNAL_ERROR")
    }

    @Test
    fun `duplicate runtime factories for one source type fail fast`() {
        val factory = JiraCliPilotRuntimeFactory(
            JiraCliPilotProperties(),
            JiraProcessRunner { _, _, _ -> error("runner must not execute") },
        )

        assertThatThrownBy {
            DefaultIssueSourceRuntimeRegistry(
                StubMappingProfileRepository(source(run(ADAPTER_VERSION, "mapping", null)), null),
                codec,
                listOf<IssueSourceRuntimeFactory>(factory, factory),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("DUPLICATE_ISSUE_SOURCE_RUNTIME_FACTORY")
    }

    private fun fixture(scenario: RuntimeFailureScenario): Fixture {
        val validDefinition = validDefinition()
        val validMappingVersion = codec.mappingVersion(validDefinition)
        val storedDefinition = when (scenario) {
            RuntimeFailureScenario.DIGEST_TAMPER -> validDefinition.deepCopy().apply {
                withObject("statusAliases").putArray("CLOSED").add("tampered state")
            }
            RuntimeFailureScenario.UNSUPPORTED_SCHEMA -> validDefinition.deepCopy().apply {
                put("schemaVersion", "jira-mapping-profile/v2")
            }
            else -> validDefinition
        }
        val runMappingVersion = when (scenario) {
            RuntimeFailureScenario.MAPPING_VERSION_MISMATCH,
            RuntimeFailureScenario.PROFILE_ONLY_AT_OTHER_VERSION,
            -> "sha256:${"b".repeat(64)}"
            RuntimeFailureScenario.UNSUPPORTED_SCHEMA -> codec.mappingVersion(storedDefinition)
            else -> validMappingVersion
        }
        val storedMappingVersion = when (scenario) {
            RuntimeFailureScenario.UNSUPPORTED_SCHEMA -> codec.mappingVersion(storedDefinition)
            RuntimeFailureScenario.PROFILE_ONLY_AT_OTHER_VERSION -> validMappingVersion
            else -> runMappingVersion
        }
        val run = run(
            adapterVersion = if (scenario == RuntimeFailureScenario.ADAPTER_VERSION_MISMATCH) {
                "jira-cli-pilot-adapter-v2"
            } else {
                ADAPTER_VERSION
            },
            mappingVersion = runMappingVersion,
            cursorBefore = if (scenario == RuntimeFailureScenario.NONE) null else EXISTING_CURSOR,
        )
        val source = source(run)
        val profile = if (scenario == RuntimeFailureScenario.NO_PROFILE) {
            null
        } else {
            profile(storedDefinition, storedMappingVersion)
        }
        val mappingRepository = StubMappingProfileRepository(source, profile)
        val syncRepository = InMemoryIssueSyncRepository(run)
        var processCalls = 0
        val executable = Files.createFile(tempDir.resolve("jira-${scenario.name}.bin")).toAbsolutePath()
        val output = listOf(
            "SAFE-1",
            "Synthetic issue",
            "Open",
            "High",
            "2026-09-01T00:00:00Z",
        ).joinToString("\u241f").toByteArray(StandardCharsets.UTF_8)
        val factory = JiraCliPilotRuntimeFactory(
            properties = JiraCliPilotProperties(true, executable.toString(), "SAFE", 20, Duration.ofSeconds(15)),
            processRunner = JiraProcessRunner { _, _, _ ->
                processCalls++
                JiraProcessResult(0, output, timedOut = false)
            },
            observedAt = { NOW },
        )
        val runtimeCodec: IssueMappingProfileCodec = if (scenario == RuntimeFailureScenario.MAPPING_VERSION_MISMATCH) {
            val compiled = codec.compile(validDefinition)
            object : IssueMappingProfileCodec {
                override fun mappingVersion(definition: JsonNode): String = runMappingVersion
                override fun compile(definition: JsonNode) = compiled
            }
        } else {
            codec
        }
        val registry = DefaultIssueSourceRuntimeRegistry(mappingRepository, runtimeCodec, listOf(factory))
        val worker = IssueSyncJobWorker(syncRepository, RunIssueSync(syncRepository), registry)
        return Fixture(worker, syncRepository, mappingRepository) { processCalls }
    }

    private fun validDefinition(): ObjectNode = objectMapper.createObjectNode().apply {
        put("schemaVersion", "jira-mapping-profile/v1")
        put("normalizationVersion", "unicode-nfc-trim-root-lower/v1")
        put("unknownStatusPolicy", "MAP_TO_UNKNOWN_WITH_WARNING")
        put("unknownSeverityPolicy", "MAP_TO_UNKNOWN_WITH_WARNING")
        putObject("statusAliases").putArray("OPEN").add("open")
        putObject("severityAliases").putArray("HIGH").add("high")
    }

    private fun run(adapterVersion: String, mappingVersion: String, cursorBefore: String?) = IssueSyncRunRecord(
        id = RUN_ID,
        projectId = PROJECT_ID,
        sourceId = SOURCE_ID,
        status = IssueSyncStatus.QUEUED,
        cursorBefore = cursorBefore,
        cursorAfter = null,
        sourceWatermark = null,
        adapterVersion = adapterVersion,
        mappingVersion = mappingVersion,
        resultSetMode = IssueSyncResultSetMode.FULL,
        filterReference = "all-relevant-issues/v1",
        issueCount = 0,
        warningCount = 0,
        diagnosticCode = null,
        createdAt = NOW,
    )

    private fun source(run: IssueSyncRunRecord) = IssueSourceRecord(
        id = SOURCE_ID,
        projectId = PROJECT_ID,
        sourceType = "JIRA",
        adapterVersion = run.adapterVersion,
        mappingVersion = run.mappingVersion,
        enabled = true,
    )

    private fun profile(definition: JsonNode, mappingVersion: String) = IssueMappingProfileRecord(
        id = "profile-1",
        projectId = PROJECT_ID,
        sourceId = SOURCE_ID,
        schemaVersion = definition.path("schemaVersion").asText(),
        mappingVersion = mappingVersion,
        definition = definition,
        createdBy = "principal-1",
        createdAt = NOW,
    )

    private data class Fixture(
        val worker: IssueSyncJobWorker,
        val repository: InMemoryIssueSyncRepository,
        val mappingRepository: StubMappingProfileRepository,
        val processCalls: () -> Int,
    )

    enum class RuntimeFailureScenario {
        NONE,
        NO_PROFILE,
        DIGEST_TAMPER,
        UNSUPPORTED_SCHEMA,
        ADAPTER_VERSION_MISMATCH,
        MAPPING_VERSION_MISMATCH,
        PROFILE_ONLY_AT_OTHER_VERSION,
    }

    private class StubMappingProfileRepository(
        private val source: IssueSourceRecord,
        private val profile: IssueMappingProfileRecord?,
    ) : IssueMappingProfileRepository {
        var requestedMappingVersion: String? = null

        override fun findSource(sourceId: String): IssueSourceRecord? = source.takeIf { it.id == sourceId }
        override fun lockSource(sourceId: String): IssueSourceRecord? = error("not used")
        override fun insert(profile: IssueMappingProfileRecord) = error("not used")
        override fun activate(sourceId: String, adapterVersion: String, mappingVersion: String, activatedAt: Instant) =
            error("not used")

        override fun find(sourceId: String, mappingVersion: String): IssueMappingProfileRecord? {
            requestedMappingVersion = mappingVersion
            return profile?.takeIf { it.sourceId == sourceId && it.mappingVersion == mappingVersion }
        }
    }

    private class InMemoryIssueSyncRepository(
        initialRun: IssueSyncRunRecord,
        private val failRunTerminalWrite: Boolean = false,
    ) : IssueSyncRepository {
        var run = initialRun
        var jobStatus = IssueSyncStatus.QUEUED
        var jobDiagnostic: String? = null
        var successfulCursor: String? = EXISTING_CURSOR
        private var claimed = false

        override fun findSource(sourceId: String): IssueSourceRecord? = error("not used")
        override fun lockSource(sourceId: String): IssueSourceRecord? = error("not used")
        override fun currentSuccessfulCursor(sourceId: String): String? = successfulCursor
        override fun insertRun(run: IssueSyncRunRecord) = error("not used")
        override fun insertJob(
            jobId: String,
            projectId: String,
            idempotencyKey: String,
            payload: JsonNode,
            createdAt: Instant,
        ) = error("not used")

        override fun markRunning(syncRunId: String): IssueSyncRunRecord {
            run = run.copy(status = IssueSyncStatus.RUNNING)
            return run
        }

        override fun persistPage(syncRunId: String, page: IssuePage) {
            run = run.copy(
                issueCount = run.issueCount + page.issues.size,
                warningCount = run.warningCount + page.issues.sumOf { it.warnings.size },
            )
        }

        override fun markSucceeded(
            syncRunId: String,
            successfulCursor: String?,
            sourceWatermark: String,
        ): IssueSyncRunRecord {
            this.successfulCursor = successfulCursor
            run = run.copy(
                status = IssueSyncStatus.SUCCEEDED,
                cursorAfter = successfulCursor,
                sourceWatermark = sourceWatermark,
            )
            return run
        }

        override fun markFailed(syncRunId: String, diagnosticCode: String): IssueSyncRunRecord {
            if (failRunTerminalWrite) throw IllegalStateException("run terminal write failed")
            run = run.copy(status = IssueSyncStatus.FAILED, diagnosticCode = diagnosticCode)
            return run
        }

        override fun findRun(syncRunId: String): IssueSyncRunRecord? = run.takeIf { it.id == syncRunId }

        override fun claimNextJob(): QueuedIssueSync? = if (claimed) {
            null
        } else {
            claimed = true
            jobStatus = IssueSyncStatus.RUNNING
            QueuedIssueSync(RUN_ID, JOB_ID)
        }

        override fun markJobSucceeded(jobId: String) {
            jobStatus = IssueSyncStatus.SUCCEEDED
        }

        override fun markJobFailed(jobId: String, diagnosticCode: String) {
            jobStatus = IssueSyncStatus.FAILED
            jobDiagnostic = diagnosticCode
        }
    }

    companion object {
        private const val PROJECT_ID = "project-1"
        private const val SOURCE_ID = "source-1"
        private const val RUN_ID = "run-1"
        private const val JOB_ID = "job-1"
        private const val EXISTING_CURSOR = "successful-cursor-before-run"
        private const val ADAPTER_VERSION = "jira-cli-pilot-adapter-v1"
        private val NOW = Instant.parse("2026-09-01T00:00:00Z")

        @JvmStatic
        fun runtimeFailures(): Stream<Arguments> = Stream.of(
            Arguments.of("MAPPING_PROFILE_NOT_CONFIGURED", RuntimeFailureScenario.NO_PROFILE),
            Arguments.of("MAPPING_PROFILE_INTEGRITY_FAILED", RuntimeFailureScenario.DIGEST_TAMPER),
            Arguments.of("MAPPING_SCHEMA_UNSUPPORTED", RuntimeFailureScenario.UNSUPPORTED_SCHEMA),
            Arguments.of("ADAPTER_VERSION_MISMATCH", RuntimeFailureScenario.ADAPTER_VERSION_MISMATCH),
            Arguments.of("MAPPING_VERSION_MISMATCH", RuntimeFailureScenario.MAPPING_VERSION_MISMATCH),
            Arguments.of("MAPPING_PROFILE_NOT_CONFIGURED", RuntimeFailureScenario.PROFILE_ONLY_AT_OTHER_VERSION),
        )
    }
}
