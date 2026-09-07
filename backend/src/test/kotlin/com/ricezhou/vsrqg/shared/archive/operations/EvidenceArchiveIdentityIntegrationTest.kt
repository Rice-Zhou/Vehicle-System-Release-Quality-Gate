package com.ricezhou.vsrqg.shared.archive.operations

import com.fasterxml.jackson.databind.json.JsonMapper
import com.ricezhou.vsrqg.shared.adapter.archive.ExactObjectDownload
import com.ricezhou.vsrqg.shared.adapter.archive.ObjectProtectionSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3ControlSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3Gateway
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveRecoveryVerifier
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveReportWriter
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveRunner
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveSourceVerifier
import com.ricezhou.vsrqg.shared.adapter.archive.operations.OperationStatus
import com.ricezhou.vsrqg.shared.adapter.archive.operations.RecoveryFileKeyReader
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchiveEvidence
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceipt
import com.ricezhou.vsrqg.shared.application.archive.ArchiveReceiptReference
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.erdtman.jcs.JsonCanonicalizer

@Timeout(60)
class EvidenceArchiveIdentityIntegrationTest {
    @TempDir lateinit var tempDirectory: Path

    @Test
    fun `actual JVM M25 pipeline matches committed canonical samples`() {
        val generated = generate(M25)
        val fixtureRoot = repositoryRoot.resolve("backend/src/test/resources/evidence-archive/identity-m25")
        val descriptorBytes = generated.getValue("descriptor.json")
        val descriptorSha256 = sha256(descriptorBytes)
        assertThat(descriptorBytes).doesNotContain('\r'.code.toByte())
        assertThat(JsonMapper.builder().build().readTree(generated.getValue("archive-report.json"))["descriptorSha256"].textValue())
            .isEqualTo(descriptorSha256)
        assertThat(JsonMapper.builder().build().readTree(generated.getValue("recovery-report.json"))["descriptorSha256"].textValue())
            .isEqualTo(descriptorSha256)

        if (System.getenv(EXPORT_ENVIRONMENT) == "create") {
            Files.createDirectories(fixtureRoot)
            generated.forEach { (name, bytes) ->
                Files.write(fixtureRoot.resolve(name), bytes, StandardOpenOption.CREATE_NEW)
            }
        }

        generated.forEach { (name, bytes) ->
            assertThat(Files.readAllBytes(fixtureRoot.resolve(name))).describedAs(name).isEqualTo(bytes)
        }
        assertNodeAccepts(generated, M25)
    }

    @Test
    fun `actual JVM M1 pipeline remains compatible with the existing identity`() {
        val generated = generate(M1)
        assertThat(generated.getValue("descriptor.json").decodeToString()).contains("V0-2-EVIDENCE-ARCHIVE-001")
        assertThat(generated.getValue("archive-report.json").decodeToString()).contains("\"schemaVersion\":1")
        assertThat(generated.getValue("recovery-report.json").decodeToString()).contains("\"status\":\"PASS\"")
        assertNodeAccepts(generated, M1)
    }

    private fun assertNodeAccepts(generated: Map<String, ByteArray>, identity: Identity) {
        val consumerRoot = tempDirectory.resolve("node-consumer-${identity.schemaVersion}")
        Files.createDirectories(consumerRoot)
        generated.forEach { (name, bytes) -> Files.write(consumerRoot.resolve(name), bytes, StandardOpenOption.CREATE_NEW) }
        val stdoutPath = consumerRoot.resolve("node.stdout")
        val stderrPath = consumerRoot.resolve("node.stderr")
        val process = ProcessBuilder(
            "node", repositoryRoot.resolve("scripts/evidence-archive/verify-evidence.mjs").toString(),
            "--work-package=${consumerRoot.resolve("descriptor.json")}",
            "--archive-report=${consumerRoot.resolve("archive-report.json")}",
            "--recovery-report=${consumerRoot.resolve("recovery-report.json")}",
        ).directory(repositoryRoot.toFile())
            .redirectOutput(stdoutPath.toFile())
            .redirectError(stderrPath.toFile())
            .start()
        val completed = process.waitFor(20, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        assertThat(completed).describedAs("Node verifier timeout").isTrue()
        val stdout = Files.readString(stdoutPath)
        val stderr = Files.readString(stderrPath)
        assertThat(process.exitValue()).describedAs(stderr).isZero()
        assertThat(stdout).isEqualTo("{\"artifactCount\":2,\"result\":\"PASS\",\"workPackageId\":\"${identity.workPackageId}\"}\n")
        assertThat(stderr).isEmpty()
    }

    private fun generate(identity: Identity): Map<String, ByteArray> {
        val sourceRoot = tempDirectory.resolve("source-${identity.schemaVersion}")
        val recoveryRoot = tempDirectory.resolve("recovery-${identity.schemaVersion}")
        val outputRoot = tempDirectory.resolve("output-${identity.schemaVersion}")
        listOf(sourceRoot, recoveryRoot, outputRoot).forEach { Files.createDirectories(it); prepareControlledTestDirectory(it) }
        val manifestBytes = "{\"classification\":\"LOCAL_PILOT_NOT_IMMUTABLE\",\"conditionBClosed\":false,\"fixture\":\"TEST_FIXTURE\"}".toByteArray()
        Files.write(sourceRoot.resolve(MANIFEST_NAME), manifestBytes)
        val zipBytes = listOf("TEST_FIXTURE one", "TEST_FIXTURE two").mapIndexed { index, value ->
            zip(value, "evidence-${index + 1}.txt")
        }
        zipBytes.forEachIndexed { index, bytes -> Files.write(sourceRoot.resolve("TEST_FIXTURE-artifact-${index + 1}.zip"), bytes) }
        val descriptorBytes = descriptor(identity, manifestBytes, zipBytes)
        val workPackage = EvidenceArchiveSourceVerifier().verify(descriptorBytes, sourceRoot)

        val store = TestArchiveStore()
        val policy = ArchivePolicy(
            DeploymentMode.COMPANY, true, true, true, true, true, true,
            ArchiveProvider.S3_COMPATIBLE, null, null, "TEST_FIXTURE-region", TEST_BUCKET,
            "TEST_FIXTURE/identity", "TEST_FIXTURE archive owner", Duration.ofDays(730),
            Duration.ofSeconds(5), Duration.ofSeconds(5),
        )
        val facade = ArchiveEvidence(policy, EvaluateArchiveCapability(listOf(store), TimeProvider { CAPABILITY_AT }), listOf(store))
        val runnerTimes = ArrayDeque(listOf(STARTED_AT, COMPLETED_AT))
        val runner = EvidenceArchiveRunner(facade, TimeProvider { runnerTimes.removeFirst() }, { EXECUTION_ID })
        val archiveReport = runner.run(workPackage)
        assertThat(archiveReport.status).describedAs(archiveReport.errorCode).isEqualTo(OperationStatus.PASS)
        val archiveBytes = EvidenceArchiveReportWriter().canonicalBytes(archiveReport)

        val recoveryOutput = outputRoot.resolve("recovery-report.json")
        val verifier = EvidenceArchiveRecoveryVerifier(
            store, TimeProvider { RECOVERY_AT }, Duration.ofSeconds(5),
            RecoveryFileKeyReader { _, attributes -> attributes.fileKey() ?: "TEST_FIXTURE-file-key" },
        )
        val recoveryReport = verifier.recover(descriptorBytes, archiveBytes, recoveryRoot, recoveryOutput)
        assertThat(recoveryReport.status).describedAs(recoveryReport.errorCode).isEqualTo(OperationStatus.PASS)
        val recoveryBytes = Files.readAllBytes(recoveryOutput)
        val recoveryDigest = sha256(recoveryBytes)
        val markerName = "recovery-report.json.complete.$recoveryDigest"
        val marker = outputRoot.resolve(markerName)
        assertThat(Files.isRegularFile(marker)).isTrue()
        val markerBytes = Files.readAllBytes(marker)
        assertThat(markerBytes).isEmpty()
        return linkedMapOf(
            "descriptor.json" to descriptorBytes,
            "archive-report.json" to archiveBytes,
            "recovery-report.json" to recoveryBytes,
            markerName to markerBytes,
        )
    }

    private fun descriptor(identity: Identity, manifest: ByteArray, zips: List<ByteArray>): ByteArray {
        val root = JsonMapper.builder().build().createObjectNode().apply {
            put("schemaVersion", identity.schemaVersion)
            put("workPackageId", identity.workPackageId)
            put("subjectCommit", "1".repeat(40))
            put("pairedSubjectCommit", "2".repeat(40))
            putObject("pilotManifest").apply {
                put("fileName", MANIFEST_NAME); put("sha256", sha256(manifest))
                put("classification", "LOCAL_PILOT_NOT_IMMUTABLE"); put("conditionBClosed", false)
            }
            putArray("artifacts").apply {
                zips.forEachIndexed { index, bytes -> addObject().apply {
                    val number = index + 1
                    put("artifactId", "100$number"); put("artifactName", "TEST_FIXTURE-artifact-$number")
                    put("fileName", "TEST_FIXTURE-artifact-$number.zip"); put("sourceRunId", "200$number")
                    put("sourceCommit", number.toString().repeat(40)); put("sizeBytes", bytes.size); put("sha256", sha256(bytes))
                } }
            }
        }
        val json = JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(root)
        return (json.replace("\r\n", "\n") + "\n").toByteArray()
    }

    private fun zip(content: String, name: String): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            val entry = ZipEntry(name).apply { time = 0L }
            zip.putNextEntry(entry); zip.write(content.toByteArray()); zip.closeEntry()
        }
        output.toByteArray()
    }

    private class TestArchiveStore : ArchiveAdapter, S3Gateway {
        override val provider = ArchiveProvider.S3_COMPATIBLE
        private val bodies = linkedMapOf<String, ByteArray>()
        private val versions = linkedMapOf<String, String>()
        override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext) =
            listOf(CapabilityCheck("TEST_FIXTURE-capability", true, "TEST_FIXTURE verified"))

        override fun archive(command: ArchiveCommand, policy: ArchivePolicy, authorization: ArchiveAuthorization): ArchiveResult {
            val payloadBytes = Files.readAllBytes(command.source)
            val payload = store("${policy.objectPrefix}/${command.sourceArtifactId}/payload.zip", payloadBytes)
            val receipt = ArchiveReceipt(
                command.acceptanceId, command.sourceArtifactId, command.sourceRunId, command.sourceCommit,
                command.expectedSha256, payload, policy.accessOwner!!, "P730D", "COMPLIANCE",
                authorization.report.policyFingerprint, authorization.report.checkedAt, ARCHIVED_AT,
                "SHA-256", true,
            )
            val receiptBytes = canonicalReceipt(receipt)
            val receiptReference = store("${policy.objectPrefix}/${command.sourceArtifactId}/receipt.json", receiptBytes)
            return ArchiveResult(receipt, ArchiveReceiptReference(receiptReference.locator, receiptReference.versionId, receiptReference.sha256, receiptReference.sizeBytes), ARCHIVE_IDENTITY)
        }

        private fun store(key: String, bytes: ByteArray): StoredObjectRef {
            val version = "TEST_FIXTURE-version-${versions.size + 1}"
            bodies[key] = bytes.copyOf(); versions[key] = version
            return StoredObjectRef(provider, "s3://$TEST_BUCKET/$key", TEST_BUCKET, key, version, sha256(bytes), bytes.size.toLong())
        }

        override fun runtimeIdentity(timeout: Duration) = VERIFIER_IDENTITY
        override fun downloadExact(source: StoredObjectRef, maxBytes: Long, timeout: Duration) =
            ExactObjectDownload(bodies.getValue(source.key), versions.getValue(source.key), "TEST_FIXTURE-etag", bodies.getValue(source.key).size.toLong(), mapOf("sha256" to sha256(bodies.getValue(source.key))))
        override fun headProtection(source: StoredObjectRef, timeout: Duration) = ObjectProtectionSnapshot("COMPLIANCE", RETAIN_UNTIL)
        override fun controls(bucket: String, targetKey: String, resultKey: String, policyFingerprint: String, identity: RuntimeIdentityRef, utcDate: LocalDate, requiredRetainUntil: Instant, validUntil: Instant, timeout: Duration): S3ControlSnapshot = error("not used")
        override fun putFileIfAbsent(bucket: String, key: String, source: Path, sha256: String, timeout: Duration): StoredObjectRef = error("not used")
        override fun download(source: StoredObjectRef, target: Path, timeout: Duration) = error("not used")
        override fun putJsonIfAbsent(bucket: String, key: String, bytes: ByteArray, sha256: String, timeout: Duration): StoredObjectRef = error("not used")
    }

    private data class Identity(val schemaVersion: Int, val workPackageId: String)

    private companion object {
        const val EXPORT_ENVIRONMENT = "VSRQG_EXPORT_IDENTITY_M25_FIXTURES"
        const val MANIFEST_NAME = "TEST_FIXTURE-pilot-preservation-manifest.json"
        const val TEST_BUCKET = "test-fixture-evidence"
        val repositoryRoot: Path = Path.of(System.getProperty("user.dir")).resolve("..").normalize()
        val M1 = Identity(1, "V0-2-EVIDENCE-ARCHIVE-001")
        val M25 = Identity(2, "M2-5-EVIDENCE-ARCHIVE-001")
        val STARTED_AT: Instant = Instant.parse("2026-09-07T08:00:00Z")
        val CAPABILITY_AT: Instant = Instant.parse("2026-09-07T08:00:01Z")
        val ARCHIVED_AT: Instant = Instant.parse("2026-09-07T08:00:02Z")
        val COMPLETED_AT: Instant = Instant.parse("2026-09-07T08:00:03Z")
        val RECOVERY_AT: Instant = Instant.parse("2026-09-07T08:10:00Z")
        val RETAIN_UNTIL: Instant = Instant.parse("2028-09-07T08:00:02Z")
        val EXECUTION_ID: UUID = UUID.fromString("25250000-0000-4000-8000-000000000001")
        val ARCHIVE_IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "a".repeat(64))
        val VERIFIER_IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "b".repeat(64))

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun canonicalReceipt(receipt: ArchiveReceipt): ByteArray {
            val mapper = JsonMapper.builder().build()
            val root = mapper.createObjectNode().apply {
                put("acceptanceId", receipt.acceptanceId); put("sourceArtifactId", receipt.sourceArtifactId)
                put("sourceRunId", receipt.sourceRunId); put("sourceCommit", receipt.sourceCommit)
                put("sourceSha256", receipt.sourceSha256)
                putObject("payload").apply {
                    put("provider", receipt.payload.provider.name); put("locator", receipt.payload.locator)
                    put("bucket", receipt.payload.bucket); put("key", receipt.payload.key)
                    put("versionId", receipt.payload.versionId); put("sha256", receipt.payload.sha256)
                    put("sizeBytes", receipt.payload.sizeBytes)
                }
                put("accessOwner", receipt.accessOwner); put("retentionPolicy", receipt.retentionPolicy)
                put("immutabilityControl", receipt.immutabilityControl); put("policyFingerprint", receipt.policyFingerprint)
                put("capabilityCheckedAt", receipt.capabilityCheckedAt.toString()); put("archivedAt", receipt.archivedAt.toString())
                put("verifier", receipt.verifier); put("longTerm", receipt.longTerm)
            }
            return JsonCanonicalizer(mapper.writeValueAsBytes(root)).encodedUTF8
        }
    }
}
