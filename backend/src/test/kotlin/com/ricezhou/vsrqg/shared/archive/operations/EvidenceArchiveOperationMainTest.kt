package com.ricezhou.vsrqg.shared.archive.operations

import com.ricezhou.vsrqg.shared.adapter.archive.operations.ArchiveOperation
import com.ricezhou.vsrqg.shared.adapter.archive.operations.ArchiveOperationRequest
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveArtifactReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExecutionReport
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveExactObjectReference
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveNarrowContext
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveOperationMain
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveOperationFailure
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveOperationSummary
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveTextSink
import com.ricezhou.vsrqg.shared.adapter.archive.operations.OperationStatus
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryOperation
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryOperationFactory
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EvidenceArchiveOperationMainTest {
    @Test
    fun `archive returns zero and prints only a stable JSON summary on success`() {
        val captured = mutableListOf<ArchiveOperationRequest>()
        val main = EvidenceArchiveOperationMain(
            archiveOperation = ArchiveOperation { request -> captured += request; report(OperationStatus.PASS) },
        )

        val result = invoke(main, ARCHIVE_ARGS)

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).isEqualTo(
            "{\"artifactCount\":2,\"result\":\"PASS\",\"workPackageId\":\"V0-2-EVIDENCE-ARCHIVE-001\"}\n",
        )
        assertThat(result.stderr).isEmpty()
        assertThat(captured.single()).isEqualTo(
            ArchiveOperationRequest(Path.of("work-package.json"), Path.of("C:\\source"), Path.of("C:\\reports\\archive.json")),
        )
    }

    @Test
    fun `archive returns one for a known failed report and for an unexpected exception`() {
        val known = invoke(
            EvidenceArchiveOperationMain(archiveOperation = ArchiveOperation { report(OperationStatus.FAIL, "ARCHIVE_UNAVAILABLE") }),
            ARCHIVE_ARGS,
        )
        val unexpected = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation { throw IllegalStateException("SENSITIVE_MARKER C:\\private\\source.zip") },
            ),
            ARCHIVE_ARGS,
        )

        assertThat(known.exitCode).isEqualTo(1)
        assertThat(known.stdout).containsOnlyOnce("ARCHIVE_UNAVAILABLE").doesNotContain("source.zip", "secret")
        assertThat(unexpected.exitCode).isEqualTo(1)
        assertThat(unexpected.stdout).containsOnlyOnce("UNEXPECTED_FAILURE").doesNotContain("source.zip", "secret")
        assertThat(known.stderr).isEmpty()
        assertThat(unexpected.stderr).isEmpty()
    }

    @Test
    fun `does not trust a path bearing injected operation error code`() {
        val result = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation {
                    throw EvidenceArchiveOperationFailure("C:\\private\\SENSITIVE_MARKER")
                },
            ),
            ARCHIVE_ARGS,
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).isEqualTo(SAFE_UNEXPECTED_SUMMARY)
        assertThat(result.stdout).doesNotContain("private", "SENSITIVE_MARKER")
    }

    @Test
    fun `does not trust an unknown operation error code that matches the stable format`() {
        val result = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation {
                    throw EvidenceArchiveOperationFailure("MALICIOUS_BUT_WELL_FORMED")
                },
            ),
            ARCHIVE_ARGS,
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).isEqualTo(SAFE_UNEXPECTED_SUMMARY)
    }

    @Test
    fun `preserves an explicitly allowed report operation failure code`() {
        val result = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation {
                    throw EvidenceArchiveOperationFailure("REPORT_WRITE_FAILED")
                },
            ),
            ARCHIVE_ARGS,
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).isEqualTo(
            "{\"artifactCount\":0,\"errorCode\":\"REPORT_WRITE_FAILED\",\"result\":\"FAIL\"}\n",
        )
    }

    @Test
    fun `does not classify a wrapped illegal argument as configuration failure`() {
        val result = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation {
                    throw IllegalStateException("SENSITIVE_MARKER", IllegalArgumentException("C:\\private"))
                },
            ),
            ARCHIVE_ARGS,
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).isEqualTo(SAFE_UNEXPECTED_SUMMARY)
        assertThat(result.stdout).doesNotContain("private", "SENSITIVE_MARKER")
    }

    @Test
    fun `does not trust an unknown recovery summary error code`() {
        val result = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation { report() },
                recoveryOperation = RecoveryOperation {
                    EvidenceArchiveOperationSummary(null, OperationStatus.FAIL, null, "MALICIOUS_BUT_WELL_FORMED")
                },
            ),
            VERIFY_ARGS,
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).isEqualTo(SAFE_UNEXPECTED_SUMMARY)
    }

    @Test
    fun `preserves a known recovery failure code in the safe exit one summary`() {
        val result = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation { report() },
                recoveryOperation = RecoveryOperation {
                    EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.FAIL, 1, "VERSION_MISMATCH")
                },
            ),
            VERIFY_ARGS,
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).isEqualTo(
            "{\"artifactCount\":1,\"errorCode\":\"VERSION_MISMATCH\",\"result\":\"FAIL\",\"workPackageId\":\"V0-2-EVIDENCE-ARCHIVE-001\"}\n",
        )
    }

    @Test
    fun `validates the complete recovery summary before selecting output and exit code`() {
        val untrustedSummaries = listOf(
            EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.PASS, 0, null),
            EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.PASS, 2, "MALICIOUS_BUT_WELL_FORMED"),
            EvidenceArchiveOperationSummary("C:\\private\\SENSITIVE_WORK_PACKAGE", OperationStatus.FAIL, 0, "ARCHIVE_UNAVAILABLE"),
            EvidenceArchiveOperationSummary(null, OperationStatus.PASS, 2, null),
            EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.FAIL, null, "ARCHIVE_UNAVAILABLE"),
            EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.FAIL, -1, "ARCHIVE_UNAVAILABLE"),
            EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.FAIL, 999_999, "ARCHIVE_UNAVAILABLE"),
            EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.FAIL, 0, null),
            EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.PASS, 2, "ARCHIVE_UNAVAILABLE"),
        )

        untrustedSummaries.forEach { summary ->
            val result = invoke(
                EvidenceArchiveOperationMain(
                    archiveOperation = ArchiveOperation { report() },
                    recoveryOperation = RecoveryOperation { summary },
                ),
                VERIFY_ARGS,
            )

            assertThat(result.exitCode).isEqualTo(1)
            assertThat(result.stdout).isEqualTo(SAFE_UNEXPECTED_SUMMARY)
            assertThat(result.stdout).doesNotContain("MALICIOUS_BUT_WELL_FORMED", "SENSITIVE_WORK_PACKAGE", "999999")
        }
    }

    @Test
    fun `strict parser returns usage exit two for malformed invocations`() {
        val invalid = listOf(
            emptyArray(),
            arrayOf("unknown"),
            arrayOf("archive", "--work-package=work-package.json", "--source-root=C:\\source"),
            ARCHIVE_ARGS + "--unknown=value",
            ARCHIVE_ARGS + "--output=C:\\reports\\second.json",
            arrayOf("archive", "--work-package=", "--source-root=C:\\source", "--output=C:\\report.json"),
            arrayOf("archive", "work-package.json", "--source-root=C:\\source", "--output=C:\\report.json"),
        )

        invalid.forEach { args ->
            val result = invoke(EvidenceArchiveOperationMain(archiveOperation = ArchiveOperation { report() }), args)
            assertThat(result.exitCode).isEqualTo(2)
            assertThat(result.stdout).isEqualTo("{\"artifactCount\":0,\"errorCode\":\"USAGE_ERROR\",\"result\":\"FAIL\"}\n")
            assertThat(result.stderr).isEmpty()
        }
    }

    @Test
    fun `verify syntax is fixed and uses the injected recovery seam`() {
        val requests = mutableListOf<com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryOperationRequest>()
        val main = EvidenceArchiveOperationMain(
            archiveOperation = ArchiveOperation { report() },
            recoveryOperation = RecoveryOperation { request ->
                requests += request
                EvidenceArchiveOperationSummary("V0-2-EVIDENCE-ARCHIVE-001", OperationStatus.PASS, 2, null)
            },
        )

        val result = invoke(main, VERIFY_ARGS)

        assertThat(result.exitCode).isZero()
        assertThat(requests.single().workPackage).isEqualTo(Path.of("work-package.json"))
        assertThat(requests.single().archiveReport).isEqualTo(Path.of("C:\\reports\\archive.json"))
        assertThat(requests.single().recoveryRoot).isEqualTo(Path.of("C:\\recovery"))
        assertThat(requests.single().output).isEqualTo(Path.of("C:\\reports\\recovery.json"))
    }

    @Test
    fun `production verify installs recovery through the narrow factory seam`() {
        var factoryCalls = 0
        val result = invoke(
            EvidenceArchiveOperationMain(
                archiveOperation = ArchiveOperation { report() },
                recoveryOperationFactory = RecoveryOperationFactory {
                    factoryCalls += 1
                    RecoveryOperation {
                        EvidenceArchiveOperationSummary(WORK_PACKAGE_ID, OperationStatus.PASS, 2, null)
                    }
                },
            ),
            VERIFY_ARGS,
        )

        assertThat(result.exitCode).isZero()
        assertThat(result.stdout).isEqualTo(
            "{\"artifactCount\":2,\"result\":\"PASS\",\"workPackageId\":\"V0-2-EVIDENCE-ARCHIVE-001\"}\n",
        )
        assertThat(result.stderr).isEmpty()
        assertThat(factoryCalls).isEqualTo(1)
    }

    @Test
    fun `default production verify fails configuration without claiming unavailable or contacting a provider`() {
        val result = invoke(EvidenceArchiveOperationMain(archiveOperation = ArchiveOperation { report() }), VERIFY_ARGS)

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stdout).contains("CONFIGURATION_INVALID")
            .doesNotContain("VERIFICATION_UNAVAILABLE", "s3://", "credential", "principal")
        assertThat(result.stderr).isEmpty()
    }

    @Test
    fun `narrow context contains archive beans without web database flyway or security infrastructure`() {
        EvidenceArchiveNarrowContext.open().use { context ->
            val beanTypes = context.beanDefinitionNames.mapNotNull { name -> context.getType(name)?.name }
            assertThat(beanTypes).anyMatch { it.endsWith("EvidenceArchiveRunner") }
            assertThat(beanTypes).anyMatch { it.endsWith("EvidenceArchiveSourceVerifier") }
            assertThat(beanTypes).anyMatch { it.endsWith("EvidenceArchiveRecoveryVerifier") }
            assertThat(beanTypes).noneMatch { type ->
                listOf("web", "jdbc", "flyway", "security", "Servlet", "DataSource").any { forbidden ->
                    type.contains(forbidden, ignoreCase = true)
                }
            }
        }
    }

    private fun invoke(main: EvidenceArchiveOperationMain, args: Array<String>): InvocationResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exitCode = main.run(
            args,
            EvidenceArchiveTextSink { stdout.append(it).append('\n') },
            EvidenceArchiveTextSink { stderr.append(it).append('\n') },
        )
        return InvocationResult(exitCode, stdout.toString(), stderr.toString())
    }

    private fun report(
        status: OperationStatus = OperationStatus.PASS,
        errorCode: String? = null,
    ): EvidenceArchiveExecutionReport = EvidenceArchiveExecutionReport(
        schemaVersion = 1,
        workPackageId = "V0-2-EVIDENCE-ARCHIVE-001",
        executionId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
        descriptorSha256 = "a".repeat(64),
        pilotManifestSha256 = "b".repeat(64),
        startedAt = Instant.parse("2026-08-27T01:00:00Z"),
        completedAt = Instant.parse("2026-08-27T01:00:01Z"),
        policyFingerprint = if (status == OperationStatus.PASS) "c".repeat(64) else null,
        capabilityCheckedAt = if (status == OperationStatus.PASS) Instant.parse("2026-08-27T00:59:59Z") else null,
        runtimeIdentity = if (status == OperationStatus.PASS) RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "d".repeat(64)) else null,
        artifacts = if (status == OperationStatus.PASS) listOf(dummyArtifact("1"), dummyArtifact("2")) else emptyList(),
        accessOwner = if (status == OperationStatus.PASS) "owner" else null,
        retentionPolicy = if (status == OperationStatus.PASS) "P730D" else null,
        immutabilityControl = if (status == OperationStatus.PASS) "COMPLIANCE" else null,
        status = status,
        errorCode = errorCode,
    )

    private fun dummyArtifact(id: String): EvidenceArchiveArtifactReport {
        val reference = EvidenceArchiveExactObjectReference(
            provider = ArchiveProvider.S3_COMPATIBLE,
            locator = "s3://company-evidence/key-$id",
            bucket = "company-evidence",
            key = "key-$id",
            versionId = "version-$id",
            sha256 = id.repeat(64),
            sizeBytes = 1,
        )
        return EvidenceArchiveArtifactReport(
            artifactId = id,
            sourceRunId = id,
            sourceCommit = id.repeat(40),
            payload = reference,
            receiptReference = reference,
        )
    }

    private data class InvocationResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val SAFE_UNEXPECTED_SUMMARY =
            "{\"artifactCount\":0,\"errorCode\":\"UNEXPECTED_FAILURE\",\"result\":\"FAIL\"}\n"
        val ARCHIVE_ARGS = arrayOf(
            "archive",
            "--work-package=work-package.json",
            "--source-root=C:\\source",
            "--output=C:\\reports\\archive.json",
        )
        val VERIFY_ARGS = arrayOf(
            "verify",
            "--work-package=work-package.json",
            "--archive-report=C:\\reports\\archive.json",
            "--recovery-root=C:\\recovery",
            "--output=C:\\reports\\recovery.json",
        )
    }
}
