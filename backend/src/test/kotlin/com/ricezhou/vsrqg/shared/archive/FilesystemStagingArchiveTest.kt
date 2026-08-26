package com.ricezhou.vsrqg.shared.archive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveFileOperations
import com.ricezhou.vsrqg.shared.adapter.archive.FilesystemStagingArchiveAdapter
import com.ricezhou.vsrqg.shared.adapter.archive.NioArchiveFileOperations
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchiveEvidence
import com.ricezhou.vsrqg.shared.application.archive.ArchiveIntegrityFailure
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class FilesystemStagingArchiveTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `probe reports local pilot only for an available explicit root`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val harness = harness(root)

        val report = harness.evaluator.evaluateReadiness(harness.policy)

        assertThat(report.state).isEqualTo(ArchiveCapabilityState.LOCAL_PILOT)
        assertThat(report.checks.map { it.name })
            .containsExactly("provider", "stagingRoot", "writable", "checksum")
        assertThat(report.checks).allMatch { it.passed }
        assertThat(harness.adapter.probeCount).isEqualTo(1)
    }

    @Test
    fun `source outside the configured real root is rejected without revealing a path`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val outside = writeSource(tempDirectory.resolve("outside.zip"))
        val harness = harness(root)

        assertThatThrownBy { harness.facade.archive(command(outside)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive source is outside the configured staging root")
            .hasMessageNotContaining(root.toString())
            .hasMessageNotContaining(outside.toString())
        assertThat(Files.exists(outside)).isTrue()
        assertThat(harness.adapter.archiveCount).isEqualTo(1)
    }

    @Test
    fun `source symlink cannot escape the configured real root`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val outside = writeSource(tempDirectory.resolve("outside.zip"))
        val link = root.resolve("source-link.zip")
        val linked = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        assumeTrue(linked, "Symbolic links are unavailable in this test environment")
        val harness = harness(root)

        assertThatThrownBy { harness.facade.archive(command(link)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive source is outside the configured staging root")
        assertThat(Files.exists(outside)).isTrue()
    }

    @Test
    fun `existing payload whose real path escapes the root is rejected`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val payload = writeSource(payloadPath(root))
        val outside = tempDirectory.resolve("outside-payload.zip")
        val harness = harness(root, operations = EscapingRealPathOperations(payload, outside))

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive target escapes the configured staging root")
        assertThat(Files.exists(source)).isTrue()
        assertThat(Files.exists(receiptPath(root))).isFalse()
    }

    @Test
    fun `existing receipt whose real path escapes the root is rejected`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        harness(root).facade.archive(command(source))
        val receipt = receiptPath(root)
        val outside = tempDirectory.resolve("outside-receipt.json")
        val replay = harness(root, operations = EscapingRealPathOperations(receipt, outside))

        assertThatThrownBy { replay.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive target escapes the configured staging root")
        assertThat(Files.exists(source)).isTrue()
    }

    @Test
    fun `all target segments and the prefix are validated again at the adapter boundary`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root)
        val unsafeCommands = listOf(
            command(source).copy(acceptanceId = "../acceptance"),
            command(source).copy(sourceCommit = "commit/child"),
            command(source).copy(sourceArtifactId = "artifact\\child"),
            command(source).copy(sourceArtifactId = ".."),
            command(source).copy(sourceArtifactId = "artifact:stream"),
            command(source).copy(sourceArtifactId = "artifact\u0000stream"),
            command(source).copy(acceptanceId = ""),
        )

        unsafeCommands.forEach { unsafe ->
            assertThatThrownBy { harness.facade.archive(unsafe) }
                .isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("Archive target contains an invalid path segment")
        }
        listOf(
            "../escape/",
            "/absolute/",
            "safe\\escape/",
            "safe/../../escape/",
            "safe/escape:stream/",
            "safe/escape\u0000stream/",
        ).forEach { prefix ->
            val unsafeHarness = harness(root, policy(root).copy(objectPrefix = prefix))
            assertThatThrownBy { unsafeHarness.facade.archive(command(source)) }
                .isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessage("Archive target prefix is invalid")
        }
        assertThat(listRegularFiles(root)).containsExactly(source)
    }

    @Test
    fun `sha mismatch preserves source and produces neither payload nor receipt`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root)

        assertThatThrownBy {
            harness.facade.archive(command(source).copy(expectedSha256 = "0".repeat(64)))
        }.isInstanceOf(ArchiveIntegrityFailure::class.java)
            .hasMessage("Archive payload digest does not match the expected SHA-256")

        assertThat(Files.exists(source)).isTrue()
        assertThat(Files.exists(payloadPath(root))).isFalse()
        assertThat(Files.exists(receiptPath(root))).isFalse()
        assertThat(partials(root)).isEmpty()
    }

    @Test
    fun `same command replay uses the committed receipt and stable locators after a fresh probe`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root, timeProvider = AdvancingTimeProvider())

        val first = harness.facade.archive(command(source))
        val second = harness.facade.archive(command(source))

        assertThat(second).isEqualTo(first)
        assertThat(second.receipt.payload.locator).isEqualTo(payloadPath(root).toUri().toASCIIString())
        assertThat(second.receiptReference.locator).isEqualTo(receiptPath(root).toUri().toASCIIString())
        assertThat(harness.adapter.probeCount).isEqualTo(2)
        assertThat(harness.adapter.archiveCount).isEqualTo(2)
        assertThat(partials(root)).isEmpty()
    }

    @Test
    fun `existing payload with another digest fails closed and is not overwritten`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val target = payloadPath(root)
        Files.createDirectories(target.parent)
        Files.writeString(target, "different payload")
        val original = Files.readAllBytes(target)
        val harness = harness(root)

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveIntegrityFailure::class.java)
            .hasMessage("Existing archive payload does not match the expected SHA-256")
        assertThat(Files.readAllBytes(target)).isEqualTo(original)
        assertThat(Files.exists(source)).isTrue()
        assertThat(Files.exists(receiptPath(root))).isFalse()
    }

    @Test
    fun `different existing receipt fails closed without overwrite`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val receipt = receiptPath(root)
        Files.createDirectories(receipt.parent)
        Files.writeString(receipt, "{\"different\":true}")
        val original = Files.readAllBytes(receipt)
        val harness = harness(root)

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveIntegrityFailure::class.java)
            .hasMessage("Existing archive receipt is not replayable")
        assertThat(Files.readAllBytes(receipt)).isEqualTo(original)
        assertThat(Files.exists(payloadPath(root))).isTrue()
        assertThat(Files.exists(source)).isTrue()
    }

    @Test
    fun `facade probes before the independent enabled gate and never invokes archive`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root, policy(root).copy(enabled = false))

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive is disabled by policy")

        assertThat(harness.adapter.probeCount).isEqualTo(1)
        assertThat(harness.adapter.archiveCount).isZero()
        assertThat(harness.evaluator.evaluateReadiness(harness.policy).state)
            .isEqualTo(ArchiveCapabilityState.LOCAL_PILOT)
        assertThat(Files.exists(source)).isTrue()
        assertThat(Files.exists(payloadPath(root))).isFalse()
    }

    @Test
    fun `facade refuses unconfigured external unverified and company local capability`() {
        val command = command(Path.of("unused.zip"))

        listOf(
            gatedHarness(DeploymentMode.PILOT, ArchiveProvider.NONE, ArchiveCapabilityState.UNCONFIGURED),
            gatedHarness(
                DeploymentMode.PILOT,
                ArchiveProvider.S3_COMPATIBLE,
                ArchiveCapabilityState.EXTERNAL_UNVERIFIED,
            ),
            gatedHarness(DeploymentMode.COMPANY, ArchiveProvider.FILESYSTEM_STAGING, ArchiveCapabilityState.LOCAL_PILOT),
        ).forEach { harness ->
            assertThatThrownBy { harness.facade.archive(command) }
                .isInstanceOf(ArchiveUnavailable::class.java)
            assertThat(harness.adapter.archiveCount).isZero()
            assertThat(harness.adapter.probeCount).isEqualTo(1)
        }
    }

    @ParameterizedTest
    @EnumSource(FailurePoint::class)
    fun `each filesystem failure cleans only its partial and retry starts with a fresh probe`(failure: FailurePoint) {
        val root = Files.createDirectories(tempDirectory.resolve("staging-${failure.name.lowercase()}"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val operations = FailOnceFileOperations(failure)
        val harness = harness(root, operations = operations)

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(IOException::class.java)
            .hasMessage("injected ${failure.name}")
        assertThat(Files.exists(source)).isTrue()
        assertThat(partials(root)).isEmpty()
        if (failure == FailurePoint.RECEIPT_WRITE || failure == FailurePoint.RECEIPT_MOVE) {
            assertThat(Files.exists(payloadPath(root))).isTrue()
        } else {
            assertThat(Files.exists(payloadPath(root))).isFalse()
        }

        val result = harness.facade.archive(command(source))

        assertThat(result.receiptReference.locator).isEqualTo(receiptPath(root).toUri().toASCIIString())
        assertThat(harness.adapter.probeCount).isEqualTo(2)
        assertThat(Files.exists(source)).isTrue()
        assertThat(Files.exists(payloadPath(root))).isTrue()
        assertThat(Files.exists(receiptPath(root))).isTrue()
        assertThat(partials(root)).isEmpty()
    }

    @Test
    fun `concurrent identical commands converge on one payload and replayable receipt`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root)
        val executor = Executors.newFixedThreadPool(2)

        val results = try {
            executor.invokeAll(
                listOf(
                    Callable { harness.facade.archive(command(source)) },
                    Callable { harness.facade.archive(command(source)) },
                ),
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertThat(results).hasSize(2)
        assertThat(results.map { it.receipt.payload.locator }).containsOnly(payloadPath(root).toUri().toASCIIString())
        assertThat(results.map { it.receiptReference.locator }).containsOnly(receiptPath(root).toUri().toASCIIString())
        assertThat(harness.adapter.probeCount).isEqualTo(2)
        assertThat(partials(root)).isEmpty()
    }

    @Test
    fun `successful result records pilot semantics and independent receipt digest`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root)

        val result = harness.facade.archive(command(source))
        val context = harness.adapter.contexts.single()
        val receipt = result.receipt

        assertThat(receipt.longTerm).isFalse()
        assertThat(receipt.retentionPolicy).isEqualTo("PILOT_ONLY")
        assertThat(receipt.immutabilityControl).isEqualTo("NONE")
        assertThat(receipt.policyFingerprint).isEqualTo(context.policyFingerprint)
        assertThat(receipt.capabilityCheckedAt).isEqualTo(context.checkedAt)
        assertThat(receipt.accessOwner).isEqualTo("release-governance")
        assertThat(receipt.payload.provider).isEqualTo(ArchiveProvider.FILESYSTEM_STAGING)
        assertThat(receipt.payload.bucket).isNull()
        assertThat(receipt.payload.versionId).isNull()
        assertThat(receipt.payload.sha256).isEqualTo(command(source).expectedSha256)
        assertThat(receipt.payload.sizeBytes).isEqualTo(Files.size(source))
        assertThat(result.receiptReference.versionId).isNull()
        assertThat(result.receiptReference.sha256).matches("^[0-9a-f]{64}$")
        assertThat(result.receiptReference.sha256).isNotEqualTo(result.receipt.payload.sha256)
        assertThat(Files.readString(receiptPath(root)))
            .doesNotContain("receiptReference", result.receiptReference.sha256)
    }

    private fun harness(
        root: Path,
        policy: ArchivePolicy = policy(root),
        operations: ArchiveFileOperations = NioArchiveFileOperations,
        timeProvider: TimeProvider = TimeProvider { FIXED_TIME },
    ): Harness {
        val delegate = FilesystemStagingArchiveAdapter(
            jacksonObjectMapper().findAndRegisterModules(),
            timeProvider,
            operations,
        )
        val adapter = CountingAdapter(delegate)
        val evaluator = EvaluateArchiveCapability(listOf(adapter), timeProvider)
        return Harness(
            policy = policy,
            evaluator = evaluator,
            adapter = adapter,
            facade = ArchiveEvidence(policy, evaluator, listOf(adapter)),
        )
    }

    private fun gatedHarness(
        mode: DeploymentMode,
        provider: ArchiveProvider,
        state: ArchiveCapabilityState,
    ): Harness {
        val adapter = StateAdapter(provider, state)
        val policy = policy(tempDirectory.resolve("unused")).copy(mode = mode, provider = provider)
        val evaluator = EvaluateArchiveCapability(listOf(adapter), TimeProvider { FIXED_TIME })
        return Harness(policy, evaluator, adapter, ArchiveEvidence(policy, evaluator, listOf(adapter)))
    }

    private fun policy(root: Path) = ArchivePolicy(
        mode = DeploymentMode.PILOT,
        enabled = true,
        checksumVerificationEnabled = true,
        encryptionRequired = true,
        privateAccessRequired = true,
        retentionPolicyRequired = true,
        immutabilityRequired = true,
        provider = ArchiveProvider.FILESYSTEM_STAGING,
        stagingRoot = root.toAbsolutePath(),
        endpoint = null,
        region = null,
        bucket = null,
        objectPrefix = "acceptance/",
        accessOwner = "release-governance",
        retentionPeriod = null,
        probeTimeout = Duration.ofSeconds(5),
        operationTimeout = Duration.ofSeconds(30),
    )

    private fun command(source: Path) = ArchiveCommand(
        acceptanceId = "acceptance-1",
        sourceArtifactId = "artifact-1",
        sourceRunId = "run-1",
        sourceCommit = "0123456789abcdef",
        source = source,
        expectedSha256 = SOURCE_SHA256,
    )

    private fun writeSource(path: Path): Path {
        Files.createDirectories(path.parent)
        return Files.write(path, SOURCE_BYTES)
    }

    private fun payloadPath(root: Path): Path = root.toRealPath()
        .resolve("acceptance/acceptance-1/0123456789abcdef/artifact-1")

    private fun receiptPath(root: Path): Path = payloadPath(root).resolveSibling("artifact-1-archive-receipt.json")

    private fun partials(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".partial") }.toList()
    }

    private fun listRegularFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList()
    }

    private data class Harness(
        val policy: ArchivePolicy,
        val evaluator: EvaluateArchiveCapability,
        val adapter: CountingAdapter,
        val facade: ArchiveEvidence,
    )

    private open class CountingAdapter(private val delegate: ArchiveAdapter) : ArchiveAdapter {
        override val provider: ArchiveProvider = delegate.provider
        var probeCount = 0
            private set
        var archiveCount = 0
            private set
        val contexts = mutableListOf<CapabilityProbeContext>()

        override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> {
            synchronized(this) {
                probeCount += 1
                contexts += context
            }
            return delegate.probe(policy, context)
        }

        override fun archive(
            command: ArchiveCommand,
            policy: ArchivePolicy,
            authorization: ArchiveAuthorization,
        ): ArchiveResult {
            synchronized(this) { archiveCount += 1 }
            return delegate.archive(command, policy, authorization)
        }
    }

    private class StateAdapter(
        provider: ArchiveProvider,
        private val requestedState: ArchiveCapabilityState,
    ) : CountingAdapter(
        object : ArchiveAdapter {
            override val provider = provider

            override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> = when (
                requestedState
            ) {
                ArchiveCapabilityState.UNCONFIGURED,
                ArchiveCapabilityState.EXTERNAL_UNVERIFIED,
                -> listOf(CapabilityCheck("provider", false, "not verified"))
                ArchiveCapabilityState.LOCAL_PILOT,
                ArchiveCapabilityState.EXTERNAL_VERIFIED,
                -> listOf(CapabilityCheck("provider", true, "verified"))
            }

            override fun archive(
                command: ArchiveCommand,
                policy: ArchivePolicy,
                authorization: ArchiveAuthorization,
            ): ArchiveResult = throw AssertionError("Archive must not be invoked")
        },
    )

    private class FailOnceFileOperations(
        private val failure: FailurePoint,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        private var failed = false

        override fun copy(source: Path, target: Path) {
            failOnce(FailurePoint.COPY)
            NioArchiveFileOperations.copy(source, target)
        }

        override fun sha256(path: Path): String {
            failOnce(FailurePoint.DIGEST)
            return NioArchiveFileOperations.sha256(path)
        }

        override fun moveAtomically(source: Path, target: Path) {
            val point = if (target.fileName.toString().endsWith("-archive-receipt.json")) {
                FailurePoint.RECEIPT_MOVE
            } else {
                FailurePoint.PAYLOAD_MOVE
            }
            failOnce(point)
            NioArchiveFileOperations.moveAtomically(source, target)
        }

        override fun write(path: Path, bytes: ByteArray) {
            failOnce(FailurePoint.RECEIPT_WRITE)
            NioArchiveFileOperations.write(path, bytes)
        }

        private fun failOnce(point: FailurePoint) {
            if (!failed && failure == point) {
                failed = true
                throw IOException("injected ${failure.name}")
            }
        }
    }

    private class EscapingRealPathOperations(
        private val escapedPath: Path,
        private val outside: Path,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        override fun toRealPath(path: Path): Path = if (path == escapedPath) {
            outside
        } else {
            NioArchiveFileOperations.toRealPath(path)
        }
    }

    enum class FailurePoint {
        COPY,
        DIGEST,
        PAYLOAD_MOVE,
        RECEIPT_WRITE,
        RECEIPT_MOVE,
    }

    private class AdvancingTimeProvider : TimeProvider {
        private var invocation = 0L

        override fun now(): Instant = synchronized(this) { FIXED_TIME.plusSeconds(invocation++) }
    }

    private companion object {
        val FIXED_TIME: Instant = Instant.parse("2026-08-26T06:00:00Z")
        val SOURCE_BYTES: ByteArray = "pilot archive source".toByteArray()
        const val SOURCE_SHA256 = "a679762fd43b7b71c5b45cba8170c3337dc95cb2506338eb3e76b25efef84167"
    }
}
