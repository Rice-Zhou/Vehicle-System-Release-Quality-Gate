package com.ricezhou.vsrqg.shared.adapter.archive.operations

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveCapabilityConfiguration
import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveConfiguration
import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveProperties
import com.ricezhou.vsrqg.shared.adapter.archive.DeploymentProperties
import com.ricezhou.vsrqg.shared.adapter.archive.FilesystemStagingArchiveConfiguration
import com.ricezhou.vsrqg.shared.adapter.archive.S3ArchiveAdapterConfiguration
import com.ricezhou.vsrqg.shared.application.archive.ArchiveEvidence
import com.ricezhou.vsrqg.shared.application.archive.ArchiveIntegrityFailure
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.function.Supplier
import kotlin.system.exitProcess
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.AnnotationConfigApplicationContext

data class ArchiveOperationRequest(
    val workPackage: Path,
    val sourceRoot: Path,
    val output: Path,
)

data class RecoveryOperationRequest(
    val workPackage: Path,
    val archiveReport: Path,
    val recoveryRoot: Path,
    val output: Path,
)

data class EvidenceArchiveOperationSummary(
    val workPackageId: String?,
    val result: OperationStatus,
    val artifactCount: Int?,
    val errorCode: String?,
)

fun interface ArchiveOperation {
    fun archive(request: ArchiveOperationRequest): EvidenceArchiveExecutionReport
}

/** Task5 must install the production exact-version recovery implementation through this port. */
fun interface RecoveryOperation {
    fun verify(request: RecoveryOperationRequest): EvidenceArchiveOperationSummary
}

fun interface EvidenceArchiveTextSink {
    fun println(value: String)
}

class EvidenceArchiveOperationMain(
    private val archiveOperation: ArchiveOperation = NarrowArchiveOperation,
    private val recoveryOperation: RecoveryOperation? = null,
) {
    fun run(
        args: Array<String>,
        stdout: EvidenceArchiveTextSink,
        @Suppress("UNUSED_PARAMETER") stderr: EvidenceArchiveTextSink,
    ): Int {
        val invocation = parse(args) ?: return emit(
            stdout,
            EvidenceArchiveOperationSummary(null, OperationStatus.FAIL, 0, "USAGE_ERROR"),
            USAGE_EXIT,
        )
        return try {
            when (invocation) {
                is Invocation.Archive -> {
                    val report = archiveOperation.archive(invocation.request)
                    emit(stdout, report.summary(), FAILURE_EXIT)
                }
                is Invocation.Verify -> {
                    val operation = recoveryOperation ?: return emit(
                        stdout,
                        EvidenceArchiveOperationSummary(null, OperationStatus.FAIL, 0, "VERIFICATION_UNAVAILABLE"),
                        FAILURE_EXIT,
                    )
                    val summary = operation.verify(invocation.request)
                    emit(stdout, summary, FAILURE_EXIT)
                }
            }
        } catch (failure: Exception) {
            emit(
                stdout,
                EvidenceArchiveOperationSummary(null, OperationStatus.FAIL, 0, stableFailureCode(failure)),
                FAILURE_EXIT,
            )
        }
    }

    private fun parse(args: Array<String>): Invocation? {
        if (args.isEmpty()) return null
        val mode = args.first()
        val expected = when (mode) {
            ARCHIVE_MODE -> ARCHIVE_KEYS
            VERIFY_MODE -> VERIFY_KEYS
            else -> return null
        }
        if (args.size != expected.size + 1) return null
        val values = linkedMapOf<String, String>()
        for (argument in args.drop(1)) {
            if (!argument.startsWith("--")) return null
            val separator = argument.indexOf('=')
            if (separator <= 2 || separator == argument.lastIndex) return null
            val key = argument.substring(2, separator)
            val value = argument.substring(separator + 1)
            if (key !in expected || values.putIfAbsent(key, value) != null) return null
        }
        if (values.keys != expected) return null
        return try {
            when (mode) {
                ARCHIVE_MODE -> Invocation.Archive(
                    ArchiveOperationRequest(
                        workPackage = Path.of(values.getValue(WORK_PACKAGE)),
                        sourceRoot = Path.of(values.getValue(SOURCE_ROOT)),
                        output = Path.of(values.getValue(OUTPUT)),
                    ),
                )
                else -> Invocation.Verify(
                    RecoveryOperationRequest(
                        workPackage = Path.of(values.getValue(WORK_PACKAGE)),
                        archiveReport = Path.of(values.getValue(ARCHIVE_REPORT)),
                        recoveryRoot = Path.of(values.getValue(RECOVERY_ROOT)),
                        output = Path.of(values.getValue(OUTPUT)),
                    ),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun emit(
        stdout: EvidenceArchiveTextSink,
        summary: EvidenceArchiveOperationSummary,
        failureExitCode: Int,
    ): Int {
        val safe = safeSummary(summary)
        stdout.println(canonicalSummary(safe))
        return if (safe.result == OperationStatus.PASS) SUCCESS_EXIT else failureExitCode
    }

    private fun safeSummary(summary: EvidenceArchiveOperationSummary): EvidenceArchiveOperationSummary {
        val count = summary.artifactCount
        val workPackageIdIsSafe = summary.workPackageId == null || summary.workPackageId == WORK_PACKAGE_ID
        val combinationIsValid = when (summary.result) {
            OperationStatus.PASS ->
                summary.workPackageId == WORK_PACKAGE_ID && count == REQUIRED_ARTIFACT_COUNT && summary.errorCode == null
            OperationStatus.FAIL ->
                workPackageIdIsSafe && count != null && count in 0..REQUIRED_ARTIFACT_COUNT &&
                    summary.errorCode != null && EvidenceArchiveOperationErrorCodes.isAllowed(summary.errorCode)
        }
        return if (combinationIsValid) {
            summary
        } else {
            EvidenceArchiveOperationSummary(
                workPackageId = null,
                result = OperationStatus.FAIL,
                artifactCount = 0,
                errorCode = EvidenceArchiveOperationErrorCodes.UNEXPECTED_FAILURE,
            )
        }
    }

    private fun canonicalSummary(summary: EvidenceArchiveOperationSummary): String {
        val mapper = SUMMARY_MAPPER
        val root = mapper.createObjectNode()
        summary.workPackageId?.let { root.put("workPackageId", it) }
        root.put("result", summary.result.name)
        summary.artifactCount?.let { root.put("artifactCount", it) }
        summary.errorCode?.let { root.put("errorCode", it) }
        return JsonCanonicalizer(mapper.writeValueAsBytes(root)).encodedString
    }

    private fun stableFailureCode(failure: Exception): String = when (failure) {
        is EvidenceArchiveOperationFailure -> EvidenceArchiveOperationErrorCodes.sanitize(failure.code)
        is EvidenceArchiveConfigurationFailure -> "CONFIGURATION_INVALID"
        is EvidenceArchiveInputFailure -> "ARCHIVE_INPUT_FAILURE"
        is EvidenceArchiveVerificationFailure -> EvidenceArchiveOperationErrorCodes.sanitize(failure.code)
        is ArchiveIntegrityFailure -> "ARCHIVE_INTEGRITY_FAILURE"
        is ArchiveUnavailable -> "ARCHIVE_UNAVAILABLE"
        else -> EvidenceArchiveOperationErrorCodes.UNEXPECTED_FAILURE
    }

    private fun EvidenceArchiveExecutionReport.summary(): EvidenceArchiveOperationSummary =
        EvidenceArchiveOperationSummary(workPackageId, status, artifacts.size, errorCode)

    private sealed interface Invocation {
        data class Archive(val request: ArchiveOperationRequest) : Invocation
        data class Verify(val request: RecoveryOperationRequest) : Invocation
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val exitCode = EvidenceArchiveOperationMain().run(
                args,
                EvidenceArchiveTextSink(System.out::println),
                EvidenceArchiveTextSink(System.err::println),
            )
            exitProcess(exitCode)
        }

        private const val SUCCESS_EXIT = 0
        private const val FAILURE_EXIT = 1
        private const val USAGE_EXIT = 2
        private const val ARCHIVE_MODE = "archive"
        private const val VERIFY_MODE = "verify"
        private const val WORK_PACKAGE = "work-package"
        private const val SOURCE_ROOT = "source-root"
        private const val ARCHIVE_REPORT = "archive-report"
        private const val RECOVERY_ROOT = "recovery-root"
        private const val OUTPUT = "output"
        private const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        private const val REQUIRED_ARTIFACT_COUNT = 2
        private val ARCHIVE_KEYS = linkedSetOf(WORK_PACKAGE, SOURCE_ROOT, OUTPUT)
        private val VERIFY_KEYS = linkedSetOf(WORK_PACKAGE, ARCHIVE_REPORT, RECOVERY_ROOT, OUTPUT)
        private val SUMMARY_MAPPER = jacksonObjectMapper()
    }
}

internal object NarrowArchiveOperation : ArchiveOperation {
    override fun archive(request: ArchiveOperationRequest): EvidenceArchiveExecutionReport {
        EvidenceArchiveNarrowContext.open().use { context ->
            val runner = context.getBean(EvidenceArchiveRunner::class.java)
            runner.validateReportOutput(request.output)
            val descriptor = readDescriptor(request.workPackage)
            val workPackage = context.getBean(EvidenceArchiveSourceVerifier::class.java)
                .verify(descriptor, request.sourceRoot)
            val report = runner.run(workPackage)
            runner.writeReport(report, request.output)
            return report
        }
    }

    private fun readDescriptor(path: Path): ByteArray {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw EvidenceArchiveOperationFailure("WORK_PACKAGE_READ_FAILED")
        }
        return try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1)
                if (bytes.size > MAX_DESCRIPTOR_BYTES) {
                    throw EvidenceArchiveOperationFailure("WORK_PACKAGE_READ_FAILED")
                }
                bytes
            }
        } catch (failure: EvidenceArchiveOperationFailure) {
            throw failure
        } catch (_: IOException) {
            throw EvidenceArchiveOperationFailure("WORK_PACKAGE_READ_FAILED")
        } catch (_: SecurityException) {
            throw EvidenceArchiveOperationFailure("WORK_PACKAGE_READ_FAILED")
        } catch (_: UnsupportedOperationException) {
            throw EvidenceArchiveOperationFailure("WORK_PACKAGE_READ_FAILED")
        }
    }

    private const val MAX_DESCRIPTOR_BYTES = 1_048_576
}

internal object EvidenceArchiveNarrowContext {
    fun open(): AnnotationConfigApplicationContext {
        val context = AnnotationConfigApplicationContext()
        try {
            val binder = Binder.get(context.environment)
            val deployment = binder.bind(
                "vsrqg.deployment",
                Bindable.of(DeploymentProperties::class.java),
            ).orElse(DeploymentProperties())
            val archive = binder.bind(
                "vsrqg.evidence.archive",
                Bindable.of(ArchiveProperties::class.java),
            ).orElse(ArchiveProperties())
            context.registerBean(DeploymentProperties::class.java, Supplier { deployment })
            context.registerBean(ArchiveProperties::class.java, Supplier { archive })
            context.registerBean(ObjectMapper::class.java, Supplier { jacksonObjectMapper().findAndRegisterModules() })
            context.registerBean(TimeProvider::class.java, Supplier { TimeProvider(Instant::now) })
            context.registerBean(EvidenceArchiveSourceVerifier::class.java, Supplier { EvidenceArchiveSourceVerifier() })
            context.registerBean(EvidenceArchiveRunner::class.java, Supplier {
                EvidenceArchiveRunner(
                    context.getBean(ArchiveEvidence::class.java),
                    context.getBean(TimeProvider::class.java),
                )
            })
            context.register(
                ArchiveConfiguration::class.java,
                ArchiveCapabilityConfiguration::class.java,
                FilesystemStagingArchiveConfiguration::class.java,
                S3ArchiveAdapterConfiguration::class.java,
            )
            context.refresh()
            return context
        } catch (failure: Exception) {
            try {
                context.close()
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
            throw EvidenceArchiveConfigurationFailure()
        }
    }
}

internal class EvidenceArchiveConfigurationFailure : IllegalStateException("CONFIGURATION_INVALID")
