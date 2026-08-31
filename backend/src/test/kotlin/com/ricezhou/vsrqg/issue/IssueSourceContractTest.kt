package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.issue.adapter.FixtureFailure
import com.ricezhou.vsrqg.issue.adapter.FixtureIssueSourceAdapter
import com.ricezhou.vsrqg.issue.adapter.FixtureScenario
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotAdapter
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotProperties
import com.ricezhou.vsrqg.issue.adapter.JiraProcessResult
import com.ricezhou.vsrqg.issue.adapter.JiraProcessRunner
import com.ricezhou.vsrqg.issue.application.IssueSourceException
import com.ricezhou.vsrqg.issue.application.IssueSourceFailureCode
import com.ricezhou.vsrqg.issue.application.IssueSourcePort
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import com.ricezhou.vsrqg.issue.domain.IssueMappingWarning
import com.ricezhou.vsrqg.issue.domain.IssuePage
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.issue.domain.SourceCapabilities
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class IssueSourceContractTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("contractSources")
    fun `all transports produce the same normalized digest`(case: ContractCase) {
        val issues = collectPages(case.port)

        assertThat(normalizedDigest(issues)).isEqualTo(EXPECTED_DIGEST)
        assertThat(case.port.capabilities()).isEqualTo(
            SourceCapabilities(readOnly = true, incremental = false, tombstones = case.supportsTombstones),
        )
        assertThat(case.port.health().available).isTrue()
    }

    @Test
    fun `recorded fixture exposes duplicate pages tombstones and unknown mappings without losing facts`() {
        val port = recordedFixture()
        val first = port.fetchChanges(null, IssueFilter(), 20)
        val second = port.fetchChanges(first.nextCursor, IssueFilter(), 20)

        assertThat(first.terminal).isFalse()
        assertThat(second.terminal).isTrue()
        assertThat(second.nextCursor).isNull()
        assertThat(first.issues.map(NormalizedIssue::sourceIssueId)).containsExactly("SAFE-1")
        assertThat(second.issues.map(NormalizedIssue::sourceIssueId)).containsExactly("SAFE-1", "SAFE-2", "SAFE-3")
        val refetchedSecond = port.fetchChanges(first.nextCursor, IssueFilter(), 20)
        assertThat(normalizedDigest(refetchedSecond.issues)).isEqualTo(normalizedDigest(second.issues))
        assertThat(refetchedSecond.nextCursor).isEqualTo(second.nextCursor)
        assertThat(refetchedSecond.sourceWatermark).isEqualTo(second.sourceWatermark)
        assertThat(refetchedSecond.terminal).isEqualTo(second.terminal)
        assertThat(second.issues.single { it.tombstone }.sourceIssueId).isEqualTo("SAFE-3")
        assertThat(second.issues.single { it.sourceIssueId == "SAFE-2" }.status).isEqualTo(IssueStatus.UNKNOWN)
        assertThat(second.issues.single { it.sourceIssueId == "SAFE-2" }.severity).isEqualTo(IssueSeverity.UNKNOWN)
        assertThat(second.issues.single { it.sourceIssueId == "SAFE-2" }.warnings)
            .containsExactlyInAnyOrder(IssueMappingWarning.UNKNOWN_STATUS, IssueMappingWarning.UNKNOWN_SEVERITY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failureCases")
    fun `fixture failures retain bounded retry semantics`(case: FailureCase) {
        val port = FixtureIssueSourceAdapter(
            FixtureScenario(source = "SYNTHETIC", mappingVersion = MAPPING_VERSION, pages = emptyList()),
            failures = mapOf(null to FixtureFailure(case.code, case.retryAfter)),
        )

        assertThatThrownBy { port.fetchChanges(null, IssueFilter(), 20) }
            .isInstanceOfSatisfying(IssueSourceException::class.java) { error ->
                assertThat(error.code).isEqualTo(case.code)
                assertThat(error.retryable).isEqualTo(case.retryable)
                assertThat(error.retryAfter).isEqualTo(case.retryAfter)
                assertThat(error.message).isEqualTo(case.code.name)
            }
    }

    @Test
    fun `retryable fixture failures are finite and keep the caller on the same cursor`() {
        val page = page(
            cursor = null,
            nextCursor = null,
            terminal = true,
            issues = listOf(issue("SAFE-1", "Synthetic alpha issue", IssueSeverity.HIGH, IssueStatus.OPEN, "2026-08-28T10:10:00Z")),
        )
        val port = FixtureIssueSourceAdapter(
            FixtureScenario("SYNTHETIC", MAPPING_VERSION, listOf(page)),
            failures = mapOf(null to FixtureFailure(IssueSourceFailureCode.UPSTREAM_5XX, occurrences = 2)),
        )

        repeat(2) {
            assertThatThrownBy { port.fetchChanges(null, IssueFilter(), 20) }
                .isInstanceOf(IssueSourceException::class.java)
        }
        assertThat(port.fetchChanges(null, IssueFilter(), 20).issues).hasSize(1)
    }

    @Test
    fun `fixture rejects a page larger than page size without truncating or advancing`() {
        val oversized = page(
            cursor = null,
            nextCursor = "must-not-advance",
            terminal = false,
            issues = listOf(
                issue("SAFE-1", "Synthetic alpha issue", IssueSeverity.HIGH, IssueStatus.OPEN, "2026-08-28T10:10:00Z"),
                issue("SAFE-2", "Synthetic beta issue", IssueSeverity.LOW, IssueStatus.OPEN, "2026-08-28T10:11:00Z"),
            ),
        )
        val port = FixtureIssueSourceAdapter(FixtureScenario("SYNTHETIC", MAPPING_VERSION, listOf(oversized)))

        assertThatThrownBy { port.fetchChanges(null, IssueFilter(), 1) }
            .isInstanceOfSatisfying(IssueSourceException::class.java) { error ->
                assertThat(error.code).isEqualTo(IssueSourceFailureCode.INVALID_REQUEST)
            }
        val retried = port.fetchChanges(null, IssueFilter(), 2)
        assertThat(retried.issues.map(NormalizedIssue::sourceIssueId)).containsExactly("SAFE-1", "SAFE-2")
        assertThat(retried.nextCursor).isEqualTo("must-not-advance")
    }

    @Test
    fun `second page failure retries the same cursor and then recovers`() {
        val first = page(
            cursor = null,
            nextCursor = "resume-page-2",
            terminal = false,
            issues = listOf(issue("SAFE-1", "Synthetic alpha issue", IssueSeverity.HIGH, IssueStatus.OPEN, "2026-08-28T10:10:00Z")),
        )
        val second = page(
            cursor = "resume-page-2",
            nextCursor = null,
            terminal = true,
            issues = listOf(issue("SAFE-2", "Synthetic beta issue", IssueSeverity.LOW, IssueStatus.RESOLVED, "2026-08-28T10:11:00Z")),
        )
        val port = FixtureIssueSourceAdapter(
            FixtureScenario("SYNTHETIC", MAPPING_VERSION, listOf(first, second)),
            failures = mapOf("resume-page-2" to FixtureFailure(IssueSourceFailureCode.UPSTREAM_5XX)),
        )
        val requestedCursors = mutableListOf<String?>()

        requestedCursors += null
        val firstResult = port.fetchChanges(null, IssueFilter(), 20)
        requestedCursors += firstResult.nextCursor
        assertThatThrownBy { port.fetchChanges(firstResult.nextCursor, IssueFilter(), 20) }
            .isInstanceOf(IssueSourceException::class.java)
        requestedCursors += firstResult.nextCursor
        val recovered = port.fetchChanges(firstResult.nextCursor, IssueFilter(), 20)

        assertThat(requestedCursors).containsExactly(null, "resume-page-2", "resume-page-2")
        assertThat(firstResult.issues.map(NormalizedIssue::sourceIssueId)).containsExactly("SAFE-1")
        assertThat(recovered.issues.map(NormalizedIssue::sourceIssueId)).containsExactly("SAFE-2")
        assertThat(recovered.terminal).isTrue()
    }

    private fun collectPages(port: IssueSourcePort): List<NormalizedIssue> {
        val byIdentity = linkedMapOf<Pair<String, String>, NormalizedIssue>()
        var cursor: String? = null
        do {
            val page = port.fetchChanges(cursor, IssueFilter(), 20)
            assertPageMetadata(page)
            page.issues.forEach { byIdentity[it.sourceIssueId to it.sourceVersion] = it }
            cursor = page.nextCursor
        } while (!page.terminal)
        return byIdentity.values.sortedBy(NormalizedIssue::sourceIssueId)
    }

    private fun assertPageMetadata(page: IssuePage) {
        assertThat(page.sourceWatermark).isNotBlank()
        assertThat(page.observedAt).isEqualTo(OBSERVED_AT)
        assertThat(page.mappingVersion).isEqualTo(MAPPING_VERSION)
        assertThat(page.terminal).isEqualTo(page.nextCursor == null)
    }

    private fun normalizedDigest(issues: List<NormalizedIssue>): String {
        val canonical = issues.filterNot(NormalizedIssue::tombstone).joinToString("\n") {
            listOf(
                it.sourceIssueId,
                it.title,
                it.severity.name,
                it.status.name,
                it.sourceVersion,
                it.tombstone.toString(),
                it.warnings.sortedBy(IssueMappingWarning::name).joinToString(","),
            ).joinToString("|")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val OBSERVED_AT: Instant = Instant.parse("2026-08-28T10:16:00Z")
        private const val MAPPING_VERSION = "issue-mapping-v1"
        private const val EXPECTED_DIGEST = "d2adbf59486cf1db68092c3dd0478314cf899e7c2e3c177dc5f3af7e48256017"

        @JvmStatic
        fun contractSources(): List<ContractCase> = listOf(
            ContractCase("synthetic fixture", syntheticFixture(), supportsTombstones = true),
            ContractCase("recorded internal fixture", recordedFixture(), supportsTombstones = true),
            ContractCase("Jira process fixture", jiraFixture(), supportsTombstones = false),
        )

        @JvmStatic
        fun failureCases(): List<FailureCase> = listOf(
            FailureCase(IssueSourceFailureCode.RATE_LIMITED, retryable = true, retryAfter = Duration.ofSeconds(7)),
            FailureCase(IssueSourceFailureCode.UPSTREAM_5XX, retryable = true),
            FailureCase(IssueSourceFailureCode.UNAUTHORIZED, retryable = false),
            FailureCase(IssueSourceFailureCode.FORBIDDEN, retryable = false),
            FailureCase(IssueSourceFailureCode.TIMEOUT, retryable = true),
            FailureCase(IssueSourceFailureCode.INVALID_OUTPUT, retryable = false),
        )

        private fun syntheticFixture(): IssueSourcePort = FixtureIssueSourceAdapter(
            FixtureScenario(
                source = "SYNTHETIC",
                mappingVersion = MAPPING_VERSION,
                pages = listOf(
                    page(
                        cursor = null,
                        nextCursor = "synthetic-page-2",
                        terminal = false,
                        issues = listOf(issue("SAFE-1", "Synthetic alpha issue", IssueSeverity.HIGH, IssueStatus.OPEN, "2026-08-28T10:10:00Z")),
                    ),
                    page(
                        cursor = "synthetic-page-2",
                        nextCursor = null,
                        terminal = true,
                        issues = listOf(
                            issue("SAFE-1", "Synthetic alpha issue", IssueSeverity.HIGH, IssueStatus.OPEN, "2026-08-28T10:10:00Z"),
                            issue(
                                "SAFE-2",
                                "Synthetic beta issue",
                                IssueSeverity.UNKNOWN,
                                IssueStatus.UNKNOWN,
                                "2026-08-28T10:15:30Z",
                                warnings = setOf(IssueMappingWarning.UNKNOWN_STATUS, IssueMappingWarning.UNKNOWN_SEVERITY),
                            ),
                            issue("SAFE-3", "Synthetic removed issue", IssueSeverity.LOW, IssueStatus.RESOLVED, "2026-08-28T10:14:00Z", tombstone = true),
                        ),
                    ),
                ),
            ),
        )

        private fun recordedFixture(): IssueSourcePort {
            val resource = requireNotNull(IssueSourceContractTest::class.java.getResourceAsStream("/m2/issues/fixture-pages.json"))
            return resource.use { FixtureIssueSourceAdapter.fromJson(jacksonObjectMapper(), it) }
        }

        private fun jiraFixture(): IssueSourcePort {
            val executable = Files.createTempFile("vsrqg-jira-contract-", ".bin").toAbsolutePath()
            executable.toFile().deleteOnExit()
            val delimiter = '\u241f'
            val stdout = listOf(
                listOf("SAFE-1", "Synthetic alpha issue", "Open", "High", "2026-08-28T10:10:00Z"),
                listOf("SAFE-2", "Synthetic beta issue", "Unmapped state", "Unmapped priority", "2026-08-28T10:15:30Z"),
            ).joinToString("\n") { it.joinToString(delimiter.toString()) }.toByteArray(StandardCharsets.UTF_8)
            return JiraCliPilotAdapter(
                JiraCliPilotProperties(true, executable.toString(), "SAFE", 20, Duration.ofSeconds(15)),
                JiraProcessRunner { _, _, _ -> JiraProcessResult(0, stdout, timedOut = false) },
                observedAt = { OBSERVED_AT },
            )
        }

        private fun page(
            cursor: String?,
            nextCursor: String?,
            terminal: Boolean,
            issues: List<NormalizedIssue>,
        ) = com.ricezhou.vsrqg.issue.adapter.FixturePage(
            cursor = cursor,
            issues = issues,
            nextCursor = nextCursor,
            sourceWatermark = "2026-08-28T10:15:30Z",
            observedAt = OBSERVED_AT,
            terminal = terminal,
        )

        private fun issue(
            id: String,
            title: String,
            severity: IssueSeverity,
            status: IssueStatus,
            version: String,
            tombstone: Boolean = false,
            warnings: Set<IssueMappingWarning> = emptySet(),
        ) = NormalizedIssue(
            source = "SYNTHETIC",
            sourceIssueId = id,
            title = title,
            severity = severity,
            status = status,
            rawSeverity = severity.name,
            rawStatus = status.name,
            sourceVersion = version,
            sourceReference = "ref:$id",
            observedAt = OBSERVED_AT,
            mappingVersion = MAPPING_VERSION,
            tombstone = tombstone,
            warnings = warnings,
        )
    }

    data class ContractCase(val name: String, val port: IssueSourcePort, val supportsTombstones: Boolean) {
        override fun toString(): String = name
    }

    data class FailureCase(
        val code: IssueSourceFailureCode,
        val retryable: Boolean,
        val retryAfter: Duration? = null,
    ) {
        override fun toString(): String = code.name
    }
}
