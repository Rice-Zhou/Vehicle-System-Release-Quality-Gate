package com.ricezhou.vsrqg.shared.archive.operations

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.ExactObjectDownload
import com.ricezhou.vsrqg.shared.adapter.archive.ObjectProtectionSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3ControlSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3Gateway
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveArtifactReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveDirectoryAccessControl
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveDirectoryAccessReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExecutionReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExactObjectReference
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveRecoveryVerifier
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableFileAccess
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableFileReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveTrustedDirectory
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveVerificationFailure
import com.ricezhou.vsrqg.shared.adapter.archive.operations.OperationStatus
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryFileKeyReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryPartialCleanup
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryRealPathResolver
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryReportPublishOperations
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryRootExpectation
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryRootGuard
import com.ricezhou.vsrqg.shared.adapter.archive.operations.VerifiedArchiveSource
import com.ricezhou.vsrqg.shared.adapter.archive.operations.VerifiedEvidenceArchiveWorkPackage
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceipt
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.nio.charset.StandardCharsets
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
        prepareControlledTestDirectory(tempDirectory)
        fixture = Fixture()
    }

    @Test
    fun `completion marker is removed when its own access proof cannot be established`() {
        val marker = tempDirectory.resolve("recovery-report.json.complete.${"0".repeat(64)}")
        val stableReader = EvidenceArchiveStableFileReader(
            EvidenceArchiveStableFileAccess.nio().copy(
                accessProof = { throw java.io.IOException("file ACL unavailable") },
            ),
        )

        assertThatThrownBy { RecoveryReportPublishOperations.nio(stableReader).createCompletionMarker(marker) }
            .isInstanceOf(java.io.IOException::class.java)
        assertThat(Files.exists(marker)).isFalse()
    }

    private fun updateFirstReceiptReference(key: String, locator: String? = null) {
        val original = fixture.report.artifacts[0].receiptReference
        val updated = original.copy(locator = locator ?: "s3://${original.bucket}/$key", key = key)
        fixture.gateway.bodies[key] = fixture.gateway.bodies.remove(original.key)!!
        fixture.gateway.protections[key] = fixture.gateway.protections.remove(original.key)!!
        fixture.report = fixture.report.copy(
            artifacts = fixture.report.artifacts.mapIndexed { index, artifact ->
                if (index == 0) artifact.copy(receiptReference = updated) else artifact
            },
        )
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
    fun `blank and literal null references are rejected before provider reads`() {
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

                assertFailure("INVALID_EXACT_REFERENCE:artifacts[0].$kind.versionId") {
                    fixture.verifier().verify(fixture.workPackage, invalid, emptyRoot("latest-$index-$kind"))
                }
            }
        }

        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `opaque version identifiers may contain ordinary security vocabulary`() {
        val first = fixture.report.artifacts[0]
        val payload = first.payload.copy(versionId = "opaque-token-version-7")
        fixture.replaceReceipt("receipt-1", fixture.receipts.getValue("receipt-1").copy(payload = payload.toStored()))
        fixture.report = fixture.report.copy(
            artifacts = fixture.report.artifacts.mapIndexed { index, artifact ->
                when (index) {
                    0 -> artifact.copy(payload = payload)
                    1 -> artifact.copy(receiptReference = artifact.receiptReference.copy(versionId = "principal-version-2"))
                    else -> artifact
                }
            },
        )

        val result = fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("opaque-version"))

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
    }

    @Test
    fun `latest and long provider version identifiers remain exact`() {
        listOf("latest", "LATEST", "😀".repeat(513), "v".repeat(4096)).forEachIndexed { index, versionId ->
            fixture = Fixture()
            val payload = fixture.report.artifacts[0].payload.copy(versionId = versionId)
            fixture.replaceReceipt("receipt-1", fixture.receipts.getValue("receipt-1").copy(payload = payload.toStored()))
            fixture.report = fixture.report.copy(
                artifacts = fixture.report.artifacts.mapIndexed { artifactIndex, artifact ->
                    if (artifactIndex == 0) artifact.copy(payload = payload) else artifact
                },
            )

            assertThat(fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("exact-version-$index")).status)
                .isEqualTo(OperationStatus.PASS)
        }
    }

    @Test
    fun `relative object key may contain ordinary security vocabulary`() {
        val original = fixture.report.artifacts[0].receiptReference
        val key = "evidence/release-token-principal/secret-object.json"
        val updated = original.copy(locator = "s3://archive-bucket/$key", key = key)
        fixture.gateway.bodies[key] = fixture.gateway.bodies.remove(original.key)!!
        fixture.gateway.protections[key] = fixture.gateway.protections.remove(original.key)!!
        fixture.report = fixture.report.copy(
            artifacts = fixture.report.artifacts.mapIndexed { index, artifact ->
                if (index == 0) artifact.copy(receiptReference = updated) else artifact
            },
        )

        val result = fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("business-key"))

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
    }

    @Test
    fun `storage key path-like business text is not treated as a local filesystem disclosure`() {
        val key = "evidence/path=/var/tmp/release-token-principal/secret-object.json"
        updateFirstReceiptReference(key)

        assertThat(fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("path-like-key")).status)
            .isEqualTo(OperationStatus.PASS)
    }

    @Test
    fun `exact reference accepts provider-compatible bucket and maximum UTF-8 key`() {
        val original = fixture.report.artifacts[0].receiptReference
        val bucket = "Tenant_Bucket[Prod] ${"X".repeat(80)}"
        val key = "k".repeat(1024)
        val locator = "s3://$bucket/$key"
        assertThat(locator.length).isGreaterThan(1093)
        val updated = original.copy(locator = locator, bucket = bucket, key = key)
        fixture.gateway.bodies[key] = fixture.gateway.bodies.remove(original.key)!!
        fixture.gateway.protections[key] = fixture.gateway.protections.remove(original.key)!!
        fixture.report = fixture.report.copy(
            artifacts = fixture.report.artifacts.mapIndexed { index, artifact ->
                if (index == 0) artifact.copy(receiptReference = updated) else artifact
            },
        )

        val result = fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("maximum-reference"))

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
    }

    @Test
    fun `version identifiers use Kotlin blank semantics without a length limit`() {
        listOf(" ", "\t", "\u00a0", "\u3000").forEachIndexed { index, versionId ->
            fixture = Fixture()
            fixture.report = fixture.report.copy(
                artifacts = fixture.report.artifacts.mapIndexed { artifactIndex, artifact ->
                    if (artifactIndex == 0) artifact.copy(payload = artifact.payload.copy(versionId = versionId)) else artifact
                },
            )
            assertFailure("INVALID_EXACT_REFERENCE:artifacts[0].payload.versionId") {
                fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("blank-version-$index"))
            }
        }

        listOf("\u200b", "\ufeff", "😀".repeat(512), "😀".repeat(513)).forEachIndexed { index, versionId ->
            fixture = Fixture()
            val payload = fixture.report.artifacts[0].payload.copy(versionId = versionId)
            fixture.replaceReceipt("receipt-1", fixture.receipts.getValue("receipt-1").copy(payload = payload.toStored()))
            fixture.report = fixture.report.copy(
                artifacts = fixture.report.artifacts.mapIndexed { artifactIndex, artifact ->
                    if (artifactIndex == 0) artifact.copy(payload = payload) else artifact
                },
            )
            assertThat(fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("valid-version-$index")).status)
                .isEqualTo(OperationStatus.PASS)
        }

    }

    @Test
    fun `S3 locator is exact raw bucket and key text`() {
        listOf("evidence/café.json", "evidence/😀.json", "evidence/raw %.json", "evidence/raw # ? [x].json")
            .forEachIndexed { index, key ->
                fixture = Fixture()
                updateFirstReceiptReference(key)
                assertThat(fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("valid-uri-$index")).status)
                    .isEqualTo(OperationStatus.PASS)
            }

        listOf(
            "evidence/a/../b",
            "evidence/a/./b",
            "evidence/a//b",
            "/evidence/a",
        )
            .forEachIndexed { index, key ->
                fixture = Fixture()
                updateFirstReceiptReference(key)
                assertFailure("RECEIPT_MISMATCH:archiveReport.artifacts[0].receiptReference") {
                    fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("invalid-uri-$index"))
                }
            }

        fixture = Fixture()
        updateFirstReceiptReference("evidence/café.json", "s3://archive-bucket/evidence/caf%C3%A9.json")
        assertFailure("RECEIPT_MISMATCH:archiveReport.artifacts[0].receiptReference") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("percent-ambiguity"))
        }
    }

    @Test
    fun `object key limit uses UTF-8 bytes`() {
        listOf("é".repeat(512), "😀".repeat(256)).forEachIndexed { index, key ->
            fixture = Fixture()
            assertThat(key.toByteArray(StandardCharsets.UTF_8)).hasSize(1024)
            updateFirstReceiptReference(key)
            assertThat(fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("key-1024-$index")).status)
                .isEqualTo(OperationStatus.PASS)
        }

        listOf(" ", "\u00a0", "\u3000").forEachIndexed { index, key ->
            fixture = Fixture()
            updateFirstReceiptReference(key)
            assertFailure("RECEIPT_MISMATCH:archiveReport.artifacts[0].receiptReference") {
                fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("blank-key-$index"))
            }
        }

        listOf("${"é".repeat(512)}a", "${"😀".repeat(256)}a").forEachIndexed { index, key ->
            fixture = Fixture()
            assertThat(key.toByteArray(StandardCharsets.UTF_8)).hasSize(1025)
            updateFirstReceiptReference(key)
            assertFailure("RECEIPT_MISMATCH:archiveReport.artifacts[0].receiptReference") {
                fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("key-1025-$index"))
            }
        }
    }

    @Test
    fun `version identifiers reject embedded local path and credential shapes`() {
        listOf(
            "path=/var/tmp/evidence.json",
            "path=C:\\private\\evidence.json",
            "https://archive.invalid/object",
            "arn:aws:iam::123456789012:role/archive",
            "arn:aws:sts::123456789012:assumed-role/archive/session",
            "principal=archive-role",
            "account:123456789012",
            "subject=archive-subject",
            "session_name=archive-session",
            "session-name:archive-session",
            "user_id=archive-user",
            "user-id:archive-user",
            "iam_role=archive-role",
            "iam-role:archive-role",
            "role_session=archive-session",
            "role-session:archive-session",
            "Authorization: Bearer opaque-value",
            "Bearer opaque-value",
            "credential=archive-value",
            "secret:archive-value",
            "password=archive-value",
            "private_key=archive-value",
            "AKIA${"A".repeat(16)}",
            "ASIA${"B".repeat(16)}",
            "ghp_${"c".repeat(36)}",
            "-----BEGIN PRIVATE KEY-----",
            "opaque-\u0085-version",
        ).forEachIndexed { index, versionId ->
            fixture = Fixture()
            fixture.report = fixture.report.copy(
                artifacts = fixture.report.artifacts.mapIndexed { artifactIndex, artifact ->
                    if (artifactIndex == 0) artifact.copy(payload = artifact.payload.copy(versionId = versionId)) else artifact
                },
            )

            assertFailure("RECEIPT_MISMATCH:archiveReport.artifacts[0].payload") {
                fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("unsafe-version-$index"))
            }
            assertThat(fixture.gateway.events).isEmpty()
        }
    }

    @Test
    fun `raw literal-null archive reference publishes the precise safe failure`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val mapper = jacksonObjectMapper()
        val root = mapper.readTree(fixture.archiveReportBytes(matching)) as com.fasterxml.jackson.databind.node.ObjectNode
        (root.withArray("artifacts")[0] as com.fasterxml.jackson.databind.node.ObjectNode)
            .withObject("receiptReference")
            .put("versionId", "null")
        val output = reportOutput("raw-latest")

        val result = fixture.verifier().recover(
            descriptor,
            JsonCanonicalizer(mapper.writeValueAsBytes(root)).encodedUTF8,
            emptyRoot("raw-latest-root"),
            output,
        )

        assertThat(result.errorCode).isEqualTo("INVALID_EXACT_REFERENCE")
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
    fun `receipt capability check must occur inside the report to archive interval`() {
        val invalidChecks = listOf(
            fixture.report.startedAt.minusNanos(1) to "before-report",
            ARCHIVED_AT.plusNanos(1) to "after-archive",
        )

        invalidChecks.forEach { (checkedAt, name) ->
            fixture = Fixture()
            fixture.replaceReceipt("receipt-1", fixture.receipts.getValue("receipt-1").copy(capabilityCheckedAt = checkedAt))
            fixture.report = fixture.report.copy(capabilityCheckedAt = maxOf(checkedAt, CAPABILITY_CHECKED_AT))

            assertFailure("RECEIPT_MISMATCH:artifacts[0].receipt.capabilityCheckedAt") {
                fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("capability-$name"))
            }
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
    fun `retention must still be effective after recovery starts`() {
        val operationStartedAt = Instant.parse("2026-01-03T00:00:10Z")
        val receipt = fixture.receipts.getValue("receipt-1").copy(retentionPolicy = "P1D")
        fixture.replaceReceipt("receipt-1", receipt)
        fixture.report = fixture.report.copy(retentionPolicy = "P1D")
        fixture.gateway.protections["receipt-1"] = ObjectProtectionSnapshot(
            "COMPLIANCE",
            receipt.archivedAt.plus(Duration.ofDays(1)),
        )
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { operationStartedAt },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
        )

        assertFailure("PROTECTION_INSUFFICIENT:artifacts[0].receiptReference.protection") {
            verifier.verify(fixture.workPackage, fixture.report, emptyRoot("expired-retention"))
        }

        fixture = Fixture()
        val equalStartReceipt = fixture.receipts.getValue("receipt-1").copy(retentionPolicy = "P1D")
        fixture.replaceReceipt("receipt-1", equalStartReceipt)
        fixture.report = fixture.report.copy(retentionPolicy = "P1D")
        val equalStart = equalStartReceipt.archivedAt.plus(Duration.ofDays(1))
        fixture.gateway.protections["receipt-1"] = ObjectProtectionSnapshot("COMPLIANCE", equalStart)
        val equalVerifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { equalStart },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
        )
        assertFailure("PROTECTION_INSUFFICIENT:artifacts[0].receiptReference.protection") {
            equalVerifier.verify(fixture.workPackage, fixture.report, emptyRoot("equal-start-retention"))
        }

        fixture = Fixture()
        val success = fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("future-retention"))
        assertThat(success.status).isEqualTo(OperationStatus.PASS)
    }

    @Test
    fun `retention date overflow is a stable protection failure`() {
        val archivedAt = Instant.MAX.minusSeconds(1)
        val checkedAt = archivedAt.minusSeconds(1)
        fixture.receipts.keys.toList().forEach { key ->
            fixture.replaceReceipt(
                key,
                fixture.receipts.getValue(key).copy(capabilityCheckedAt = checkedAt, archivedAt = archivedAt),
            )
        }
        fixture.report = fixture.report.copy(
            startedAt = checkedAt,
            completedAt = archivedAt,
            capabilityCheckedAt = checkedAt,
        )
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { Instant.MAX },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
        )

        assertFailure("PROTECTION_INSUFFICIENT:artifacts[0].receiptReference.protection") {
            verifier.verify(fixture.workPackage, fixture.report, emptyRoot("retention-overflow"))
        }
    }

    @Test
    fun `archive completion cannot be in the recovery future`() {
        val future = fixture.report.copy(completedAt = NOW.plusNanos(1))

        assertFailure("RECEIPT_MISMATCH:archiveReport.completedAt") {
            fixture.verifier().verify(fixture.workPackage, future, emptyRoot("future-report"))
        }

        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `cached protection must remain effective strictly after operation completion`() {
        val requiredRetainUntil = ARCHIVED_AT.plus(Duration.ofDays(1))
        fixture.receipts.keys.toList().forEach { key ->
            fixture.replaceReceipt(key, fixture.receipts.getValue(key).copy(retentionPolicy = "P1D"))
        }
        fixture.report = fixture.report.copy(retentionPolicy = "P1D")
        val cases = listOf(
            requiredRetainUntil.plusSeconds(5) to requiredRetainUntil.plusSeconds(10) to OperationStatus.FAIL,
            requiredRetainUntil.plusSeconds(10) to requiredRetainUntil.plusSeconds(10) to OperationStatus.FAIL,
            requiredRetainUntil.plusSeconds(11) to requiredRetainUntil.plusSeconds(10) to OperationStatus.PASS,
        )

        cases.forEachIndexed { index, (times, expectedStatus) ->
            fixture.gateway.protections.keys.toList().forEach { key ->
                fixture.gateway.protections[key] = ObjectProtectionSnapshot("COMPLIANCE", times.first)
            }
            val clock = ArrayDeque(listOf(requiredRetainUntil, times.second))
            val verifier = EvidenceArchiveRecoveryVerifier(
                fixture.gateway,
                TimeProvider { clock.removeFirst() },
                OPERATION_TIMEOUT,
                TEST_FILE_KEY_READER,
            )

            val report = verifier.verifyReport(fixture.workPackage, fixture.report, emptyRoot("completion-retention-$index"))

            assertThat(report.status).isEqualTo(expectedStatus)
            assertThat(report.completedAt).isEqualTo(times.second)
            if (expectedStatus == OperationStatus.FAIL) {
                assertThat(report.errorCode).isEqualTo("PROTECTION_INSUFFICIENT")
            }
        }
    }

    @Test
    fun `archive schema version must be the exact integral int one before DTO creation`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val canonical = String(fixture.archiveReportBytes(matching))
        val invalidValues = listOf("4294967297", "-4294967295", "1.0", "\"1\"")

        invalidValues.forEachIndexed { index, value ->
            val bytes = canonical.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":$value").toByteArray()
            val output = reportOutput("schema-$index")
            val result = fixture.verifier().recover(descriptor, bytes, emptyRoot("schema-root-$index"), output)

            assertThat(result.status).isEqualTo(OperationStatus.FAIL)
            assertThat(result.errorCode).isEqualTo("RECEIPT_MISMATCH")
            assertThat(result.executionId).isNull()
            assertThat(Files.readString(output)).doesNotContain(value, matching.executionId)
        }
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `shared directory access checks protect recovery root and output parent before staging`() {
        val root = tempDirectory.resolve("permission-root")
        val outputParent = Files.createDirectory(tempDirectory.resolve("permission-output"))
        val checked = mutableListOf<Path>()
        val reader = EvidenceArchiveDirectoryAccessReader { path ->
            checked.add(path)
            if (path == root) throw java.io.IOException("shared writable")
            EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL
        }
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
            directoryAccessReader = reader,
        )

        assertFailure("RECOVERY_ROOT_INVALID:recoveryRoot") {
            verifier.recover(fixture.descriptorBytes(), fixture.archiveReportBytes(), root, outputParent.resolve("report.json"))
        }
        assertThat(checked).contains(root)
        assertThat(fixture.gateway.events).isEmpty()

        val acceptedRoot = tempDirectory.resolve("accepted-root")
        val outputReader = EvidenceArchiveDirectoryAccessReader { path ->
            if (path == outputParent) throw java.io.IOException("shared writable")
            EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL
        }
        val outputVerifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
            directoryAccessReader = outputReader,
        )
        assertFailure("REPORT_OUTPUT_INVALID:output") {
            outputVerifier.recover(
                fixture.descriptorBytes(),
                fixture.archiveReportBytes(),
                acceptedRoot,
                outputParent.resolve("report.json"),
            )
        }
        assertThat(fixture.gateway.events).isEmpty()
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
            directoryAccessReader = EvidenceArchiveDirectoryAccessReader {
                EvidenceArchiveDirectoryAccessControl.POSIX_NOT_SHARED_WRITABLE
            },
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
        val descriptor = fixture.descriptorBytes()
        val archiveReport = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val output = reportOutput("cleanup-failure")

        val report = verifier.recover(descriptor, fixture.archiveReportBytes(archiveReport), root, output)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("RECOVERY_CLEANUP_FAILED")
        assertThat(report.cleanupStatus).isEqualTo(OperationStatus.FAIL)
        assertThat(report.cleanupErrorCode).isEqualTo("RECOVERY_CLEANUP_FAILED")
        assertThat(report.artifacts).hasSize(2)
        assertThat(report.toString()).doesNotContain("secret", root.toString())
        assertThat(Files.readString(output)).contains(
            "\"cleanupErrorCode\":\"RECOVERY_CLEANUP_FAILED\"",
            "\"cleanupStatus\":\"FAIL\"",
        )
    }

    @Test
    fun `late foreign recovery file is retained and published as cleanup failure`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val root = tempDirectory.resolve("late-foreign-root")
        val output = reportOutput("late-foreign")
        val foreign = root.resolve("foreign.txt")
        var emptyChecksAfterRecovery = 0
        val accessReader = EvidenceArchiveDirectoryAccessReader { path ->
            if (path == root && fixture.gateway.events.lastOrNull() == "head:payload-2" && Files.exists(root)) {
                val empty = Files.list(root).use { it.findAny().isEmpty }
                if (empty) {
                    emptyChecksAfterRecovery += 1
                    if (emptyChecksAfterRecovery == 2) Files.writeString(foreign, "foreign")
                }
            }
            EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL
        }
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
            directoryAccessReader = accessReader,
        )

        val report = verifier.recover(descriptor, fixture.archiveReportBytes(matching), root, output)

        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("RECOVERY_CLEANUP_FAILED")
        assertThat(report.cleanupStatus).isEqualTo(OperationStatus.FAIL)
        assertThat(report.cleanupErrorCode).isEqualTo("RECOVERY_CLEANUP_FAILED")
        assertThat(Files.readString(foreign)).isEqualTo("foreign")
        assertThat(Files.readString(output)).contains(
            "\"status\":\"FAIL\"",
            "\"cleanupStatus\":\"FAIL\"",
            "\"cleanupErrorCode\":\"RECOVERY_CLEANUP_FAILED\"",
        )
    }

    @Test
    fun `foreign file injected after final bytes are forced downgrades the prelink report`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val root = tempDirectory.resolve("prelink-foreign-root")
        val output = reportOutput("prelink-foreign")
        val foreign = root.resolve("foreign-at-link.txt")
        val delegate = RecoveryRootGuard.nio(
            RecoveryRealPathResolver { it.toRealPath() },
            TEST_FILE_KEY_READER,
            EvidenceArchiveDirectoryAccessReader.nio(),
        )
        var finalEmptyGuards = 0
        val guard = RecoveryRootGuard { trusted, expectation ->
            if (expectation == RecoveryRootExpectation.EMPTY &&
                fixture.gateway.events.lastOrNull() == "head:payload-2"
            ) {
                finalEmptyGuards += 1
                if (finalEmptyGuards == 2) Files.writeString(foreign, "foreign")
            }
            delegate.require(trusted, expectation)
        }
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
            recoveryRootGuard = guard,
        )

        val report = verifier.recover(descriptor, fixture.archiveReportBytes(matching), root, output)

        assertThat(finalEmptyGuards).isEqualTo(2)
        assertThat(report.status).isEqualTo(OperationStatus.FAIL)
        assertThat(report.errorCode).isEqualTo("RECOVERY_CLEANUP_FAILED")
        assertThat(report.cleanupStatus).isEqualTo(OperationStatus.FAIL)
        assertThat(Files.readString(foreign)).isEqualTo("foreign")
        assertThat(Files.readString(output)).contains(
            "\"status\":\"FAIL\"",
            "\"cleanupStatus\":\"FAIL\"",
        )
    }

    @Test
    fun `posix output parent partial and published target require nonnull file keys`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val posixAccess = EvidenceArchiveDirectoryAccessReader {
            EvidenceArchiveDirectoryAccessControl.POSIX_NOT_SHARED_WRITABLE
        }

        val parentOutput = reportOutput("null-parent-key")
        val nullParent = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            RecoveryFileKeyReader { path, _ -> if (path == parentOutput.parent) null else "key:$path" },
            directoryAccessReader = posixAccess,
        )
        assertFailure("REPORT_OUTPUT_INVALID:output") {
            nullParent.recover(descriptor, fixture.archiveReportBytes(matching), emptyRoot("null-parent-root"), parentOutput)
        }

        val partialOutput = reportOutput("null-partial-key")
        val nullPartial = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            RecoveryFileKeyReader { path, _ ->
                if (path.fileName.toString().endsWith(".partial")) null else "key:$path"
            },
            directoryAccessReader = posixAccess,
        )
        assertFailure("REPORT_CLEANUP_FAILED:output") {
            nullPartial.recover(descriptor, fixture.archiveReportBytes(matching), emptyRoot("null-partial-root"), partialOutput)
        }
        assertThat(Files.exists(partialOutput)).isFalse()
        Files.list(partialOutput.parent).use { paths ->
            assertThat(paths.filter { it.fileName.toString().endsWith(".partial") }.count()).isEqualTo(1)
        }

        val publishedOutput = reportOutput("null-published-key")
        val nullPublished = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            RecoveryFileKeyReader { path, _ -> if (path == publishedOutput) null else "key:$path" },
            directoryAccessReader = posixAccess,
        )
        assertFailure("REPORT_WRITE_FAILED:output") {
            nullPublished.recover(
                descriptor,
                fixture.archiveReportBytes(matching),
                emptyRoot("null-published-root"),
                publishedOutput,
            )
        }
        assertThat(Files.exists(publishedOutput)).isTrue()
        assertThat(Files.readString(publishedOutput)).contains("\"status\":\"PASS\"")
    }

    @Test
    fun `initialization cleanup never deletes a partial whose injected ownership key changed`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val output = reportOutput("changed-init-partial")
        var partialReads = 0
        val reader = RecoveryFileKeyReader { path, _ ->
            if (path.fileName.toString().endsWith(".partial")) {
                partialReads += 1
                if (partialReads == 1) "expected-partial-key" else "foreign-partial-key"
            } else {
                "key:$path"
            }
        }
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            reader,
        )

        assertFailure("REPORT_CLEANUP_FAILED:output") {
            verifier.recover(descriptor, fixture.archiveReportBytes(matching), emptyRoot("changed-init-root"), output)
        }

        assertThat(Files.exists(output)).isFalse()
        Files.list(output.parent).use { paths ->
            assertThat(paths.filter { it.fileName.toString().endsWith(".partial") }.count()).isEqualTo(1)
        }
    }

    @Test
    fun `invalid archive and receipt times and retention publish precise safe failures`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val mapper = jacksonObjectMapper()
        listOf("startedAt", "completedAt", "capabilityCheckedAt", "retentionPolicy").forEach { field ->
            val root = mapper.readTree(fixture.archiveReportBytes(matching)) as com.fasterxml.jackson.databind.node.ObjectNode
            root.put(field, "not-a-${field.lowercase()}")
            val output = reportOutput("invalid-$field")

            val report = fixture.verifier().recover(
                descriptor,
                JsonCanonicalizer(mapper.writeValueAsBytes(root)).encodedUTF8,
                emptyRoot("invalid-$field-root"),
                output,
            )

            assertThat(report.errorCode).isEqualTo("RECEIPT_MISMATCH")
            assertThat(Files.readString(output)).doesNotContain("not-a-", matching.executionId)
        }

        fixture = Fixture()
        val receiptRoot = mapper.readTree(fixture.gateway.bodies.getValue("receipt-1")) as com.fasterxml.jackson.databind.node.ObjectNode
        receiptRoot.put("archivedAt", "not-an-instant")
        val receiptBytes = JsonCanonicalizer(mapper.writeValueAsBytes(receiptRoot)).encodedUTF8
        fixture.gateway.bodies["receipt-1"] = receiptBytes
        fixture.report = fixture.report.copy(
            descriptorSha256 = sha256(descriptor),
            artifacts = fixture.report.artifacts.mapIndexed { index, artifact ->
                if (index == 0) artifact.copy(
                    receiptReference = artifact.receiptReference.copy(
                        sha256 = sha256(receiptBytes),
                        sizeBytes = receiptBytes.size.toLong(),
                    ),
                ) else artifact
            },
        )
        val receiptOutput = reportOutput("invalid-receipt-time")
        val report = fixture.verifier().recover(
            descriptor,
            fixture.archiveReportBytes(),
            emptyRoot("invalid-receipt-time-root"),
            receiptOutput,
        )
        assertThat(report.errorCode).isEqualTo("RECEIPT_MISMATCH")
        assertThat(Files.readString(receiptOutput)).doesNotContain("not-an-instant")
    }

    @Test
    fun `second artifact failure report preserves the first verified evidence`() {
        fixture.gateway.responseVersions["receipt-2"] = "shadow"

        val result = fixture.verifier().verifyReport(fixture.workPackage, fixture.report, emptyRoot("partial-result"))

        assertThat(result.status).isEqualTo(OperationStatus.FAIL)
        assertThat(result.errorCode).isEqualTo("VERSION_MISMATCH")
        assertThat(result.cleanupStatus).isEqualTo(OperationStatus.PASS)
        assertThat(result.cleanupErrorCode).isNull()
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
    fun `Error is rethrown after owned recovery and output partials are cleaned`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val root = emptyRoot("error-cleanup-root")
        val output = reportOutput("error-cleanup")
        val fatal = AssertionError("fatal")
        fixture.gateway.failures["payload-1"] = fatal

        assertThatThrownBy {
            fixture.verifier().recover(descriptor, fixture.archiveReportBytes(matching), root, output)
        }.isSameAs(fatal)

        Files.list(root).use { assertThat(it.toList()).isEmpty() }
        assertThat(Files.exists(output)).isFalse()
        Files.list(output.parent).use { assertThat(it.toList()).isEmpty() }
    }

    @Test
    fun `untrusted archive fields never enter a failed canonical recovery report`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val mapper = jacksonObjectMapper()
        val untrusted = mapper.readTree(fixture.archiveReportBytes(matching)) as com.fasterxml.jackson.databind.node.ObjectNode
        untrusted.put("executionId", "C:\\private\\credential=TOP_SECRET")
        untrusted.put("policyFingerprint", "secret-token")
        untrusted.withObject("runtimeIdentity").put("principalFingerprint", "arn:aws:iam::secret/path")
        val archiveBytes = JsonCanonicalizer(mapper.writeValueAsBytes(untrusted)).encodedUTF8
        val output = reportOutput("untrusted")

        val result = fixture.verifier().recover(descriptor, archiveBytes, emptyRoot("untrusted-root"), output)

        assertThat(result.status).isEqualTo(OperationStatus.FAIL)
        assertThat(result.errorCode).isEqualTo("RECEIPT_MISMATCH")
        assertThat(result.executionId).isNull()
        assertThat(result.archiveIdentity).isNull()
        val canonical = Files.readString(output)
        assertThat(canonical).doesNotContain("TOP_SECRET", "credential", "private", "arn:aws", "secret-token")
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `archive identity execution and control formats are validated before becoming trusted`() {
        val descriptor = fixture.descriptorBytes()
        val base = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val invalid = listOf(
            base.copy(executionId = base.executionId.uppercase()),
            base.copy(runtimeIdentity = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "C:\\private\\principal")),
            base.copy(accessOwner = "credential=SECRET"),
            base.copy(policyFingerprint = "A".repeat(64)),
        )

        invalid.forEachIndexed { index, report ->
            val output = reportOutput("strict-$index")
            val result = fixture.verifier().recover(
                descriptor,
                fixture.archiveReportBytes(report),
                emptyRoot("strict-root-$index"),
                output,
            )

            assertThat(result.status).isEqualTo(OperationStatus.FAIL)
            assertThat(result.executionId).isNull()
            assertThat(result.archiveIdentity).isNull()
            assertThat(Files.readString(output)).doesNotContain("C:\\private\\principal", "credential", "SECRET")
        }
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `archive access owner follows normalized safe production text domain`() {
        listOf(
            "Release Security",
            "release/security",
            "发布安全团队",
            "Company / Division / Security",
            "公司 / 平台 / 安全",
            "Security https://organization.example/division",
            "path=/Company/Division/Security",
            "Release Owner ".repeat(256),
        ).forEachIndexed { index, accessOwner ->
            fixture = Fixture()
            fixture.receipts.keys.toList().forEach { key ->
                fixture.replaceReceipt(key, fixture.receipts.getValue(key).copy(accessOwner = accessOwner))
            }
            fixture.report = fixture.report.copy(accessOwner = accessOwner)

            assertThat(fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("owner-valid-$index")).status)
                .isEqualTo(OperationStatus.PASS)
        }

        listOf(
            "",
            " ",
            "\u00a0",
            "\u3000",
            "owner\u0085team",
            "principal=raw-role",
            "secret=credential-value",
            "Bearer opaque-access-token",
            "arn:aws:iam::123456789012:role/release-security",
            "  https://organization.example/division  ",
            "C:\\Company\\Division\\Security",
            "\\\\server\\division\\security",
            "/Company/Division/Security",
        ).forEachIndexed { index, accessOwner ->
            fixture = Fixture()
            val invalid = fixture.report.copy(accessOwner = accessOwner)
            assertFailure("RECEIPT_MISMATCH:archiveReport.accessOwner") {
                fixture.verifier().verify(fixture.workPackage, invalid, emptyRoot("owner-invalid-$index"))
            }
            assertThat(fixture.gateway.events).isEmpty()
        }
    }

    @Test
    fun `verifier identity is re-attested after provider reads and must remain stable`() {
        fixture.gateway.identities += VERIFIER_IDENTITY
        fixture.gateway.identities += RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "f".repeat(64))

        assertFailure("DOWNLOAD_FAILED:runtimeIdentity") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("identity-changed"))
        }

        assertThat(fixture.gateway.identityCalls).isEqualTo(2)
        assertThat(fixture.gateway.timeline.last()).isEqualTo("identity")
        assertThat(fixture.gateway.timeline).containsSubsequence("head:payload-2", "identity")
    }

    @Test
    fun `second identity failure publishes no completion marker`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val output = reportOutput("second-identity-failure")
        fixture.gateway.failIdentityCall = 2

        val result = fixture.verifier().recover(
            descriptor,
            fixture.archiveReportBytes(matching),
            emptyRoot("second-identity-failure-root"),
            output,
        )

        assertThat(result.status).isEqualTo(OperationStatus.FAIL)
        assertThat(result.errorCode).isEqualTo("DOWNLOAD_FAILED")
        assertThat(fixture.gateway.identityCalls).isEqualTo(2)
        Files.list(output.parent).use { files ->
            assertThat(files.map { it.fileName.toString() }).containsExactly(output.fileName.toString())
        }
    }

    @Test
    fun `second identity rejects unsafe attestation`() {
        fixture.gateway.identities += VERIFIER_IDENTITY
        fixture.gateway.identities += RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "secret=raw-principal")

        assertFailure("DOWNLOAD_FAILED:runtimeIdentity") {
            fixture.verifier().verify(fixture.workPackage, fixture.report, emptyRoot("identity-secret"))
        }
        assertThat(fixture.gateway.identityCalls).isEqualTo(2)
    }

    @Test
    fun `invalid report output fails before parsing identity or downloads`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val relativeOutput = Path.of("recovery-report.json")

        assertFailure("REPORT_OUTPUT_INVALID:output") {
            fixture.verifier().recover(
                descriptor,
                fixture.archiveReportBytes(matching),
                emptyRoot("invalid-output-root"),
                relativeOutput,
            )
        }

        assertThat(fixture.gateway.events).isEmpty()
        assertThat(Files.exists(relativeOutput)).isFalse()
    }

    @Test
    fun `malformed inputs still publish a safe fail report after output staging`() {
        val output = reportOutput("malformed")

        val result = fixture.verifier().recover(
            "not-json C:\\private\\descriptor".toByteArray(),
            "not-json credential=SECRET".toByteArray(),
            emptyRoot("malformed-root"),
            output,
        )

        assertThat(result.status).isEqualTo(OperationStatus.FAIL)
        assertThat(result.workPackageId).isEqualTo(WORK_PACKAGE_ID)
        assertThat(result.executionId).isNull()
        assertThat(result.errorCode).isEqualTo("RECEIPT_MISMATCH")
        assertThat(Files.readString(output)).doesNotContain("private", "credential", "SECRET")
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `file operation owns input read failures and still publishes the safe final report`() {
        val descriptor = tempDirectory.resolve("malformed-work-package.json")
        val archiveReport = tempDirectory.resolve("malformed-archive-report.json")
        Files.writeString(descriptor, "{} trailing C:\\private")
        Files.writeString(archiveReport, "credential=SECRET")
        val output = reportOutput("file-malformed")

        val result = fixture.verifier().recoverFiles(
            descriptor,
            archiveReport,
            emptyRoot("file-malformed-root"),
            output,
        )

        assertThat(result.errorCode).isEqualTo("RECEIPT_MISMATCH")
        assertThat(result.executionId).isNull()
        assertThat(Files.readString(output)).doesNotContain("private", "credential", "SECRET")
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `foreign recovery content is not deleted and cleanup has secondary error precedence`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val root = emptyRoot("foreign-root")
        fixture.gateway.onEvent = { event ->
            if (event == "download:receipt-2") Files.writeString(root.resolve("foreign.txt"), "foreign")
        }
        fixture.gateway.responseVersions["receipt-2"] = "shadow"

        val result = fixture.verifier().recover(
            descriptor,
            fixture.archiveReportBytes(matching),
            root,
            reportOutput("foreign"),
        )

        assertThat(result.errorCode).isEqualTo("VERSION_MISMATCH")
        assertThat(result.cleanupStatus).isEqualTo(OperationStatus.FAIL)
        assertThat(result.cleanupErrorCode).isEqualTo("RECOVERY_CLEANUP_FAILED")
        assertThat(result.artifacts.map { it.artifactId }).containsExactly("1001")
        assertThat(Files.readString(root.resolve("foreign.txt"))).isEqualTo("foreign")
    }

    @Test
    fun `success uses different identity receipt first exact versions protection and leaves root empty`() {
        val root = emptyRoot("success")

        val result = fixture.verifier().verify(fixture.workPackage, fixture.report, root)

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
        assertThat(result.errorCode).isNull()
        assertThat(result.cleanupStatus).isEqualTo(OperationStatus.PASS)
        assertThat(result.cleanupErrorCode).isNull()
        assertThat(result.executionId).isEqualTo(fixture.report.executionId)
        assertThat(result.archiveIdentity).isEqualTo(ARCHIVE_IDENTITY)
        assertThat(result.verifierIdentity).isEqualTo(VERIFIER_IDENTITY).isNotEqualTo(result.archiveIdentity)
        assertThat(result.artifacts).hasSize(2)
        assertThat(fixture.gateway.events).containsExactly(
            "download:receipt-1", "download:payload-1", "head:receipt-1", "head:payload-1",
            "download:receipt-2", "download:payload-2", "head:receipt-2", "head:payload-2",
        )
        assertThat(fixture.gateway.identityCalls).isEqualTo(2)
        assertThat(fixture.gateway.timeline.last()).isEqualTo("identity")
        assertThat(result.completedAt).isEqualTo(NOW)
        Files.list(root).use { assertThat(it.toList()).isEmpty() }
    }

    @Test
    fun `two phase operation publishes only the final report outside the empty recovery root`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val root = emptyRoot("two-phase-root")
        val output = reportOutput("two-phase")
        fixture.gateway.onEvent = { event ->
            if (event == "download:receipt-1") {
                assertThat(Files.exists(output)).isFalse()
                val staged = Files.list(output.parent).use { it.toList().single() }
                assertThat(staged.fileName.toString()).endsWith(".partial")
                assertThat(Files.readString(staged)).contains("\"status\":\"IN_PROGRESS\"")
                    .doesNotContain(matching.executionId, ARCHIVE_IDENTITY.principalFingerprint)
            }
        }

        val result = fixture.verifier().recover(descriptor, fixture.archiveReportBytes(matching), root, output)

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
        assertThat(Files.readAllBytes(output)).isEqualTo(JsonCanonicalizer(Files.readAllBytes(output)).encodedUTF8)
        Files.list(root).use { assertThat(it.toList()).isEmpty() }
        val reportBytes = Files.readAllBytes(output)
        val marker = output.resolveSibling("${output.fileName}.complete.${sha256(reportBytes)}")
        assertThat(marker.fileName.toString().length).isLessThanOrEqualTo(255)
        assertThat(Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isTrue()
        assertThat(Files.isSymbolicLink(marker)).isFalse()
        assertThat(Files.size(marker)).isZero()
        Files.list(output.parent).use { paths ->
            assertThat(paths.map { it.fileName.toString() }).containsExactlyInAnyOrder(
                output.fileName.toString(),
                marker.fileName.toString(),
            )
        }
    }

    @Test
    fun `output filename is portable and marker safe before provisional staging`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        listOf("恢复.json", "CON.json", "tail.", "a".repeat(177) + ".json")
            .forEachIndexed { index, fileName ->
                val parent = Files.createDirectory(tempDirectory.resolve("invalid-output-$index"))
                val output = parent.resolve(fileName)

                assertFailure("REPORT_OUTPUT_INVALID:output") {
                    fixture.verifier().recover(
                        descriptor,
                        fixture.archiveReportBytes(matching),
                        emptyRoot("invalid-output-root-$index"),
                        output,
                    )
                }

                Files.list(parent).use { assertThat(it.toList()).isEmpty() }
            }
        assertThat(fixture.gateway.events).isEmpty()
    }

    @Test
    fun `181 byte output filename succeeds under a Unicode parent with a portable derived marker`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val parent = Files.createDirectory(tempDirectory.resolve("报告目录"))
        val output = parent.resolve("a".repeat(176) + ".json")

        val result = fixture.verifier().recover(
            descriptor,
            fixture.archiveReportBytes(matching),
            emptyRoot("portable-output-root"),
            output,
        )

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
        val reportBytes = Files.readAllBytes(output)
        val marker = output.resolveSibling("${output.fileName}.complete.${sha256(reportBytes)}")
        assertThat(marker.fileName.toString().toByteArray(StandardCharsets.UTF_8)).hasSize(255)
        assertThat(Files.size(marker)).isZero()
    }

    @Test
    fun `normal recovery verification output filename remains accepted`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val output = Files.createDirectory(tempDirectory.resolve("normal-output")).resolve("recovery-verification.json")

        val result = fixture.verifier().recover(
            descriptor,
            fixture.archiveReportBytes(matching),
            emptyRoot("normal-output-root"),
            output,
        )

        assertThat(result.status).isEqualTo(OperationStatus.PASS)
        assertThat(Files.exists(output)).isTrue()
    }

    @Test
    fun `published report target is immutable across every post-link failure`() {
        PublishFailure.entries.forEach { phase ->
            fixture = Fixture()
            val descriptor = fixture.descriptorBytes()
            val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
            val root = emptyRoot("published-${phase.name.lowercase()}-root")
            val output = reportOutput("published-${phase.name.lowercase()}")
            val operations = RecordingPublishOperations(phase)
            val verifier = EvidenceArchiveRecoveryVerifier(
                fixture.gateway,
                TimeProvider { NOW },
                OPERATION_TIMEOUT,
                TEST_FILE_KEY_READER,
                reportPublishOperations = operations,
            )

            assertFailure(phase.expectedCode) {
                verifier.recover(descriptor, fixture.archiveReportBytes(matching), root, output)
            }

            assertThat(Files.exists(output)).isTrue()
            assertThat(Files.readAllBytes(output)).isEqualTo(operations.bytesAtLink)
            assertThat(JsonCanonicalizer(operations.bytesAtLink).encodedUTF8).isEqualTo(operations.bytesAtLink)
            assertThat(String(operations.bytesAtLink)).contains("\"status\":\"PASS\"")
            assertThat(operations.events).doesNotContain("delete-target", "write-target")
            Files.list(output.parent).use { paths ->
                assertThat(paths.noneMatch { it.fileName.toString().contains(".complete.") }).isTrue()
            }
            val downloadCount = fixture.gateway.events.size
            assertFailure("REPORT_TARGET_EXISTS:output") {
                verifier.recover(
                    descriptor,
                    fixture.archiveReportBytes(matching),
                    emptyRoot("retry-${phase.name.lowercase()}"),
                    output,
                )
            }
            assertThat(fixture.gateway.events).hasSize(downloadCount)
            assertThat(Files.readAllBytes(output)).isEqualTo(operations.bytesAtLink)
        }
    }

    @Test
    fun `preexisting completion marker collision is rejected before report publication`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val firstOutput = reportOutput("marker-source")
        fixture.verifier().recover(
            descriptor,
            fixture.archiveReportBytes(matching),
            emptyRoot("marker-source-root"),
            firstOutput,
        )
        val digest = sha256(Files.readAllBytes(firstOutput))
        val collisionOutput = reportOutput("marker-collision")
        val collisionMarker = collisionOutput.resolveSibling("${collisionOutput.fileName}.complete.$digest")
        Files.createFile(collisionMarker)

        assertFailure("REPORT_TARGET_EXISTS:output") {
            fixture.verifier().recover(
                descriptor,
                fixture.archiveReportBytes(matching),
                emptyRoot("marker-collision-root"),
                collisionOutput,
            )
        }

        assertThat(Files.exists(collisionOutput)).isFalse()
        assertThat(Files.size(collisionMarker)).isZero()
    }

    @Test
    fun `physical output alias into recovery root is rejected before provisional or downloads`() {
        val descriptor = fixture.descriptorBytes()
        val matching = fixture.report.copy(descriptorSha256 = sha256(descriptor))
        val root = emptyRoot("physical-alias-root")
        val outputParent = Files.createDirectory(tempDirectory.resolve("lexical-output-parent"))
        val output = outputParent.resolve("recovery.json")
        val resolver = RecoveryRealPathResolver { path ->
            when (path) {
                outputParent -> root
                else -> path.toRealPath()
            }
        }
        val verifier = EvidenceArchiveRecoveryVerifier(
            fixture.gateway,
            TimeProvider { NOW },
            OPERATION_TIMEOUT,
            TEST_FILE_KEY_READER,
            realPathResolver = resolver,
        )

        assertFailure("REPORT_OUTPUT_INVALID:output") {
            verifier.recover(descriptor, fixture.archiveReportBytes(matching), root, output)
        }

        assertThat(fixture.gateway.events).isEmpty()
        assertThat(Files.exists(output)).isFalse()
        Files.list(outputParent).use { assertThat(it.toList()).isEmpty() }
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
        val output = tempDirectory.resolve("reports").also(Files::createDirectory).resolve("recovery.json")
        val recoveryReport = verifier.recover(descriptorBytes, archiveReportBytes, recoveryRoot, output)

        assertThat(parsedWorkPackage.descriptorSha256).isEqualTo(sha256(descriptorBytes))
        assertThat(parsedWorkPackage.artifacts.map { it.path.fileName.toString() })
            .containsExactly("artifact-1.zip", "artifact-2.zip")
        assertThat(parsedArchiveReport.candidate()).isEqualTo(expectedArchiveReport)
        assertThat(recoveryReport.artifacts).allMatch { it.receiptArchivedAt == ARCHIVED_AT }
        val outputBytes = Files.readAllBytes(output)
        assertThat(JsonCanonicalizer(outputBytes).encodedUTF8).isEqualTo(outputBytes)
        assertThat(String(outputBytes)).contains("\"receiptArchivedAt\":\"$ARCHIVED_AT\"")
        assertThat(String(outputBytes)).doesNotContain(tempDirectory.toString(), "credential", "https://", "arn:")
        assertFailure("REPORT_TARGET_EXISTS:output") {
            verifier.recover(descriptorBytes, archiveReportBytes, emptyRoot("second-output-attempt"), output)
        }
    }

    @Test
    fun `recovery report output is rejected inside the recovery root`() {
        val root = emptyRoot("output-boundary")

        assertFailure("REPORT_OUTPUT_INVALID:output") {
            fixture.verifier().recover(
                fixture.descriptorBytes(),
                fixture.archiveReportBytes(),
                root,
                root.resolve("recovery-report.json"),
            )
        }
    }

    private fun emptyRoot(name: String): Path = Files.createDirectory(tempDirectory.resolve(name))

    private fun reportOutput(name: String): Path =
        Files.createDirectory(tempDirectory.resolve("$name-reports")).resolve("recovery.json")

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

    private enum class PublishFailure(val expectedCode: String) {
        VALIDATE("REPORT_WRITE_FAILED:output"),
        FORCE("REPORT_WRITE_FAILED:output"),
        CLEANUP("REPORT_CLEANUP_FAILED:output"),
        MARKER("REPORT_WRITE_FAILED:output"),
    }

    private class RecordingPublishOperations(
        private val failure: PublishFailure,
    ) : RecoveryReportPublishOperations {
        val events = mutableListOf<String>()
        lateinit var bytesAtLink: ByteArray

        override fun createLink(target: Path, partial: Path) {
            events += "create-link"
            Files.createLink(target, partial)
            bytesAtLink = Files.readAllBytes(target)
        }

        override fun validatePublished(
            target: Path,
            partial: Path,
            directory: EvidenceArchiveTrustedDirectory,
            expectedBytes: ByteArray,
        ) {
            events += "validate-published"
            if (failure == PublishFailure.VALIDATE) throw java.io.IOException("post-link validation")
            check(Files.isSameFile(target, partial) && Files.readAllBytes(target).contentEquals(expectedBytes))
        }

        override fun forceDirectory(directory: Path) {
            events += "force-directory"
            if (failure == PublishFailure.FORCE) throw java.io.IOException("directory force")
        }

        override fun cleanupPartial(partial: Path) {
            events += "cleanup-partial"
            if (failure == PublishFailure.CLEANUP) throw java.io.IOException("partial cleanup")
            Files.delete(partial)
        }

        override fun createCompletionMarker(marker: Path) {
            events += "create-completion-marker"
            if (failure == PublishFailure.MARKER) throw java.io.IOException("marker create")
            Files.createFile(marker)
        }
    }

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
        val identities = ArrayDeque<RuntimeIdentityRef>()
        var failIdentityCall: Int? = null
        var identityCalls = 0
        val events = mutableListOf<String>()
        val timeline = mutableListOf<String>()
        val bodies = linkedMapOf<String, ByteArray>()
        val responseVersions = mutableMapOf<String, String>()
        val protections = mutableMapOf<String, ObjectProtectionSnapshot>()
        val failures = mutableMapOf<String, Throwable>()
        var onEvent: (String) -> Unit = {}

        override fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef {
            identityCalls += 1
            timeline += "identity"
            if (failIdentityCall == identityCalls) throw ArchiveUnavailable("identity unavailable")
            return identities.removeFirstOrNull() ?: identity
        }

        override fun downloadExact(source: StoredObjectRef, maxBytes: Long, timeout: Duration): ExactObjectDownload {
            val event = "download:${source.key}"
            events += event
            timeline += event
            onEvent(event)
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
            val event = "head:${source.key}"
            events += event
            timeline += event
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
        val CAPABILITY_CHECKED_AT: Instant = Instant.parse("2026-01-01T00:00:05Z")
        val ARCHIVED_AT: Instant = Instant.parse("2026-01-01T00:00:10Z")
        val RETAIN_UNTIL: Instant = Instant.parse("2028-01-02T00:00:10Z")
        val NOW: Instant = Instant.parse("2026-01-02T00:00:00Z")
        val ARCHIVE_IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "d".repeat(64))
        val VERIFIER_IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "e".repeat(64))
        val TEST_FILE_KEY_READER = RecoveryFileKeyReader { _, attributes ->
            attributes.fileKey() ?: "test-file-key"
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
