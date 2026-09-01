package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.application.IssueSourceException
import com.ricezhou.vsrqg.issue.application.IssueSourceFailureCode
import com.ricezhou.vsrqg.issue.application.IssueSourcePort
import com.ricezhou.vsrqg.issue.domain.IssueBatch
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import com.ricezhou.vsrqg.issue.domain.IssuePage
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import com.ricezhou.vsrqg.issue.domain.SourceCapabilities
import com.ricezhou.vsrqg.issue.domain.SourceHealth
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

fun interface JiraProcessRunner {
    fun run(argv: List<String>, timeout: Duration, stdoutLimit: Int): JiraProcessResult
}

data class JiraProcessResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val timedOut: Boolean,
    val stderrDigest: String? = null,
)

class JiraCliPilotAdapter(
    private val properties: JiraCliPilotProperties,
    private val processRunner: JiraProcessRunner,
    private val mapper: JiraIssueMapper,
    private val mappingVersion: String,
    private val observedAt: () -> Instant = Instant::now,
) : IssueSourcePort {
    init {
        validateStandaloneProperties(properties)
        require(mappingVersion.isNotBlank()) { "MAPPING_VERSION_REQUIRED" }
        require(mappingVersion == mapper.mappingVersion) { "MAPPING_VERSION_MISMATCH" }
    }

    override fun capabilities() = SourceCapabilities(readOnly = true, incremental = false, tombstones = false)

    override fun fetchChanges(cursor: String?, filter: IssueFilter, pageSize: Int): IssuePage {
        if (cursor != null || pageSize !in 1..properties.maxIssues) fail(IssueSourceFailureCode.INVALID_REQUEST)
        val observation = observedAt()
        val issues = execute(pageSize, observation)
        val watermark = issues.maxByOrNull { Instant.parse(it.sourceVersion) }?.sourceVersion ?: observation.toString()
        return IssuePage(
            issues = issues,
            nextCursor = null,
            sourceWatermark = watermark,
            observedAt = observation,
            mappingVersion = mappingVersion,
            terminal = true,
        )
    }

    override fun fetchByIds(sourceIssueIds: Set<String>): IssueBatch {
        if (sourceIssueIds.isNotEmpty()) fail(IssueSourceFailureCode.CAPABILITY_NOT_SUPPORTED)
        return IssueBatch(emptyList(), emptySet(), observedAt(), mappingVersion)
    }

    override fun health() = SourceHealth(available = true, code = "CONFIGURED")

    private fun execute(limit: Int, observation: Instant): List<NormalizedIssue> {
        val result = try {
            processRunner.run(argv(limit), properties.timeout, MAX_STDOUT_BYTES)
        } catch (_: Exception) {
            fail(IssueSourceFailureCode.PROCESS_FAILED)
        }
        if (result.timedOut) fail(IssueSourceFailureCode.TIMEOUT)
        if (result.stdout.size > MAX_STDOUT_BYTES) fail(IssueSourceFailureCode.OUTPUT_LIMIT_EXCEEDED)
        if (result.exitCode != 0) {
            throw IssueSourceException(IssueSourceFailureCode.PROCESS_FAILED, diagnosticDigest = result.stderrDigest)
        }
        return parseOutput(result.stdout, limit, observation)
    }

    private fun argv(limit: Int): List<String> = listOf(
        properties.cliPath,
        "issue",
        "list",
        "--project",
        properties.project,
        "--paginate",
        "0:$limit",
        "--plain",
        "--no-headers",
        "--no-truncate",
        "--columns",
        COLUMNS,
        "--delimiter=$DELIMITER",
    )

    private fun parseOutput(bytes: ByteArray, limit: Int, observation: Instant): List<NormalizedIssue> {
        val text = decodeUtf8(bytes)
        if (text.isEmpty()) return emptyList()
        val lines = text.split('\n').let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it }
        if (lines.size > limit) fail(IssueSourceFailureCode.OUTPUT_LIMIT_EXCEEDED)
        return lines.map { rawLine ->
            val line = rawLine.removeSuffix("\r")
            val fields = line.split(DELIMITER)
            if (fields.size != FIELD_COUNT || fields.any { it.isBlank() || it.hasControlCharacter() }) {
                fail(IssueSourceFailureCode.INVALID_OUTPUT)
            }
            if (fields[STATUS_INDEX].length > MAX_RAW_MAPPING_TOKEN_LENGTH ||
                fields[SEVERITY_INDEX].length > MAX_RAW_MAPPING_TOKEN_LENGTH
            ) {
                fail(IssueSourceFailureCode.INVALID_OUTPUT)
            }
            normalize(fields, observation)
        }.sortedBy(NormalizedIssue::sourceIssueId)
    }

    private fun normalize(fields: List<String>, observation: Instant): NormalizedIssue {
        val (key, title, rawStatus, rawSeverity, updated) = fields
        if (!SOURCE_ISSUE_ID.matches(key) || !key.startsWith("${properties.project}-")) {
            fail(IssueSourceFailureCode.INVALID_OUTPUT)
        }
        val sourceVersion = parseUpdated(updated)
        val (status, statusWarning) = mapper.status(rawStatus)
        val (severity, severityWarning) = mapper.severity(rawSeverity)
        return NormalizedIssue(
            source = SOURCE,
            sourceIssueId = key,
            title = title,
            severity = severity,
            status = status,
            rawSeverity = rawSeverity,
            rawStatus = rawStatus,
            sourceVersion = sourceVersion,
            sourceReference = "jira:$key",
            observedAt = observation,
            mappingVersion = mappingVersion,
            warnings = setOfNotNull(statusWarning, severityWarning),
        )
    }

    companion object {
        const val MAX_STDOUT_BYTES = 64 * 1024
        private const val SOURCE = "JIRA"
        private const val COLUMNS = "KEY,SUMMARY,STATUS,PRIORITY,UPDATED"
        private const val FIELD_COUNT = 5
        private const val STATUS_INDEX = 2
        private const val SEVERITY_INDEX = 3
        private const val MAX_RAW_MAPPING_TOKEN_LENGTH = 120
        private const val DELIMITER = '\u241f'
        private val SOURCE_ISSUE_ID = Regex("^[A-Z][A-Z0-9_]{1,19}-[1-9][0-9]*$")
    }
}

private fun parseUpdated(raw: String): String {
    try {
        return Instant.parse(raw).toString()
    } catch (_: DateTimeParseException) {
        try {
            return OffsetDateTime.parse(raw, JIRA_CLI_UPDATED_FORMATTER).toInstant().toString()
        } catch (_: DateTimeParseException) {
            fail(IssueSourceFailureCode.INVALID_OUTPUT)
        }
    }
}

internal class DefaultJiraProcessRunner : JiraProcessRunner {
    override fun run(argv: List<String>, timeout: Duration, stdoutLimit: Int): JiraProcessResult {
        val process = ProcessBuilder(argv).start()
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "jira-cli-stream-${STREAM_THREAD_SEQUENCE.incrementAndGet()}")
        }
        val deadline = System.nanoTime() + timeout.toNanos()
        val stdout = executor.submit(Callable { process.inputStream.readBounded(stdoutLimit) })
        val stderrDigest = executor.submit(Callable { process.errorStream.sha256() })
        val futures = listOf(stdout, stderrDigest)
        var result: JiraProcessResult? = null
        var failed = false
        var interrupted = false
        try {
            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                failed = !terminateProcessTree(process)
                result = JiraProcessResult(null, ByteArray(0), timedOut = true)
            } else {
                try {
                    result = JiraProcessResult(
                        exitCode = process.exitValue(),
                        stdout = stdout.get(remainingNanos(deadline), TimeUnit.NANOSECONDS),
                        timedOut = false,
                        stderrDigest = stderrDigest.get(remainingNanos(deadline), TimeUnit.NANOSECONDS),
                    )
                } catch (_: TimeoutException) {
                    failed = !terminateProcessTree(process)
                    result = JiraProcessResult(null, ByteArray(0), timedOut = true)
                }
            }
        } catch (_: InterruptedException) {
            interrupted = true
            failed = true
        } catch (_: Exception) {
            failed = true
        } finally {
            if (process.isAlive && !terminateProcessTree(process)) failed = true
            closeProcessStreams(process)
            if (!awaitFutures(futures, RESOURCE_SETTLE_TIMEOUT)) failed = true
            if (!terminateExecutor(executor, RESOURCE_SETTLE_TIMEOUT)) failed = true
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (failed) throw IssueSourceException(IssueSourceFailureCode.PROCESS_FAILED)
        return result ?: throw IssueSourceException(IssueSourceFailureCode.PROCESS_FAILED)
    }
}

private fun terminateProcessTree(process: Process): Boolean {
    val handles = linkedMapOf<Long, ProcessHandle>()
    return try {
        snapshotDescendants(process).forEach { handles[it.pid()] = it }
        val direct = process.toHandle()
        handles[direct.pid()] = direct

        direct.destroy()
        snapshotDescendants(process).forEach { handles[it.pid()] = it }
        handles.values.filter { it.pid() != direct.pid() }.forEach(ProcessHandle::destroy)
        if (!waitUntilStopped(handles.values, GRACEFUL_TERMINATION_TIMEOUT)) {
            snapshotDescendants(process).forEach { handles[it.pid()] = it }
            direct.destroyForcibly()
            handles.values.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
        }
        val stopped = waitUntilStopped(handles.values, FORCED_TERMINATION_TIMEOUT)
        if (stopped) process.waitFor(PROCESS_REAP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        stopped && handles.values.none(ProcessHandle::isAlive) && !process.isAlive
    } catch (_: Exception) {
        false
    }
}

private fun snapshotDescendants(process: Process): List<ProcessHandle> = process.descendants().use { it.toList() }

private fun waitUntilStopped(handles: Collection<ProcessHandle>, timeout: Duration): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
        try {
            Thread.sleep(PROCESS_POLL_INTERVAL.toMillis())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }
    return handles.none(ProcessHandle::isAlive)
}

private fun closeProcessStreams(process: Process) {
    runCatching { process.outputStream.close() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
}

private fun awaitFutures(futures: List<Future<*>>, timeout: Duration): Boolean {
    val deadline = System.nanoTime() + timeout.toNanos()
    var settled = true
    futures.forEach { future ->
        val completed = try {
            future.get(remainingNanos(deadline), TimeUnit.NANOSECONDS)
            true
        } catch (_: ExecutionException) {
            true
        } catch (_: TimeoutException) {
            future.cancel(true)
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) settled = false
    }
    return settled
}

private fun terminateExecutor(executor: java.util.concurrent.ExecutorService, timeout: Duration): Boolean {
    executor.shutdown()
    try {
        if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) return true
        executor.shutdownNow()
        return executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        executor.shutdownNow()
        Thread.currentThread().interrupt()
        return false
    }
}

private fun validateStandaloneProperties(properties: JiraCliPilotProperties) {
    val path = runCatching { Path.of(properties.cliPath) }.getOrNull()
    if (!properties.enabled ||
        path == null ||
        !path.isAbsolute ||
        !Files.isRegularFile(path) ||
        !PROJECT_KEY.matches(properties.project) ||
        properties.maxIssues !in 1..MAX_ISSUES ||
        properties.timeout <= Duration.ZERO ||
        properties.timeout > MAX_TIMEOUT
    ) {
        throw IllegalArgumentException("JIRA_PILOT_CONFIGURATION_INVALID")
    }
}

private fun decodeUtf8(bytes: ByteArray): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: Exception) {
    fail(IssueSourceFailureCode.INVALID_OUTPUT)
}

private fun String.hasControlCharacter(): Boolean = any(Char::isISOControl)

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var retained = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (retained <= limit) {
            val retain = minOf(read, limit + 1 - retained)
            output.write(buffer, 0, retain)
            retained += retain
        }
    }
    return output.toByteArray()
}

private fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun remainingNanos(deadline: Long): Long = maxOf(1L, deadline - System.nanoTime())

private fun fail(code: IssueSourceFailureCode): Nothing = throw IssueSourceException(code)

private val STREAM_THREAD_SEQUENCE = AtomicLong()
private val GRACEFUL_TERMINATION_TIMEOUT = Duration.ofMillis(250)
private val FORCED_TERMINATION_TIMEOUT = Duration.ofSeconds(2)
private val PROCESS_REAP_TIMEOUT = Duration.ofMillis(250)
private val PROCESS_POLL_INTERVAL = Duration.ofMillis(10)
private val RESOURCE_SETTLE_TIMEOUT = Duration.ofSeconds(2)
private val JIRA_CLI_UPDATED_FORMATTER = DateTimeFormatter
    .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxx", Locale.ROOT)
    .withResolverStyle(ResolverStyle.STRICT)
