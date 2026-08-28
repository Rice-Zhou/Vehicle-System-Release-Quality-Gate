package com.ricezhou.vsrqg.shared.archive.operations

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.ExactObjectDownload
import com.ricezhou.vsrqg.shared.adapter.archive.ObjectProtectionSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3ControlSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3Gateway
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveArtifactReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExecutionReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExactObjectReference
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveRecoveryVerifier
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveVerificationFailure
import com.ricezhou.vsrqg.shared.adapter.archive.operations.OperationStatus
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryFileKeyReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryPartialCleanup
import com.ricezhou.vsrqg.shared.adapter.archive.operations.VerifiedArchiveSource
import com.ricezhou.vsrqg.shared.adapter.archive.operations.VerifiedEvidenceArchiveWorkPackage
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceipt
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.erdtman.jcs.JsonCanonicalizer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvidenceArchiveRecoveryVerifierTest {
    @TempDir
    lateinit var tempDirectory: Path

    private lateinit var fixture: Fixture

    @BeforeEach
    fun setUp() {
        fixture = Fixture()
    }

    @Test
    fun `same runtime identity fails before every download`() {
        fixture.gateway.identity = ARCHIVE_IDENTITY

        assertFailure("SAME_RUNTIME_IDENTITY:runtimeIdentity") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("same-identity"))
        }

        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `latest-only references are rejected before provider reads`() {
        listOf("", " ", "null", "NULL").forEachIndexed { index, versionId ->
            listOf("receiptReference", "payload").forEach { kind ->
                val invalid = fixture.report.copy(
                    artifacts = fixture.report.artifacts.mapIndexed { artifactIndex, artifact ->
                        if (artifactIndex != 0) artifact
                        else if (kind == "receiptReference") {
                            artifact.copy(receiptReference = artifact.receiptReference.copy(versionId = versionId))
                        } else {
                            artifact.copy(payload = artifact.payload.copy(versionId = versionId))
                        }
                    },
                )

                assertFailure("LATEST_REFERENCE_FORBIDDEN:artifacts[0].$kind.versionId") {
                    fixture.verifier().verify(fixture.workPackage, invalid, emptyRoot("latest-$index-$kind"))
                }
            }
        }

        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `response version shadow is rejected without payload fallback`() {
        fixture.gateway.responseVersions[fixture.report.artifacts[0].receiptReference.key] = "latest-shadow"

        assertFailure("VERSION_MISMATCH:artifacts[0].receiptReference.versionId") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("version-shadow"))
        }

        assertThat(fixture.gateway.events).containsExactly("download:receipt-1")

        fixture = Fixture()
        fixture.gateway.responseVersions[fixture.report.artifacts[0].payload.key] = "latest-shadow"
        assertFailure("VERSION_MISMATCH:artifacts[0].payload.versionId") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("payload-version-shadow"))
        }
        assertThat(fixture.gateway.events).containsExactly("download:receipt-1", "download:payload-1")
    }

    @Test
    fun `receipt and payload size and digest mismatches use stable codes`() {
        val cases = listOf(
            FailureCase(
                "receipt-size",
                "SIZE_MISMATCH:artifacts[0].receiptReference.sizeBytes",
                { fixture.gateway.bodies["receipt-1"] = fixture.gateway.bodies.getValue("receipt-1") + 0 },
                listOf("download:receipt-1"),
            ),
            FailureCase(
                "receipt-digest",
                "DIGEST_MISMATCH:artifacts[0].receiptReference.sha256",
                {
                    val original = fixture.gateway.bodies.getValue("receipt-1")
                    fixture.gateway.bodies["receipt-1"] = original.copyOf().also { it[it.lastIndex] = (it.last() xor 1) }
                },
                listOf("download:receipt-1"),
            ),
            FailureCase(
                "payload-size",
                "SIZE_MISMATCH:artifacts[0].payload.sizeBytes",
                { fixture.gateway.bodies["payload-1"] = fixture.gateway.bodies.getValue("payload-1") + 0 },
                listOf("download:receipt-1", "download:payload-1"),
            ),
            FailureCase(
                "payload-digest",
                "DIGEST_MISMATCH:artifacts[0].payload.sha256",
                {
                    val original = fixture.gateway.bodies.getValue("payload-1")
                    fixture.gateway.bodies["payload-1"] = original.copyOf().also { it[0] = (it[0] xor 1) }
                },
                listOf("download:receipt-1", "download:payload-1"),
            ),
        )

        cases.forEach { case ->
            fixture = Fixture()
            case.mutate()
            assertFailure(case.code) {
                fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot(case.name))
            }
            assertThat(fixture.gateway.events).containsExactlyElementsOf(case.events)
        }
    }

    @Test
    fun `receipt must bind acceptance source payload and report controls`() {
        val first = fixture.receipts.getValue("receipt-1")
        val mismatches = listOf(
            first.copy(acceptanceId = "other") to "acceptanceId",
            first.copy(sourceArtifactId = "other") to "sourceArtifactId",
            first.copy(sourceRunId = "other") to "sourceRunId",
            first.copy(sourceCommit = "0".repeat(40)) to "sourceCommit",
            first.copy(sourceSha256 = "0".repeat(64)) to "sourceSha256",
            first.copy(payload = first.payload.copy(versionId = "other-version")) to "payload",
            first.copy(accessOwner = "other-owner") to "accessOwner",
            first.copy(retentionPolicy = "P1D") to "retentionPolicy",
            first.copy(immutabilityControl = "GOVERNANCE") to "immutabilityControl",
            first.copy(policyFingerprint = "0".repeat(64)) to "policyFingerprint",
            first.copy(longTerm = false) to "longTerm",
        )

        mismatches.forEachIndexed { index, (receipt, field) ->
            fixture = Fixture()
            fixture.replaceReceipt("receipt-1", receipt)
            assertFailure("RECEIPT_MISMATCH:artifacts[0].receipt.$field") {
                fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("receipt-$index"))
            }
            assertThat(fixture.gateway.events).containsExactly("download:receipt-1")
        }
    }

    @Test
    fun `archive capability timestamp must equal the latest receipt control timestamp`() {
        val shadowed = fixture.report.copy(capabilityCheckedAt = CAPABILITY_CHECKED_AT.plusSeconds(30))

        assertFailure("RECEIPT_MISMATCH:archiveReport.capabilityCheckedAt") {
            fixture.verifier().verify(fixture.workPackage, shadowed, emptyRoot("capability-shadow"))
        }
    }

    @Test
    fun `archive report must be PASS complete and identical to the work package`() {
        val invalidReports = listOf(
            fixture.report.copy(status = OperationStatus.FAIL, errorCode = "ARCHIVE_UNAVAILABLE") to "status",
            fixture.report.copy(descriptorSha256 = "0".repeat(64)) to "descriptorSha256",
            fixture.report.copy(pilotManifestSha256 = "0".repeat(64)) to "pilotManifestSha256",
            fixture.report.copy(artifacts = fixture.report.artifacts.dropLast(1)) to "artifacts",
            fixture.report.copy(accessOwner = null) to "accessOwner",
            fixture.report.copy(runtimeIdentity = null) to "runtimeIdentity",
        )

        invalidReports.forEachIndexed { index, (report, field) ->
            assertFailure("RECEIPT_MISMATCH:archiveReport.$field") {
                fixture.verifier().verify(fixture.workPackage, report, emptyRoot("report-$index"))
            }
        }
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `verified work package control fields are revalidated before provider reads`() {
        val invalidWorkPackage = fixture.workPackage.copy(descriptorSha256 = "not-a-digest")
        val matchingInvalidReport = fixture.report.copy(descriptorSha256 = "not-a-digest")

        assertFailure("RECEIPT_MISMATCH:workPackage.descriptorSha256") {
            fixture.verifier().verify(invalidWorkPackage, matchingInvalidReport, emptyRoot("invalid-work-package"))
        }

        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `protection mode and retain until must satisfy receipt policy for both objects`() {
        fixture.gateway.protections["receipt-1"] = ObjectProtectionSnapshot("GOVERNANCE", RETAIN_UNTIL)
        assertFailure("PROTECTION_INSUFFICIENT:artifacts[0].receiptReference.protection") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("bad-mode"))
        }
        assertThat(fixture.gateway.events).containsExactly(
            "download:receipt-1", "download:payload-1", "head:receipt-1",
        )

        fixture = Fixture()
        fixture.gateway.protections["payload-1"] = ObjectProtectionSnapshot("COMPLIANCE", ARCHIVED_AT.plus(Duration.ofDays(729)))
        assertFailure("PROTECTION_INSUFFICIENT:artifacts[0].payload.protection") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("short-retention"))
        }
        assertThat(fixture.gateway.events).containsExactly(
            "download:receipt-1", "download:payload-1", "head:receipt-1", "head:payload-1",
        )
    }

    @Test
    fun `recovery root rejects relative nonempty symbolic and unavailable identities`() {
        assertFailure("RECOVERY_ROOT_INVALID:recoveryRoot") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, Path.of("relative"))
        }

        val nonempty = emptyRoot("nonempty")
        Files.writeString(nonempty.resolve("existing.txt"), "occupied")
        assertFailure("RECOVERY_ROOT_INVALID:recoveryRoot") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, nonempty)
        }

        val target = emptyRoot("target")
        val link = tempDirectory.resolve("root-link")
        createSymbolicLinkOrJunction(link, target)
        assertFailure("RECOVERY_ROOT_INVALID:recoveryRoot") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, link)
        }

        val noKey = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            RecoveryFileKeyReader { _, _ -> null },
        )
        assertFailure("RECOVERY_ROOT_INVALID:recoveryRoot") {
            noKey.verify(fixture.workPackage, fixture.report, emptyRoot("no-file-key"))
        }
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `partial cleanup failure fails the report and does not claim success`() {
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
            partialCleanup = RecoveryPartialCleanup { throw java.io.IOException("C:\\secret\\partial") },
        )
        val root = emptyRoot("cleanup-failure")

        val report = verifier.verifyReport(fixture.workPackage, fixture.report, root)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("RECOVERY_CLEANUP_FAILED")
        assertThat(report.artifacts).hasSize(2)
        assertThat(report.toString()).doesNotContain("secret", root.toString())
    }

    @Test
    fun `second artifact failure report preserves the first verified evidence`() {
        fixture.gateway.responseVersions["receipt-2"] = "shadow"

        val result = fixture.verifier().verifyReport(fixture.workPackage, fixture.report, emptyRoot("partial-result"))

        assertThat(result.status).isEqualTo(OperationStatus.FAIL)
        assertThat(result.errorCode).isEqualTo("VERSION_MISMATCH")
        assertThat(result.artifacts.map { it.artifactId }).containsExactly("1001")
        assertThat(fixture.gateway.events).containsExactly(
            "download:receipt-1", "download:payload-1", "head:receipt-1", "head:payload-1", "download:receipt-2",
        )
    }

    @Test
    fun `unexpected provider exception becomes a stable secret-free failure without catching Error`() {
        fixture.gateway.failures["receipt-1"] = IllegalStateException("https://secret.example?credential=raw")

        val result = fixture.verifier().verifyReport(fixture.workPackage, fixture.report, emptyRoot("unexpected"))

        assertThat(result.status).isEqualTo(OperationStatus.FAIL)
        assertThat(result.errorCode).isEqualTo("UNEXPECTED_FAILURE")
        assertThat(result.toString()).doesNotContain("secret.example", "credential=raw")
    }

    @Test
    fun `success uses different identity receipt first exact versions protection and leaves root empty`() {
        val root = emptyRoot("success")

        val result = fixture.verifier().verify(fixture.workPackage, fixture.report, root)

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
        assertThat(result.errorCode).isNull()
        assertThat(result.executionId).isEqualTo(fixture.report.executionId)
        assertThat(result.archiveIdentity).isEqualTo(ARCHIVE_IDENTITY)
        assertThat(result.verifierIdentity).isEqualTo(VERIFIER_IDENTITY).isNotEqualTo(result.archiveIdentity)
        assertThat(result.artifacts).hasSize(2)
        assertThat(fixture.gateway.events).containsExactly(
            "download:receipt-1", "download:payload-1", "head:receipt-1", "head:payload-1",
            "download:receipt-2", "download:payload-2", "head:receipt-2", "head:payload-2",
        )
        Files.list(root).use { assertThat(it.toList()).isEmpty() }
    }

    @Test
    fun `strict bytes inputs and canonical create-only recovery report support the operation`() {
        val verifier = fixture.verifier()
        val descriptorBytes = fixture.descriptorBytes()
        val expectedArchiveReport = fixture.report.copy(descriptorSha256 = sha256(descriptorBytes))
        val archiveReportBytes = fixture.archiveReportBytes(expectedArchiveReport)

        val parsedWorkPackage = verifier.parseWorkPackage(descriptorBytes)
        val parsedArchiveReport = verifier.parseArchiveReport(archiveReportBytes)
        val recoveryRoot = emptyRoot("parsed-success")
        val recoveryReport = verifier.verify(parsedWorkPackage, parsedArchiveReport, recoveryRoot)
        val output = tempDirectory.resolve("reports").also(Files::createDirectory).resolve("recovery.json")
        verifier.validateReportOutput(output)
        verifier.writeReport(recoveryReport, output)

        assertThat(parsedWorkPackage.descriptorSha256).isEqualTo(sha256(descriptorBytes))
        assertThat(parsedWorkPackage.artifacts.map { it.path.fileName.toString() })
            .containsExactly("artifact-1.zip", "artifact-2.zip")
        assertThat(parsedArchiveReport).isEqualTo(expectedArchiveReport)
        val outputBytes = Files.readAllBytes(output)
        assertThat(JsonCanonicalizer(outputBytes).encodedUTF8).isEqualTo(outputBytes)
        assertThat(String(outputBytes)).doesNotContain(tempDirectory.toString(), "credential", "https://", "arn:")
        assertFailure("UNEXPECTED_FAILURE:output") { verifier.writeReport(recoveryReport, output) }
    }

    @Test
    fun `recovery report output is rejected inside the recovery root`() {
        val root = emptyRoot("output-boundary")

        assertFailure("UNEXPECTED_FAILURE:output") {
            fixture.verifier().validateReportOutput(root.resolve("recovery-report.json"), root)
        }
    }

    private fun emptyRoot(name: String): Path = Files.createDirectory(tempDirectory.resolve(name))

    private fun createSymbolicLinkOrJunction(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: java.nio.file.FileSystemException) {
            if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) throw failure
            val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start()
            process.inputStream.use { it.readAllBytes() }
            check(process.waitFor() == 0) { "junction creation failed" }
        }
    }

    private fun assertFailure(code: String, action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOf(EvidenceArchiveVerificationFailure::class.java)
            .hasMessage(code)
            .extracting("code")
            .isEqualTo(code)
    }

    private data class FailureCase(
        val name: String,
        val code: String,
        val mutate: () -> Unit,
        val events: List<String>,
    )

    private class Fixture {
        val gateway = RecordingGateway()
        val receipts = linkedMapOf<String, ArchiveReceipt>()
        val workPackage: VerifiedEvidenceArchiveWorkPackage
        var report: EvidenceArchiveExecutionReport

        init {
            val sources = (1..2).map { index ->
                val bytes = "payload-$index".toByteArray()
                gateway.bodies["payload-$index"] = bytes
                VerifiedArchiveSource(
                    artifactId = "100$index",
                    sourceRunId = "200$index",
                    sourceCommit = index.toString().repeat(40),
                    path = Path.of("artifact-$index.zip"),
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                )
            }
            workPackage = VerifiedEvidenceArchiveWorkPackage(
                WORK_PACKAGE_ID,
                "a".repeat(64),
                "b".repeat(64),
                sources,
            )
            val artifactReports = sources.mapIndexed { index, source ->
                val number = index + 1
                val payload = exactRef("payload-$number", source.sha256, source.sizeBytes)
                val receipt = ArchiveReceipt(
                    acceptanceId = WORK_PACKAGE_ID,
                    sourceArtifactId = source.artifactId,
                    sourceRunId = source.sourceRunId,
                    sourceCommit = source.sourceCommit,
                    sourceSha256 = source.sha256,
                    payload = payload.toStored(),
                    accessOwner = "release-owner",
                    retentionPolicy = "P730D",
                    immutabilityControl = "COMPLIANCE",
                    policyFingerprint = "c".repeat(64),
                    capabilityCheckedAt = CAPABILITY_CHECKED_AT,
                    archivedAt = ARCHIVED_AT,
                    verifier = "SHA-256",
                    longTerm = true,
                )
                receipts["receipt-$number"] = receipt
                val receiptBytes = canonicalReceipt(receipt)
                gateway.bodies["receipt-$number"] = receiptBytes
                EvidenceArchiveArtifactReport(
                    source.artifactId,
                    source.sourceRunId,
                    source.sourceCommit,
                    payload,
                    exactRef("receipt-$number", sha256(receiptBytes), receiptBytes.size.toLong()),
                )
            }
            report = EvidenceArchiveExecutionReport(
                schemaVersion = 1,
                workPackageId = WORK_PACKAGE_ID,
                executionId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
                descriptorSha256 = workPackage.descriptorSha256,
                pilotManifestSha256 = workPackage.pilotManifestSha256,
                startedAt = Instant.parse("2026-01-01T00:00:00Z"),
                completedAt = Instant.parse("2026-01-01T00:01:00Z"),
                policyFingerprint = "c".repeat(64),
                capabilityCheckedAt = CAPABILITY_CHECKED_AT,
                runtimeIdentity = ARCHIVE_IDENTITY,
                artifacts = artifactReports,
                accessOwner = "release-owner",
                retentionPolicy = "P730D",
                immutabilityControl = "COMPLIANCE",
                status = OperationStatus.PASS,
                errorCode = null,
            )
            artifactReports.forEach {
                gateway.protections[it.payload.key] = ObjectProtectionSnapshot("COMPLIANCE", RETAIN_UNTIL)
                gateway.protections[it.receiptReference.key] = ObjectProtectionSnapshot("COMPLIANCE", RETAIN_UNTIL)
            }
        }

        fun verifier(): EvidenceArchiveRecoveryVerifier = EvidenceArchiveRecoveryVerifier(
            gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
        )

        fun replaceReceipt(key: String, receipt: ArchiveReceipt) {
            receipts[key] = receipt
            val bytes = canonicalReceipt(receipt)
            gateway.bodies[key] = bytes
            val index = key.substringAfterLast('-').toInt() - 1
            report = report.copy(
                artifacts = report.artifacts.mapIndexed { artifactIndex, artifact ->
                    if (artifactIndex == index) {
                        artifact.copy(receiptReference = artifact.receiptReference.copy(sha256 = sha256(bytes), sizeBytes = bytes.size.toLong()))
                    } else artifact
                },
            )
        }

        fun descriptorBytes(): ByteArray {
            val mapper = jacksonObjectMapper()
            return mapper.writeValueAsBytes(
                mapper.createObjectNode().apply {
                    put("schemaVersion", 1)
                    put("workPackageId", WORK_PACKAGE_ID)
                    put("subjectCommit", "f".repeat(40))
                    put("pairedSubjectCommit", "0".repeat(40))
                    putObject("pilotManifest").apply {
                        put("fileName", "pilot-preservation-manifest.json")
                        put("sha256", workPackage.pilotManifestSha256)
                        put("classification", "LOCAL_PILOT_NOT_IMMUTABLE")
                        put("conditionBClosed", false)
                    }
                    putArray("artifacts").apply {
                        workPackage.artifacts.forEachIndexed { index, source ->
                            addObject().apply {
                                put("artifactId", source.artifactId)
                                put("artifactName", "artifact-${index + 1}")
                                put("fileName", "artifact-${index + 1}.zip")
                                put("sourceRunId", source.sourceRunId)
                                put("sourceCommit", source.sourceCommit)
                                put("sizeBytes", source.sizeBytes)
                                put("sha256", source.sha256)
                            }
                        }
                    }
                },
            )
        }

        fun archiveReportBytes(report: EvidenceArchiveExecutionReport = this.report): ByteArray {
            val mapper = jacksonObjectMapper()
            val root = mapper.createObjectNode().apply {
                put("schemaVersion", report.schemaVersion)
                put("workPackageId", report.workPackageId)
                put("executionId", report.executionId)
                put("descriptorSha256", report.descriptorSha256)
                put("pilotManifestSha256", report.pilotManifestSha256)
                put("startedAt", report.startedAt.toString())
                put("completedAt", report.completedAt.toString())
                put("policyFingerprint", report.policyFingerprint)
                put("capabilityCheckedAt", report.capabilityCheckedAt.toString())
                putObject("runtimeIdentity").apply {
                    put("provider", report.runtimeIdentity?.provider?.name)
                    put("principalFingerprint", report.runtimeIdentity?.principalFingerprint)
                }
                putArray("artifacts").apply {
                    report.artifacts.forEach { artifact ->
                        addObject().apply {
                            put("artifactId", artifact.artifactId)
                            put("sourceRunId", artifact.sourceRunId)
                            put("sourceCommit", artifact.sourceCommit)
                            set<com.fasterxml.jackson.databind.node.ObjectNode>("payload", exactReferenceNode(artifact.payload))
                            set<com.fasterxml.jackson.databind.node.ObjectNode>(
                                "receiptReference",
                                exactReferenceNode(artifact.receiptReference),
                            )
                        }
                    }
                }
                put("accessOwner", report.accessOwner)
                put("retentionPolicy", report.retentionPolicy)
                put("immutabilityControl", report.immutabilityControl)
                put("status", report.status.name)
                putNull("errorCode")
            }
            return JsonCanonicalizer(mapper.writeValueAsBytes(root)).encodedUTF8
        }

        private fun exactReferenceNode(reference: EvidenceArchiveExactObjectReference) =
            jacksonObjectMapper().createObjectNode().apply {
                put("provider", reference.provider.name)
                put("locator", reference.locator)
                put("bucket", reference.bucket)
                put("key", reference.key)
                put("versionId", reference.versionId)
                put("sha256", reference.sha256)
                put("sizeBytes", reference.sizeBytes)
            }
    }

    private class RecordingGateway : S3Gateway {
        var identity: RuntimeIdentityRef = VERIFIER_IDENTITY
        val events = mutableListOf<String>()
        val bodies = linkedMapOf<String, ByteArray>()
        val responseVersions = mutableMapOf<String, String>()
        val protections = mutableMapOf<String, ObjectProtectionSnapshot>()
        val failures = mutableMapOf<String, RuntimeException>()

        override fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef = identity

        override fun downloadExact(source: StoredObjectRef, maxBytes: Long, timeout: Duration): ExactObjectDownload {
            events += "download:${source.key}"
            failures[source.key]?.let { throw it }
            val bytes = bodies.getValue(source.key)
            return ExactObjectDownload(
                bytes,
                responseVersions[source.key] ?: source.versionId,
                "etag-${source.key}",
                bytes.size.toLong(),
                mapOf("sha256" to sha256(bytes)),
            )
        }

        override fun headProtection(source: StoredObjectRef, timeout: Duration): ObjectProtectionSnapshot {
            events += "head:${source.key}"
            return protections.getValue(source.key)
        }

        override fun controls(
            bucket: String,
            targetKey: String,
            resultKey: String,
            policyFingerprint: String,
            identity: RuntimeIdentityRef,
            utcDate: LocalDate,
            requiredRetainUntil: Instant,
            validUntil: Instant,
            timeout: Duration,
        ): S3ControlSnapshot = error("not used")

        override fun putFileIfAbsent(bucket: String, key: String, source: Path, sha256: String, timeout: Duration): StoredObjectRef =
            error("not used")

        override fun download(source: StoredObjectRef, target: Path, timeout: Duration) = error("not used")

        override fun putJsonIfAbsent(bucket: String, key: String, bytes: ByteArray, sha256: String, timeout: Duration): StoredObjectRef =
            error("not used")
    }

    private companion object {
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        val OPERATION_TIMEOUT: Duration = Duration.ofSeconds(5)
        val CAPABILITY_CHECKED_AT: Instant = Instant.parse("2025-12-31T23:59:00Z")
        val ARCHIVED_AT: Instant = Instant.parse("2026-01-01T00:00:10Z")
        val RETAIN_UNTIL: Instant = Instant.parse("2028-01-02T00:00:10Z")
        val NOW: Instant = Instant.parse("2026-01-02T00:00:00Z")
        val ARCHIVE_IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "d".repeat(64))
        val VERIFIER_IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "e".repeat(64))
        val TEST_FILE_KEY_READER = RecoveryFileKeyReader { path, attributes ->
            attributes.fileKey() ?: path.toAbsolutePath().normalize().toString()
        }

        fun exactRef(key: String, digest: String, size: Long) = EvidenceArchiveExactObjectReference(
            ArchiveProvider.S3_COMPATIBLE,
            "s3://archive-bucket/$key",
            "archive-bucket",
            key,
            "version-$key",
            digest,
            size,
        )

        fun EvidenceArchiveExactObjectReference.toStored() = StoredObjectRef(
            provider,
            locator,
            bucket,
            key,
            versionId,
            sha256,
            sizeBytes,
        )

        fun canonicalReceipt(receipt: ArchiveReceipt): ByteArray {
            val mapper = jacksonObjectMapper()
            val root = mapper.createObjectNode().apply {
                put("acceptanceId", receipt.acceptanceId)
                put("sourceArtifactId", receipt.sourceArtifactId)
                put("sourceRunId", receipt.sourceRunId)
                put("sourceCommit", receipt.sourceCommit)
                put("sourceSha256", receipt.sourceSha256)
                putObject("payload").apply {
                    put("provider", receipt.payload.provider.name)
                    put("locator", receipt.payload.locator)
                    put("bucket", receipt.payload.bucket)
                    put("key", receipt.payload.key)
                    put("versionId", receipt.payload.versionId)
                    put("sha256", receipt.payload.sha256)
                    put("sizeBytes", receipt.payload.sizeBytes)
                }
                put("accessOwner", receipt.accessOwner)
                put("retentionPolicy", receipt.retentionPolicy)
                put("immutabilityControl", receipt.immutabilityControl)
                put("policyFingerprint", receipt.policyFingerprint)
                put("capabilityCheckedAt", receipt.capabilityCheckedAt.toString())
                put("archivedAt", receipt.archivedAt.toString())
                put("verifier", receipt.verifier)
                put("longTerm", receipt.longTerm)
            }
            return JsonCanonicalizer(mapper.writeValueAsBytes(root)).encodedUTF8
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        infix fun Byte.xor(value: Int): Byte = (toInt() xor value).toByte()
    }
}
