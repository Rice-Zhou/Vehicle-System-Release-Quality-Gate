package com.ricezhou.vsrqg.issue

import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotAdapter
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotConfiguration
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotProperties
import com.ricezhou.vsrqg.issue.adapter.JiraProcessResult
import com.ricezhou.vsrqg.issue.adapter.JiraProcessRunner
import com.ricezhou.vsrqg.issue.application.IssueSourceException
import com.ricezhou.vsrqg.issue.application.IssueSourceFailureCode
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class JiraCliPilotAdapterTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `uses only the fixed read-only argv`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        var captured: List<String>? = null
        val adapter = adapter(executable, maxIssues = 12) { argv, timeout, stdoutLimit ->
            captured = argv
            assertThat(timeout).isEqualTo(Duration.ofSeconds(15))
            assertThat(stdoutLimit).isPositive()
            JiraProcessResult(0, validLine(), timedOut = false)
        }

        adapter.fetchChanges(null, IssueFilter(), 12)

        assertThat(captured).containsExactly(
            executable.toString(),
            "issue", "list",
            "--project", "SAFE",
            "--paginate", "0:12",
            "--plain",
            "--no-headers",
            "--no-truncate",
            "--columns", "KEY,SUMMARY,STATUS,PRIORITY,UPDATED",
            "--delimiter", "\u001f",
        )
        assertThat(captured).doesNotContain("--raw", "--comments", "--history", "--attachment")
    }

    @Test
    fun `callers cannot inject cursor filter flags paths or an oversized page`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val runner = JiraProcessRunner { _, _, _ -> error("runner must not execute") }
        val adapter = JiraCliPilotAdapter(properties(executable, maxIssues = 10), runner)

        assertFixedFailure(IssueSourceFailureCode.INVALID_REQUEST) {
            adapter.fetchChanges("caller-cursor", IssueFilter(), 10)
        }
        listOf(0, 11, 21).forEach { size ->
            assertFixedFailure(IssueSourceFailureCode.INVALID_REQUEST) {
                adapter.fetchChanges(null, IssueFilter(), size)
            }
        }
        assertFixedFailure(IssueSourceFailureCode.INVALID_REQUEST) {
            adapter.fetchByIds(setOf("SAFE-1", "OTHER-2"))
        }
        assertFixedFailure(IssueSourceFailureCode.INVALID_REQUEST) {
            adapter.fetchByIds((1..11).map { "SAFE-$it" }.toSet())
        }
    }

    @Test
    fun `fetch by ids is bounded filtered and deterministic`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val stdout = listOf(
            validRecord("SAFE-3", "2026-08-28T10:13:00Z"),
            validRecord("SAFE-1", "2026-08-28T10:11:00Z"),
            validRecord("SAFE-2", "2026-08-28T10:12:00Z"),
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val adapter = adapter(executable) { _, _, _ -> JiraProcessResult(0, stdout, false) }

        val batch = adapter.fetchByIds(linkedSetOf("SAFE-3", "SAFE-1"))

        assertThat(batch.issues.map { it.sourceIssueId }).containsExactly("SAFE-1", "SAFE-3")
        assertThat(batch.missingIds).isEmpty()
    }

    @Test
    fun `rejects timeout invalid utf8 malformed rows control characters and bounded output`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val cases = listOf(
            JiraProcessResult(null, ByteArray(0), timedOut = true) to IssueSourceFailureCode.TIMEOUT,
            JiraProcessResult(0, byteArrayOf(0xC3.toByte(), 0x28), false) to IssueSourceFailureCode.INVALID_OUTPUT,
            JiraProcessResult(0, "only\u001ffour\u001fcolumns\u001fhere".toByteArray(), false) to IssueSourceFailureCode.INVALID_OUTPUT,
            JiraProcessResult(0, "SAFE-1\u001fbad\ttitle\u001fOpen\u001fHigh\u001f2026-08-28T10:10:00Z".toByteArray(), false) to IssueSourceFailureCode.INVALID_OUTPUT,
            JiraProcessResult(0, ByteArray(JiraCliPilotAdapter.MAX_STDOUT_BYTES + 1), false) to IssueSourceFailureCode.OUTPUT_LIMIT_EXCEEDED,
        )

        cases.forEach { (result, code) ->
            val adapter = adapter(executable) { _, _, _ -> result }
            assertFixedFailure(code) { adapter.fetchChanges(null, IssueFilter(), 20) }
        }
    }

    @Test
    fun `rejects excess rows and nonzero exit without exposing sensitive process data`() {
        val executable = Files.createFile(tempDir.resolve("private-cli-name.bin")).toAbsolutePath()
        val excess = (1..3).joinToString("\n") { validRecord("SAFE-$it", "2026-08-28T10:10:0${it}Z") }
            .toByteArray(StandardCharsets.UTF_8)
        val limited = adapter(executable, maxIssues = 2) { _, _, _ -> JiraProcessResult(0, excess, false) }
        assertFixedFailure(IssueSourceFailureCode.OUTPUT_LIMIT_EXCEEDED) {
            limited.fetchChanges(null, IssueFilter(), 2)
        }

        val sensitiveStderr = "credential-value server.example.test private error".toByteArray()
        val stderrDigest = sha256(sensitiveStderr)
        val failed = adapter(executable) { _, _, _ -> JiraProcessResult(3, ByteArray(0), false, stderrDigest) }
        val error = catchFailure { failed.fetchChanges(null, IssueFilter(), 20) }

        assertThat(error.code).isEqualTo(IssueSourceFailureCode.PROCESS_FAILED)
        assertThat(error.diagnosticDigest).isEqualTo(stderrDigest)
        assertThat(error.message).isEqualTo("PROCESS_FAILED")
        assertThat(error.toString())
            .doesNotContain("credential-value", "server.example.test", "private error", executable.toString(), "issue list")
    }

    @Test
    fun `disabled defaults load and enabled configuration rejects unsafe deployment and values`() {
        ApplicationContextRunner()
            .withUserConfiguration(JiraCliPilotConfiguration::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(JiraCliPilotAdapter::class.java)
            }

        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val base = arrayOf(
            "vsrqg.jira.pilot.enabled=true",
            "vsrqg.jira.pilot.cli-path=$executable",
            "vsrqg.jira.pilot.project=SAFE",
            "vsrqg.jira.pilot.max-issues=20",
            "vsrqg.jira.pilot.timeout=PT15S",
        )
        assertContextFails(*base, "vsrqg.deployment.mode=COMPANY")
        assertContextFails("vsrqg.jira.pilot.enabled=true")
        assertContextFails(*base.withReplacement("vsrqg.jira.pilot.cli-path=relative-cli"))
        assertContextFails(*base.withReplacement("vsrqg.jira.pilot.cli-path=$tempDir"))
        assertContextFails(*base.withReplacement("vsrqg.jira.pilot.project=bad-project"))
        assertContextFails(*base.withReplacement("vsrqg.jira.pilot.max-issues=0"))
        assertContextFails(*base.withReplacement("vsrqg.jira.pilot.max-issues=21"))
        assertContextFails(*base.withReplacement("vsrqg.jira.pilot.timeout=PT0S"))
        assertContextFails(*base.withReplacement("vsrqg.jira.pilot.timeout=PT2M"))

        ApplicationContextRunner()
            .withUserConfiguration(JiraCliPilotConfiguration::class.java)
            .withPropertyValues(*base)
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(JiraCliPilotAdapter::class.java)
            }
    }

    private fun assertContextFails(vararg properties: String) {
        ApplicationContextRunner()
            .withUserConfiguration(JiraCliPilotConfiguration::class.java)
            .withPropertyValues(*properties)
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(causeMessages(context.startupFailure))
                    .doesNotContain("private-cli-name", tempDir.toString(), "server.example.test", "credential-value")
            }
    }

    private fun Array<String>.withReplacement(replacement: String): Array<String> {
        val key = replacement.substringBefore('=')
        return map { if (it.substringBefore('=') == key) replacement else it }.toTypedArray()
    }

    private fun adapter(
        executable: Path,
        maxIssues: Int = 20,
        runner: JiraProcessRunner,
    ) = JiraCliPilotAdapter(properties(executable, maxIssues), runner, observedAt = { Instant.parse("2026-08-28T10:16:00Z") })

    private fun properties(executable: Path, maxIssues: Int) = JiraCliPilotProperties(
        enabled = true,
        cliPath = executable.toString(),
        project = "SAFE",
        maxIssues = maxIssues,
        timeout = Duration.ofSeconds(15),
    )

    private fun validLine(): ByteArray = validRecord("SAFE-1", "2026-08-28T10:10:00Z").toByteArray(StandardCharsets.UTF_8)

    private fun validRecord(id: String, updated: String): String =
        listOf(id, "Synthetic process issue", "Open", "High", updated).joinToString("\u001f")

    private fun assertFixedFailure(code: IssueSourceFailureCode, action: () -> Unit) {
        val error = catchFailure(action)
        assertThat(error.code).isEqualTo(code)
        assertThat(error.message).isEqualTo(code.name)
    }

    private fun catchFailure(action: () -> Unit): IssueSourceException {
        var failure: IssueSourceException? = null
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(IssueSourceException::class.java) { failure = it }
        return requireNotNull(failure)
    }

    private fun causeMessages(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.mapNotNull(Throwable::message).joinToString("\n")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
