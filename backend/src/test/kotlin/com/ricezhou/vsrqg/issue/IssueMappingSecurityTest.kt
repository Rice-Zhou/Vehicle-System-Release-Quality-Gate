package com.ricezhou.vsrqg.issue

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.access.application.ProjectAuthorization
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.issue.adapter.FixedIssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.adapter.IssueRuntimeConfigurationException
import com.ricezhou.vsrqg.issue.adapter.IssueRuntimeFailureCode
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeRegistry
import com.ricezhou.vsrqg.issue.adapter.IssueSyncJobWorker
import com.ricezhou.vsrqg.issue.adapter.JcsIssueMappingProfileCodec
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotAdapter
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotProperties
import com.ricezhou.vsrqg.issue.adapter.JiraIssueMapper
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.ActivateIssueMappingProfileCommand
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRecord
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRepository
import com.ricezhou.vsrqg.issue.application.IssueSourceException
import com.ricezhou.vsrqg.issue.application.IssueSourceRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncRunRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncStatus
import com.ricezhou.vsrqg.issue.application.MappingProfileInvalid
import com.ricezhou.vsrqg.issue.application.QueuedIssueSync
import com.ricezhou.vsrqg.issue.application.RunIssueSync
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import com.ricezhou.vsrqg.issue.domain.IssuePage
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.problem.ProblemHandler
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest

class IssueMappingSecurityTest {
    @TempDir
    lateinit var tempDir: Path

    private val objectMapper = ObjectMapper()
    private val codec = JcsIssueMappingProfileCodec(objectMapper)
    private var capturedLogs: CapturedLogs? = null

    @AfterEach
    fun detachLogCapture() {
        capturedLogs?.close()
    }

    @Test
    fun `mapping authority surfaces retain only safe metadata and fixed diagnostics`() {
        val definition = sensitiveDefinition()
        val governance = CapturingGovernanceStore()
        val repository = CapturingProfileRepository()
        val logs = captureApplicationLogs()

        val activation = ActivateIssueMappingProfile(
            authorizer = ProjectAuthorizer { _, _, _ -> ProjectAuthorization(PRINCIPAL_ID) },
            codec = codec,
            idempotentExecutor = DirectIdempotentExecutor,
            repository = repository,
            descriptorRegistry = FixedIssueSourceDescriptorRegistry(),
            governanceStore = governance,
            idGenerator = { PROFILE_ID },
            timeProvider = { NOW },
            objectMapper = objectMapper,
        ).activate(
            ActivateIssueMappingProfileCommand(
                principal = Principal("synthetic-issuer", PRINCIPAL_ID, false),
                sourceId = SOURCE_ID,
                idempotencyKey = "synthetic-idempotency",
                definition = definition,
                requestId = REQUEST_ID,
            ),
        )

        val invalidDefinition = sensitiveDefinition().apply {
            (path("statusAliases") as ObjectNode).set<JsonNode>(
                "NOT_A_STATUS",
                objectMapper.createArrayNode().add(ALIAS_TOKEN),
            )
        }
        val validationFailure = catchMappingFailure { codec.compile(invalidDefinition) }
        val request = MockHttpServletRequest(
            "POST",
            "/api/v1/issue-sources/$SOURCE_ID/mapping-profiles:activate",
        ).apply { setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, REQUEST_ID) }
        val problem = ProblemHandler(ProblemWriter(objectMapper))
            .safeValidationFailure(validationFailure, request)
            .body!!

        val cliPath = Files.createFile(tempDir.resolve(CLI_PATH_MARKER)).toAbsolutePath()
        val cliFailure = catchIssueSourceFailure {
            JiraCliPilotAdapter(
                JiraCliPilotProperties(true, cliPath.toString(), "SAFE", 20, Duration.ofSeconds(15)),
                { _, _, _ -> throw IllegalStateException(RUNNER_SECRET) },
                JiraIssueMapper(codec.compile(definition)),
                activation.mappingVersion,
                observedAt = { NOW },
            ).fetchChanges(null, IssueFilter(), 1)
        }

        val jobRepository = CapturingJobRepository()
        val runtimeFailure = IssueRuntimeConfigurationException(
            IssueRuntimeFailureCode.MAPPING_PROFILE_INTEGRITY_FAILED,
        )
        IssueSyncJobWorker(
            jobRepository,
            mock(RunIssueSync::class.java),
            IssueSourceRuntimeRegistry { throw runtimeFailure },
        ).runNext()

        val audit = requireNotNull(governance.audit)
        val outbox = requireNotNull(governance.outbox)
        assertThat(audit.fieldNames().asSequence().toList()).containsExactlyInAnyOrderElementsOf(SAFE_METADATA_FIELDS)
        assertThat(outbox.fieldNames().asSequence().toList()).containsExactlyInAnyOrderElementsOf(SAFE_METADATA_FIELDS)
        assertThat(problem.code).isEqualTo("MAPPING_PROFILE_INVALID")
        assertThat(problem.violations).containsExactly(mapOf("code" to "STATUS_TARGET_INVALID"))
        assertThat(cliFailure.message).isEqualTo("PROCESS_FAILED")
        assertThat(cliFailure.diagnosticDigest).isNull()
        assertThat(jobRepository.runDiagnostic).isEqualTo("MAPPING_PROFILE_INTEGRITY_FAILED")
        assertThat(jobRepository.jobResult).isEqualTo("MAPPING_PROFILE_INTEGRITY_FAILED")

        val visible = listOf(
            validationFailure.toString(),
            validationFailure.violationCodes.joinToString(),
            cliFailure.toString(),
            objectMapper.writeValueAsString(problem),
            audit.toString(),
            outbox.toString(),
            objectMapper.createObjectNode().put("diagnosticCode", jobRepository.jobResult).toString(),
            logs.rendered(),
        ).joinToString("\n")
        (SENSITIVE_MARKERS + definition.toString() + invalidDefinition.toString()).forEach { marker ->
            assertThat(visible).doesNotContain(marker)
        }
    }

    private fun sensitiveDefinition(): ObjectNode = objectMapper.readTree(
        """
        {
          "schemaVersion":"jira-mapping-profile/v1",
          "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
          "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "statusAliases":{"OPEN":["synthetic-open","$DEFINITION_MARKER","$ALIAS_TOKEN","$ISSUE_TITLE","$SERVER_URL","$CLI_PATH_MARKER","$STDOUT_MARKER","$STDERR_MARKER","$CREDENTIAL_MARKER"]},
          "severityAliases":{"HIGH":["synthetic-high"]}
        }
        """.trimIndent(),
    ) as ObjectNode

    private fun catchMappingFailure(action: () -> Unit): MappingProfileInvalid = try {
        action()
        throw AssertionError("EXPECTED_MAPPING_PROFILE_FAILURE")
    } catch (failure: MappingProfileInvalid) {
        failure
    }

    private fun catchIssueSourceFailure(action: () -> Unit): IssueSourceException = try {
        action()
        throw AssertionError("EXPECTED_ISSUE_SOURCE_FAILURE")
    } catch (failure: IssueSourceException) {
        failure
    }

    private fun captureApplicationLogs(): CapturedLogs {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return CapturedLogs(logger, appender).also { capturedLogs = it }
    }

    private class CapturedLogs(
        private val logger: Logger,
        private val appender: ListAppender<ILoggingEvent>,
    ) {
        val events: List<ILoggingEvent>
            get() = appender.list.toList()

        fun rendered(): String = events.joinToString("\n") { event ->
            listOfNotNull(
                event.formattedMessage,
                event.throwableProxy?.className,
                event.throwableProxy?.message,
            ).joinToString(" ")
        }

        fun close() {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private object DirectIdempotentExecutor : IdempotentExecutor {
        override fun <T : Any> execute(
            scope: String,
            principalId: String,
            key: String,
            requestDigest: String,
            responseType: Class<T>,
            action: () -> T,
        ): T = action()
    }

    private class CapturingGovernanceStore : GovernanceStore {
        var audit: JsonNode? = null
        var outbox: JsonNode? = null

        override fun appendAudit(
            projectId: String,
            actorId: String,
            action: String,
            resourceType: String,
            resourceId: String,
            requestId: String,
            reason: String?,
            beforeState: JsonNode?,
            afterState: JsonNode?,
        ) {
            audit = afterState?.deepCopy()
        }

        override fun appendOutbox(
            eventType: String,
            aggregateType: String,
            aggregateId: String,
            payload: JsonNode,
        ) {
            outbox = payload.deepCopy()
        }
    }

    private class CapturingProfileRepository : IssueMappingProfileRepository {
        private val source = IssueSourceRecord(
            SOURCE_ID,
            PROJECT_ID,
            "JIRA",
            "unconfigured",
            "unconfigured",
            true,
        )
        private var profile: IssueMappingProfileRecord? = null

        override fun findSource(sourceId: String) = source.takeIf { sourceId == SOURCE_ID }
        override fun lockSource(sourceId: String) = findSource(sourceId)
        override fun insert(profile: IssueMappingProfileRecord) {
            this.profile = profile
        }
        override fun activate(sourceId: String, adapterVersion: String, mappingVersion: String, activatedAt: Instant) = Unit
        override fun find(sourceId: String, mappingVersion: String) =
            profile?.takeIf { it.sourceId == sourceId && it.mappingVersion == mappingVersion }
    }

    private class CapturingJobRepository : IssueSyncRepository {
        private val run = IssueSyncRunRecord(
            "sync_synthetic",
            PROJECT_ID,
            SOURCE_ID,
            IssueSyncStatus.QUEUED,
            null,
            null,
            null,
            "jira-cli-pilot-adapter-v1",
            "sha256:" + "a".repeat(64),
            0,
            0,
            null,
            NOW,
        )
        var runDiagnostic: String? = null
        var jobResult: String? = null

        override fun claimNextJob() = QueuedIssueSync(run.id, "job_synthetic")
        override fun findRun(syncRunId: String) = run.takeIf { syncRunId == run.id }
        override fun markFailed(syncRunId: String, diagnosticCode: String): IssueSyncRunRecord {
            runDiagnostic = diagnosticCode
            return run.copy(status = IssueSyncStatus.FAILED, diagnosticCode = diagnosticCode)
        }
        override fun markJobFailed(jobId: String, diagnosticCode: String) {
            jobResult = diagnosticCode
        }
        override fun findSource(sourceId: String): IssueSourceRecord? = unused()
        override fun lockSource(sourceId: String): IssueSourceRecord? = unused()
        override fun currentSuccessfulCursor(sourceId: String): String? = unused()
        override fun insertRun(run: IssueSyncRunRecord) = unused<Unit>()
        override fun insertJob(jobId: String, projectId: String, idempotencyKey: String, payload: JsonNode, createdAt: Instant) = unused<Unit>()
        override fun markRunning(syncRunId: String): IssueSyncRunRecord = unused()
        override fun persistPage(syncRunId: String, page: IssuePage) = unused<Unit>()
        override fun markSucceeded(syncRunId: String, successfulCursor: String?, sourceWatermark: String): IssueSyncRunRecord = unused()
        override fun markJobSucceeded(jobId: String) = unused<Unit>()

        private fun <T> unused(): T = throw AssertionError("UNEXPECTED_REPOSITORY_CALL")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-01T08:00:00Z")
        const val PRINCIPAL_ID = "principal_synthetic"
        const val PROJECT_ID = "project_synthetic"
        const val SOURCE_ID = "source_synthetic"
        const val PROFILE_ID = "map_synthetic"
        const val REQUEST_ID = "request_synthetic"
        const val DEFINITION_MARKER = "synthetic-open-definition-private"
        const val ALIAS_TOKEN = "synthetic-open-alias-private"
        const val ISSUE_TITLE = "synthetic-issue-title-private"
        const val SERVER_URL = "https://private-jira.example.invalid"
        const val CLI_PATH_MARKER = "private-cli-path.exe"
        const val STDOUT_MARKER = "private-stdout-content"
        const val STDERR_MARKER = "private-stderr-content"
        const val CREDENTIAL_MARKER = "credential=private-token"
        const val RUNNER_SECRET = "$ISSUE_TITLE $SERVER_URL $STDOUT_MARKER $STDERR_MARKER $CREDENTIAL_MARKER"
        val SENSITIVE_MARKERS = listOf(
            DEFINITION_MARKER,
            ALIAS_TOKEN,
            ISSUE_TITLE,
            SERVER_URL,
            CLI_PATH_MARKER,
            STDOUT_MARKER,
            STDERR_MARKER,
            CREDENTIAL_MARKER,
            RUNNER_SECRET,
        )
        val SAFE_METADATA_FIELDS = listOf(
            "schemaVersion",
            "profileId",
            "projectId",
            "sourceId",
            "adapterVersion",
            "mappingVersion",
            "requestId",
        )
    }
}
