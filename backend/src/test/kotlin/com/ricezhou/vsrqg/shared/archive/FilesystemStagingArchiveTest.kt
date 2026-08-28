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
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceipt
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
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
    fun `probe fails closed for expected IO but does not swallow programmer errors`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val ioHarness = harness(root, operations = ProbeFailureOperations(root, IOException("path leak: $root")))
        val bugHarness = harness(root, operations = ProbeFailureOperations(root, AssertionError("programmer bug")))

        val ioReport = ioHarness.evaluator.evaluateReadiness(ioHarness.policy)

        assertThat(ioReport.state).isEqualTo(ArchiveCapabilityState.UNCONFIGURED)
        assertThat(ioReport.checks.map { it.detail }).containsOnly("verified", "not verified")
        assertThatThrownBy { bugHarness.evaluator.evaluateReadiness(bugHarness.policy) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessage("programmer bug")
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
    fun `injected source real path escape is always rejected`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val outside = tempDirectory.resolve("outside-source.zip")
        val harness = harness(root, operations = EscapingRealPathOperations(source, outside))

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive source is outside the configured staging root")
        assertThat(Files.exists(source)).isTrue()
        assertThat(Files.exists(payloadPath(root))).isFalse()
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
    fun `payload and receipt namespaces prevent cross type object collisions`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root)
        val plain = command(source).copy(sourceArtifactId = "x")
        val receiptShaped = command(source).copy(sourceArtifactId = "x-archive-receipt.json")

        val first = harness.facade.archive(plain)
        val second = harness.facade.archive(receiptShaped)

        assertThat(first.receipt.payload.locator).isNotEqualTo(second.receipt.payload.locator)
        assertThat(first.receiptReference.locator).isNotEqualTo(second.receiptReference.locator)
        assertThat(Path.of(URI(first.receipt.payload.locator))).isEqualTo(payloadPath(root, plain))
        assertThat(Path.of(URI(second.receipt.payload.locator))).isEqualTo(payloadPath(root, receiptShaped))
        assertThat(Path.of(URI(first.receiptReference.locator))).isEqualTo(receiptPath(root, plain))
        assertThat(Path.of(URI(second.receiptReference.locator))).isEqualTo(receiptPath(root, receiptShaped))
    }

    @Test
    fun `case distinct IDs map to distinct lowercase fixed length object names`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root)
        val upper = command(source).copy(
            acceptanceId = "Case-A",
            sourceCommit = "Commit-A",
            sourceArtifactId = "Artifact-A",
        )
        val lower = command(source).copy(
            acceptanceId = "case-a",
            sourceCommit = "commit-a",
            sourceArtifactId = "artifact-a",
        )

        val upperResult = harness.facade.archive(upper)
        val lowerResult = harness.facade.archive(lower)

        assertThat(upperResult.receipt.payload.locator).isNotEqualTo(lowerResult.receipt.payload.locator)
        listOf(upperResult, lowerResult).forEach { result ->
            val payload = Path.of(URI(result.receipt.payload.locator))
            val receipt = Path.of(URI(result.receiptReference.locator))
            assertThat(payload).isEqualTo(payloadPath(root, result.receipt.toCommand(source)))
            assertThat(payload.fileName.toString()).matches("^[0-9a-f]{64}$")
            assertThat(receipt.fileName.toString()).matches("^[0-9a-f]{64}\\.json$")
            assertThat(payload.toString()).doesNotContain(
                result.receipt.acceptanceId,
                result.receipt.sourceCommit,
                result.receipt.sourceArtifactId,
            )
            assertThat(receipt.toString()).doesNotContain(
                result.receipt.acceptanceId,
                result.receipt.sourceCommit,
                result.receipt.sourceArtifactId,
            )
        }
    }

    @Test
    fun `target directory symlink is rejected before any outside directory is created`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val outside = Files.createDirectories(tempDirectory.resolve("outside"))
        val symlink = root.resolve("acceptance")
        val operations = SimulatedDirectorySymlinkOperations(symlink, outside)
        val harness = harness(root, operations = operations)

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive target escapes the configured staging root")
        assertThat(Files.list(outside).use { it.toList() }).isEmpty()
        assertThat(Files.exists(source)).isTrue()
        assertThat(partials(root)).isEmpty()
    }

    @Test
    fun `directory replacement race is rejected before resolving the next segment outside root`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val outside = Files.createDirectories(tempDirectory.resolve("outside"))
        val replacedDirectory = root.resolve("acceptance")
        val operations = SimulatedDirectoryReplacementOperations(replacedDirectory, outside)
        val harness = harness(root, operations = operations)

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(Files.list(outside).use { it.toList() }).isEmpty()
        assertThat(Files.exists(source)).isTrue()
        assertThat(partials(root)).isEmpty()
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
    fun `stable command always maps to the same hashed payload and receipt locators`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val archiveCommand = command(source)
        val harness = harness(root, timeProvider = AdvancingTimeProvider())

        val first = harness.facade.archive(archiveCommand)
        val second = harness.facade.archive(archiveCommand)

        assertThat(first.receipt.payload.locator).isEqualTo(payloadPath(root, archiveCommand).toUri().toASCIIString())
        assertThat(first.receiptReference.locator).isEqualTo(receiptPath(root, archiveCommand).toUri().toASCIIString())
        assertThat(root.toRealPath().relativize(payloadPath(root)).joinToString("/") { it.toString() })
            .isEqualTo(
                "acceptance/payload/" +
                    "7db117164a99cd51c878805c3ae187752dddd3ade4591481d54271b81d5fc7d3/" +
                    "fab74b14e0bb5d9ab3ff5dcc2e69cc421a6b3680b19b4b8270447c40f704c543/" +
                    "03ff81ba37bbec1d88deed7ce8de10e41e6a8ed0cad45c161f518954c02289b0",
            )
        assertThat(second.receipt.payload.locator).isEqualTo(first.receipt.payload.locator)
        assertThat(second.receiptReference.locator).isEqualTo(first.receiptReference.locator)
    }

    @Test
    fun `fixed naming vectors separate the same value across all identifier domains`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val archiveCommand = command(source).copy(
            acceptanceId = "same-id",
            sourceCommit = "same-id",
            sourceArtifactId = "same-id",
        )

        val result = harness(root).facade.archive(archiveCommand)

        assertThat(result.receipt.payload.locator).isEqualTo(payloadPath(root, archiveCommand).toUri().toASCIIString())
        assertThat(result.receiptReference.locator).isEqualTo(receiptPath(root, archiveCommand).toUri().toASCIIString())
        assertThat(
            listOf(
                encodedId(ACCEPTANCE_ID_DOMAIN, "same-id"),
                encodedId(SOURCE_COMMIT_DOMAIN, "same-id"),
                encodedId(SOURCE_ARTIFACT_ID_DOMAIN, "same-id"),
            ),
        ).containsExactly(
            "5f65cd55018cfbcf59f2ae11dcdca08a25b82adba6718c0b4c3d863151bbec0b",
            "d736427f6f558d98c398b2c2b0c878b1f2392aa40d22f3f2fb8ac977d9028b0b",
            "425053646641b93cba1bf3fa6ec09949ea195fc85877222a40c1f833b79ed349",
        ).doesNotHaveDuplicates()
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
        val expectedStage = when (failure) {
            FailurePoint.COPY -> "Archive payload copy failed"
            FailurePoint.DIGEST -> "Archive payload digest failed"
            FailurePoint.PAYLOAD_MOVE -> "Archive payload commit failed"
            FailurePoint.RECEIPT_WRITE -> "Archive receipt write failed"
            FailurePoint.RECEIPT_MOVE -> "Archive receipt commit failed"
        }

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage(expectedStage)
            .message()
            .doesNotContain(root.toString(), source.toString())
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

    @ParameterizedTest
    @EnumSource(CommittedObject::class)
    fun `post link cleanup failure keeps target valid and concurrent retry removes only the owned orphan`(
        committedObject: CommittedObject,
    ) {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val operations = FailPostLinkCleanupOperations(committedObject)
        val harness = harness(root, operations = operations)

        assertThatThrownBy { harness.facade.archive(command(source)) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive partial cleanup failed")
        assertThat(Files.exists(payloadPath(root))).isTrue()
        assertThat(NioArchiveFileOperations.sha256(payloadPath(root))).isEqualTo(SOURCE_SHA256)
        assertThat(Files.exists(receiptPath(root))).isEqualTo(committedObject == CommittedObject.RECEIPT)
        assertThat(partials(root)).hasSize(1)
        val foreignPartial = Files.writeString(root.resolve("foreign-call.partial"), "foreign")
        val executor = Executors.newFixedThreadPool(2)

        val replays = try {
            executor.invokeAll(
                listOf(
                    Callable { harness.facade.archive(command(source)) },
                    Callable { harness.facade.archive(command(source)) },
                ),
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertThat(replays.map { it.receipt.payload.locator })
            .containsOnly(payloadPath(root).toUri().toASCIIString())
        assertThat(NioArchiveFileOperations.sha256(payloadPath(root))).isEqualTo(SOURCE_SHA256)
        assertThat(partials(root)).containsExactly(foreignPartial)
        assertThat(Files.readString(foreignPartial)).isEqualTo("foreign")
        assertThat(Files.exists(source)).isTrue()
    }

    @ParameterizedTest
    @EnumSource(ProgrammerFailure::class)
    fun `programmer failures remain visible and still clean the owned partial`(failure: ProgrammerFailure) {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val injected = failure.create()
        val harness = harness(root, operations = ProgrammerFailureOperations(injected))

        val thrown = requireNotNull(runCatching { harness.facade.archive(command(source)) }.exceptionOrNull())

        assertThat(thrown).isSameAs(injected)
        assertThat(partials(root)).isEmpty()
        assertThat(Files.exists(source)).isTrue()
    }

    @Test
    fun `cleanup failure is suppressed behind the controlled primary stage error`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root, operations = CopyAndCleanupFailureOperations(root))

        val thrown = requireNotNull(runCatching { harness.facade.archive(command(source)) }.exceptionOrNull())

        assertThat(thrown).isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive payload copy failed")
        val cleanupFailure = thrown.suppressed.single()
        assertThat(cleanupFailure).isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(cleanupFailure.message).isEqualTo("Archive partial cleanup failed")
        assertThat(thrown.message).doesNotContain(root.toString(), source.toString())
        assertThat(Files.exists(source)).isTrue()
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
    fun `concurrent different receipt semantics never overwrite the create-only winner`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val operations = ReceiptCommitBarrierOperations()
        val harness = harness(root, operations = operations)
        val executor = Executors.newFixedThreadPool(2)
        val commands = listOf(
            command(source).copy(sourceRunId = "run-a"),
            command(source).copy(sourceRunId = "run-b"),
        )

        val outcomes = try {
            executor.invokeAll(
                commands.map { archiveCommand ->
                    Callable { runCatching { harness.facade.archive(archiveCommand) } }
                },
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val successes = outcomes.mapNotNull { it.getOrNull() }
        val failures = outcomes.mapNotNull { it.exceptionOrNull() }
        assertThat(successes).hasSize(1)
        assertThat(failures).singleElement().isInstanceOf(ArchiveIntegrityFailure::class.java)
        val stored = jacksonObjectMapper().findAndRegisterModules()
            .readValue(Files.readAllBytes(receiptPath(root)), ArchiveReceipt::class.java)
        assertThat(stored).isEqualTo(successes.single().receipt)
        assertThat(NioArchiveFileOperations.sha256(receiptPath(root)))
            .isEqualTo(successes.single().receiptReference.sha256)
        assertThat(partials(root)).isEmpty()
    }

    @Test
    fun `successful result records pilot semantics and independent receipt digest`() {
        val root = Files.createDirectories(tempDirectory.resolve("staging"))
        val source = writeSource(root.resolve("incoming/source.zip"))
        val harness = harness(root)

        val localResult = harness.facade.archive(command(source))
        val context = harness.adapter.contexts.single()
        val receipt = localResult.receipt

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
        assertThat(localResult.receiptReference.versionId).isNull()
        assertThat(localResult.receiptReference.sha256).matches("^[0-9a-f]{64}$")
        assertThat(localResult.receiptReference.sha256).isNotEqualTo(localResult.receipt.payload.sha256)
        assertThat(localResult.receiptReference.sizeBytes).isEqualTo(Files.size(receiptPath(root)))
        assertThat(localResult.receiptReference.sizeBytes).isPositive()
        assertThat(localResult.runtimeIdentity).isNull()
        assertThat(Files.readString(receiptPath(root)))
            .doesNotContain("receiptReference", localResult.receiptReference.sha256)
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

    private fun payloadPath(root: Path, command: ArchiveCommand = command(Path.of("unused"))): Path = root.toRealPath()
        .resolve("acceptance/payload")
        .resolve(encodedId(ACCEPTANCE_ID_DOMAIN, command.acceptanceId))
        .resolve(encodedId(SOURCE_COMMIT_DOMAIN, command.sourceCommit))
        .resolve(encodedId(SOURCE_ARTIFACT_ID_DOMAIN, command.sourceArtifactId))

    private fun receiptPath(root: Path, command: ArchiveCommand = command(Path.of("unused"))): Path = root.toRealPath()
        .resolve("acceptance/receipt")
        .resolve(encodedId(ACCEPTANCE_ID_DOMAIN, command.acceptanceId))
        .resolve(encodedId(SOURCE_COMMIT_DOMAIN, command.sourceCommit))
        .resolve("${encodedId(SOURCE_ARTIFACT_ID_DOMAIN, command.sourceArtifactId)}.json")

    private fun encodedId(domain: String, value: String): String = requireNotNull(PRECOMPUTED_ID_HASHES[domain to value]) {
        "Missing independent test vector"
    }

    private fun ArchiveReceipt.toCommand(source: Path) = ArchiveCommand(
        acceptanceId = acceptanceId,
        sourceArtifactId = sourceArtifactId,
        sourceRunId = sourceRunId,
        sourceCommit = sourceCommit,
        source = source,
        expectedSha256 = sourceSha256,
    )

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

        override fun linkCreateOnly(source: Path, target: Path) {
            val point = if (target.parent.parent.parent.fileName.toString() == "receipt") {
                FailurePoint.RECEIPT_MOVE
            } else {
                FailurePoint.PAYLOAD_MOVE
            }
            failOnce(point)
            NioArchiveFileOperations.linkCreateOnly(source, target)
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

    private class ProbeFailureOperations(
        private val root: Path,
        private val failure: Throwable,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        override fun toRealPath(path: Path): Path {
            if (path == root) throw failure
            return NioArchiveFileOperations.toRealPath(path)
        }
    }

    private class FailPostLinkCleanupOperations(
        private val committedObject: CommittedObject,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        private var failNextCleanup = false
        private var failed = false

        @Synchronized
        override fun linkCreateOnly(source: Path, target: Path) {
            NioArchiveFileOperations.linkCreateOnly(source, target)
            val targetObject = if (target.parent.parent.parent.fileName.toString() == "receipt") {
                CommittedObject.RECEIPT
            } else {
                CommittedObject.PAYLOAD
            }
            if (!failed && targetObject == committedObject) {
                failNextCleanup = true
            }
        }

        @Synchronized
        override fun deleteIfExists(path: Path) {
            if (failNextCleanup) {
                failNextCleanup = false
                failed = true
                throw IOException("simulated partial delete failure: $path")
            }
            NioArchiveFileOperations.deleteIfExists(path)
        }
    }

    private class ProgrammerFailureOperations(
        private val failure: Throwable,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        override fun copy(source: Path, target: Path) {
            throw failure
        }
    }

    private class CopyAndCleanupFailureOperations(
        private val root: Path,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        override fun copy(source: Path, target: Path) {
            throw IOException("copy leaked paths: $source $target $root")
        }

        override fun deleteIfExists(path: Path) {
            throw IOException("cleanup leaked path: $path")
        }
    }

    private class SimulatedDirectorySymlinkOperations(
        private val symlink: Path,
        private val outside: Path,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        override fun existsNoFollow(path: Path): Boolean =
            path == symlink || NioArchiveFileOperations.existsNoFollow(path)

        override fun isDirectoryNoFollow(path: Path): Boolean =
            path != symlink && NioArchiveFileOperations.isDirectoryNoFollow(path)

        override fun toRealPath(path: Path): Path = if (path.startsWith(symlink)) {
            outside.resolve(symlink.relativize(path))
        } else {
            NioArchiveFileOperations.toRealPath(path)
        }
    }

    private class ReceiptCommitBarrierOperations : ArchiveFileOperations by NioArchiveFileOperations {
        private val receiptBarrier = CyclicBarrier(2)

        override fun linkCreateOnly(source: Path, target: Path) {
            if (target.parent.parent.parent.fileName.toString() == "receipt") {
                receiptBarrier.await()
            }
            NioArchiveFileOperations.linkCreateOnly(source, target)
        }
    }

    private class SimulatedDirectoryReplacementOperations(
        private val replacedDirectory: Path,
        private val outside: Path,
    ) : ArchiveFileOperations by NioArchiveFileOperations {
        private var directoryChecks = 0

        override fun existsNoFollow(path: Path): Boolean {
            if (path == replacedDirectory) return true
            if (path.startsWith(replacedDirectory)) {
                Files.createDirectories(outside.resolve("unexpected-child"))
                return false
            }
            return NioArchiveFileOperations.existsNoFollow(path)
        }

        override fun isDirectoryNoFollow(path: Path): Boolean = if (path == replacedDirectory) {
            directoryChecks += 1
            directoryChecks == 1
        } else {
            NioArchiveFileOperations.isDirectoryNoFollow(path)
        }

        override fun toRealPath(path: Path): Path = if (path == replacedDirectory) {
            replacedDirectory
        } else {
            NioArchiveFileOperations.toRealPath(path)
        }

        override fun createDirectory(path: Path) {
            if (path.startsWith(replacedDirectory)) {
                Files.createDirectories(outside.resolve("unexpected-child"))
                throw IOException("simulated directory replacement")
            }
            NioArchiveFileOperations.createDirectory(path)
        }
    }

    enum class FailurePoint {
        COPY,
        DIGEST,
        PAYLOAD_MOVE,
        RECEIPT_WRITE,
        RECEIPT_MOVE,
    }

    enum class CommittedObject {
        PAYLOAD,
        RECEIPT,
    }

    enum class ProgrammerFailure {
        ILLEGAL_STATE,
        ASSERTION_ERROR,
        ;

        fun create(): Throwable = when (this) {
            ILLEGAL_STATE -> IllegalStateException("programmer failure")
            ASSERTION_ERROR -> AssertionError("programmer failure")
        }
    }

    private class AdvancingTimeProvider : TimeProvider {
        private var invocation = 0L

        override fun now(): Instant = synchronized(this) { FIXED_TIME.plusSeconds(invocation++) }
    }

    private companion object {
        val FIXED_TIME: Instant = Instant.parse("2026-08-26T06:00:00Z")
        val SOURCE_BYTES: ByteArray = "pilot archive source".toByteArray()
        const val SOURCE_SHA256 = "a679762fd43b7b71c5b45cba8170c3337dc95cb2506338eb3e76b25efef84167"
        const val ACCEPTANCE_ID_DOMAIN = "vsrqg.archive.path.v1/acceptanceId"
        const val SOURCE_COMMIT_DOMAIN = "vsrqg.archive.path.v1/sourceCommit"
        const val SOURCE_ARTIFACT_ID_DOMAIN = "vsrqg.archive.path.v1/sourceArtifactId"
        val PRECOMPUTED_ID_HASHES = mapOf(
            (ACCEPTANCE_ID_DOMAIN to "acceptance-1") to
                "7db117164a99cd51c878805c3ae187752dddd3ade4591481d54271b81d5fc7d3",
            (SOURCE_COMMIT_DOMAIN to "0123456789abcdef") to
                "fab74b14e0bb5d9ab3ff5dcc2e69cc421a6b3680b19b4b8270447c40f704c543",
            (SOURCE_ARTIFACT_ID_DOMAIN to "artifact-1") to
                "03ff81ba37bbec1d88deed7ce8de10e41e6a8ed0cad45c161f518954c02289b0",
            (SOURCE_ARTIFACT_ID_DOMAIN to "x") to
                "1a3c831cf69f21c81cd2a40499b5fc48ae222d6b0dbbd42dc5bb6878237c1b70",
            (SOURCE_ARTIFACT_ID_DOMAIN to "x-archive-receipt.json") to
                "8a238f997e09f22d9c428a1f2b5398947ec18b8e98d7d80210cf9ebaf6f2e4ca",
            (ACCEPTANCE_ID_DOMAIN to "Case-A") to
                "a082884169025199c454bd3b2e7126d0bbfc31280275f4006676598a93c169d2",
            (SOURCE_COMMIT_DOMAIN to "Commit-A") to
                "f31924c4cb51f763c8f37d05aee035c501b023ade163dd6aea8954a7084a6d71",
            (SOURCE_ARTIFACT_ID_DOMAIN to "Artifact-A") to
                "274fbb8b2a910870815f1fcbdc5dce3e886dca399ba3a5c24c0626f61fed790a",
            (ACCEPTANCE_ID_DOMAIN to "case-a") to
                "1a9aefdf1b785e156588029b40ddaa8fd517e9a5ff84342d71626bea35bd0a03",
            (SOURCE_COMMIT_DOMAIN to "commit-a") to
                "c002eb7cb5f716a510180147dd80985ca08062ad2016e9c499521b25e9a1cb51",
            (SOURCE_ARTIFACT_ID_DOMAIN to "artifact-a") to
                "a6781ce898087828492b0ca55a28230afad51216d60200a2ed34d4afb7528f94",
            (ACCEPTANCE_ID_DOMAIN to "same-id") to
                "5f65cd55018cfbcf59f2ae11dcdca08a25b82adba6718c0b4c3d863151bbec0b",
            (SOURCE_COMMIT_DOMAIN to "same-id") to
                "d736427f6f558d98c398b2c2b0c878b1f2392aa40d22f3f2fb8ac977d9028b0b",
            (SOURCE_ARTIFACT_ID_DOMAIN to "same-id") to
                "425053646641b93cba1bf3fa6ec09949ea195fc85877222a40c1f833b79ed349",
        )
    }
}
