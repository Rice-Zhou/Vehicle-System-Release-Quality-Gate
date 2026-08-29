package com.ricezhou.vsrqg.issue

import com.ricezhou.vsrqg.issue.adapter.DefaultJiraProcessRunner
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

        val actual = requireNotNull(captured)
        val expectedArguments = listOf(
            "issue", "list",
            "--project", "SAFE",
            "--paginate", "0:12",
            "--plain",
            "--no-headers",
            "--no-truncate",
            "--columns", "KEY,SUMMARY,STATUS,PRIORITY,UPDATED",
            "--delimiter", "\u001f",
        )
        assertThat(actual.size).isEqualTo(expectedArguments.size + 1)
        assertThat(actual.first() == executable.toString()).isTrue()
        assertThat(actual.drop(1) == expectedArguments).isTrue()
        assertThat(actual.none { it in setOf("--raw", "--comments", "--history", "--attachment") }).isTrue()
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
        assertThat(hasNoSensitiveErrorData(error, listOf("credential-value", "server.example.test", "private error", executable.toString()))).isTrue()
    }

    @Test
    fun `rejects untrusted diagnostics and sanitizes every runner exception`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val sensitiveMarker = "runner-sensitive-marker"
        val maliciousDigests = listOf(sensitiveMarker, "A".repeat(64))

        maliciousDigests.forEach { candidate ->
            val adapter = adapter(executable) { _, _, _ -> JiraProcessResult(9, ByteArray(0), false, candidate) }
            val error = catchFailure { adapter.fetchChanges(null, IssueFilter(), 20) }
            assertThat(error.code).isEqualTo(IssueSourceFailureCode.PROCESS_FAILED)
            assertThat(error.diagnosticDigest == null).isTrue()
            assertThat(hasNoSensitiveErrorData(error, listOf(sensitiveMarker, candidate))).isTrue()
        }

        val runnerFailures = listOf<Throwable>(
            IssueSourceException(IssueSourceFailureCode.INVALID_OUTPUT, diagnosticDigest = sensitiveMarker),
            IssueSourceException(IssueSourceFailureCode.PROCESS_FAILED, diagnosticDigest = sha256(sensitiveMarker.toByteArray())),
            IllegalStateException(sensitiveMarker),
        )
        runnerFailures.forEach { runnerFailure ->
            val adapter = adapter(executable) { _, _, _ -> throw runnerFailure }
            val error = catchFailure { adapter.fetchChanges(null, IssueFilter(), 20) }
            assertThat(error.code).isEqualTo(IssueSourceFailureCode.PROCESS_FAILED)
            assertThat(error.diagnosticDigest == null).isTrue()
            assertThat(hasNoSensitiveErrorData(error, listOf(sensitiveMarker))).isTrue()
        }
    }

    @Test
    fun `default runner preserves argument boundaries kills timeouts and bounds process streams`() {
        val runner = DefaultJiraProcessRunner()
        val java = javaExecutable()
        val classpath = Path.of(JiraProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString()
        val prefix = listOf(java.toString(), "-cp", classpath, JiraProcessFixture::class.java.name)

        val argumentResult = runFixture(
            runner,
            prefix + listOf("arguments", "safe value", "literal;token", "literal&token", "literal$(token)"),
            Duration.ofSeconds(5),
            128,
        )
        assertThat(argumentResult.exitCode).isZero()
        assertThat(argumentResult.timedOut).isFalse()
        assertThat(String(argumentResult.stdout, StandardCharsets.UTF_8)).isEqualTo("ARGUMENTS_OK")

        val startedMarker = tempDir.resolve("process-started")
        val survivalMarker = tempDir.resolve("process-survived-timeout")
        val startedAt = System.nanoTime()
        val timeoutResult = runFixture(
            runner,
            prefix + listOf("timeout", startedMarker.toString(), survivalMarker.toString()),
            Duration.ofSeconds(1),
            128,
        )
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)
        assertThat(timeoutResult.timedOut).isTrue()
        assertThat(elapsed < Duration.ofSeconds(5)).isTrue()
        assertThat(Files.exists(startedMarker)).isTrue()
        Thread.sleep(1_500L)
        assertThat(Files.notExists(survivalMarker)).isTrue()

        val stdoutLimit = 32
        val streamResult = runFixture(
            runner,
            prefix + listOf("streams", "4096"),
            Duration.ofSeconds(5),
            stdoutLimit,
        )
        assertThat(streamResult.exitCode).isZero()
        assertThat(streamResult.stdout.size).isEqualTo(stdoutLimit + 1)
        assertThat(streamResult.stderrDigest).isEqualTo(sha256("runner-stderr-marker".toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `disabled defaults load and enabled configuration rejects unsafe deployment and values`() {
        ApplicationContextRunner()
            .withUserConfiguration(JiraCliPilotConfiguration::class.java)
            .run { context ->
                assertThat(context.startupFailure == null).isTrue()
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
                assertThat(context.startupFailure == null).isTrue()
                assertThat(context).hasSingleBean(JiraCliPilotAdapter::class.java)
            }
    }

    private fun assertContextFails(vararg properties: String) {
        ApplicationContextRunner()
            .withUserConfiguration(JiraCliPilotConfiguration::class.java)
            .withPropertyValues(*properties)
            .run { context ->
                assertThat(context.startupFailure != null).isTrue()
                val messages = causeMessages(context.startupFailure)
                assertThat(listOf("private-cli-name", tempDir.toString(), "server.example.test", "credential-value").none(messages::contains)).isTrue()
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
        try {
            action()
        } catch (error: Throwable) {
            assertThat(error is IssueSourceException).isTrue()
            return error as IssueSourceException
        }
        throw AssertionError("EXPECTED_ISSUE_SOURCE_FAILURE")
    }

    private fun causeMessages(failure: Throwable?): String =
        generateSequence(failure) { it.cause }.mapNotNull(Throwable::message).joinToString("\n")

    private fun hasNoSensitiveErrorData(error: IssueSourceException, markers: List<String>): Boolean {
        val exposed = listOfNotNull(error.message, error.toString(), error.diagnosticDigest)
        return markers.none { marker -> exposed.any { value -> value.contains(marker) } }
    }

    private fun javaExecutable(): Path {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath()
    }

    private fun runFixture(
        runner: DefaultJiraProcessRunner,
        argv: List<String>,
        timeout: Duration,
        stdoutLimit: Int,
    ): JiraProcessResult = try {
        runner.run(argv, timeout, stdoutLimit)
    } catch (_: Throwable) {
        throw AssertionError("PROCESS_FIXTURE_FAILED")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
