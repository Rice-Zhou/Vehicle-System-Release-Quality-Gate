package com.ricezhou.vsrqg.shared.archive.operations

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveInputFailure
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveSourceVerifier
import com.ricezhou.vsrqg.shared.adapter.archive.operations.OperationStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvidenceArchiveSourceVerifierTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val objectMapper = jacksonObjectMapper()
    private val verifier = EvidenceArchiveSourceVerifier()
    private lateinit var sourceRoot: Path

    @BeforeEach
    fun createValidSources() {
        sourceRoot = Files.createDirectory(tempDirectory.resolve("source"))
        Files.write(sourceRoot.resolve(MANIFEST_FILE_NAME), validManifestBytes())
        Files.write(sourceRoot.resolve(FIRST_FILE_NAME), zipBytes("first evidence"))
        Files.write(sourceRoot.resolve(SECOND_FILE_NAME), zipBytes("second evidence"))
    }

    @Test
    fun `rejects a source root that is relative or not normalized`() {
        assertFailure(
            expectedCode = "SOURCE_ROOT_INVALID:sourceRoot",
            descriptorBytes = descriptorBytes(),
            root = Path.of("relative-source"),
        )
        assertFailure(
            expectedCode = "SOURCE_ROOT_INVALID:sourceRoot",
            descriptorBytes = descriptorBytes(),
            root = sourceRoot.resolve(".."),
        )
        val rootFile = tempDirectory.resolve("root-file")
        Files.write(rootFile, byteArrayOf(1))
        assertFailure("SOURCE_ROOT_INVALID:sourceRoot", descriptorBytes(), rootFile)
        assertFailure("SOURCE_ROOT_INVALID:sourceRoot", descriptorBytes(), tempDirectory.resolve("missing"))
    }

    @Test
    fun `rejects a source root symbolic link`() {
        val link = tempDirectory.resolve("source-link")
        createSymbolicLinkOrJunction(link, sourceRoot)

        assertFailure("SOURCE_ROOT_INVALID:sourceRoot", descriptorBytes(), link)
    }

    @Test
    fun `strictly validates the fixed descriptor contract`() {
        assertFailure("DESCRIPTOR_INVALID:descriptor", byteArrayOf(), sourceRoot)
        assertDescriptorFailure("DESCRIPTOR_INVALID:schemaVersion") { it.put("schemaVersion", 2) }
        assertDescriptorFailure("DESCRIPTOR_INVALID:workPackageId") { it.put("workPackageId", "OTHER") }
        assertDescriptorFailure("DESCRIPTOR_INVALID:subjectCommit") { it.put("subjectCommit", "A".repeat(40)) }
        assertDescriptorFailure("DESCRIPTOR_INVALID:pairedSubjectCommit") {
            it.put("pairedSubjectCommit", "a".repeat(39))
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:pilotManifest.sha256") {
            it.withObject("pilotManifest").put("sha256", "A".repeat(64))
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:pilotManifest.classification") {
            it.withObject("pilotManifest").put("classification", "EXTERNAL_VERIFIED")
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:pilotManifest.conditionBClosed") {
            it.withObject("pilotManifest").put("conditionBClosed", true)
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts") {
            it.withArray("artifacts").remove(1)
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].artifactId") {
            (it.withArray("artifacts")[0] as ObjectNode).put("artifactId", "0")
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].artifactName") {
            (it.withArray("artifacts")[0] as ObjectNode).put("artifactName", "nested/name")
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].sourceRunId") {
            (it.withArray("artifacts")[0] as ObjectNode).put("sourceRunId", "01")
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].sourceCommit") {
            (it.withArray("artifacts")[0] as ObjectNode).put("sourceCommit", "b".repeat(39))
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].sizeBytes") {
            (it.withArray("artifacts")[0] as ObjectNode).put("sizeBytes", 0)
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].sha256") {
            (it.withArray("artifacts")[0] as ObjectNode).put("sha256", "b".repeat(63))
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:unexpected") { it.put("unexpected", true) }
        assertDescriptorFailure("DESCRIPTOR_INVALID:pilotManifest.unexpected") {
            it.withObject("pilotManifest").put("unexpected", true)
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].unexpected") {
            (it.withArray("artifacts")[0] as ObjectNode).put("unexpected", true)
        }
    }

    @Test
    fun `rejects artifact and manifest filenames that are not safe basenames`() {
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].fileName") {
            (it.withArray("artifacts")[0] as ObjectNode).put("fileName", "../outside.zip")
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:artifacts[0].fileName") {
            (it.withArray("artifacts")[0] as ObjectNode).put("fileName", "nested/artifact.zip")
        }
        assertDescriptorFailure("DESCRIPTOR_INVALID:pilotManifest.fileName") {
            it.withObject("pilotManifest").put("fileName", "..\\manifest.json")
        }
    }

    @Test
    fun `rejects duplicate artifact identifiers and conflicting source filenames`() {
        assertDescriptorFailure("DESCRIPTOR_CONFLICT:artifacts.artifactId") { descriptor ->
            val artifacts = descriptor.withArray("artifacts")
            (artifacts[1] as ObjectNode).put("artifactId", artifacts[0]["artifactId"].textValue())
        }
        assertDescriptorFailure("DESCRIPTOR_CONFLICT:artifacts.fileName") { descriptor ->
            val artifacts = descriptor.withArray("artifacts")
            (artifacts[1] as ObjectNode).put("fileName", artifacts[0]["fileName"].textValue())
        }
        assertDescriptorFailure("DESCRIPTOR_CONFLICT:artifacts.fileName") { descriptor ->
            (descriptor.withArray("artifacts")[0] as ObjectNode)
                .put("fileName", descriptor["pilotManifest"]["fileName"].textValue())
        }
    }

    @Test
    fun `rejects a missing artifact`() {
        val descriptorBytes = descriptorBytes()
        Files.delete(sourceRoot.resolve(FIRST_FILE_NAME))

        assertFailure("SOURCE_FILE_INVALID:artifacts[0].fileName", descriptorBytes, sourceRoot)
    }

    @Test
    fun `rejects an artifact that is not a regular file`() {
        val descriptorBytes = descriptorBytes()
        Files.delete(sourceRoot.resolve(FIRST_FILE_NAME))
        Files.createDirectory(sourceRoot.resolve(FIRST_FILE_NAME))

        assertFailure("SOURCE_FILE_INVALID:artifacts[0].fileName", descriptorBytes, sourceRoot)
    }

    @Test
    fun `rejects an artifact symbolic link`() {
        val descriptorBytes = descriptorBytes()
        val externalSource = Files.createDirectory(tempDirectory.resolve("external-artifact"))
        Files.delete(sourceRoot.resolve(FIRST_FILE_NAME))
        createSymbolicLinkOrJunction(sourceRoot.resolve(FIRST_FILE_NAME), externalSource)

        assertFailure("SOURCE_FILE_INVALID:artifacts[0].fileName", descriptorBytes, sourceRoot)
    }

    @Test
    fun `rejects artifact size and digest mismatches`() {
        assertDescriptorFailure("SOURCE_SIZE_MISMATCH:artifacts[0].sizeBytes") {
            val artifact = it.withArray("artifacts")[0] as ObjectNode
            artifact.put("sizeBytes", artifact["sizeBytes"].longValue() + 1)
        }
        assertDescriptorFailure("SOURCE_DIGEST_MISMATCH:artifacts[0].sha256") {
            (it.withArray("artifacts")[0] as ObjectNode).put("sha256", "f".repeat(64))
        }
    }

    @Test
    fun `rejects an artifact whose bytes are not a valid ZIP`() {
        Files.write(sourceRoot.resolve(FIRST_FILE_NAME), "not a zip".toByteArray())
        assertFailure("SOURCE_ZIP_INVALID:artifacts[0].fileName", descriptorBytes(), sourceRoot)

        val completeZip = zipBytes("truncated central directory")
        Files.write(sourceRoot.resolve(FIRST_FILE_NAME), completeZip.copyOf(completeZip.size - 10))
        assertFailure("SOURCE_ZIP_INVALID:artifacts[0].fileName", descriptorBytes(), sourceRoot)
    }

    @Test
    fun `rejects a ZIP whose central directory file header is corrupted`() {
        val corruptedZip = zipBytes("corrupted central directory").copyOf()
        val centralDirectoryOffset = findSignature(corruptedZip, 0x02014b50)
        check(centralDirectoryOffset >= 0) { "central directory record missing from test ZIP" }
        corruptedZip[centralDirectoryOffset] = 0
        Files.write(sourceRoot.resolve(FIRST_FILE_NAME), corruptedZip)

        assertFailure("SOURCE_ZIP_INVALID:artifacts[0].fileName", descriptorBytes(), sourceRoot)
    }

    @Test
    fun `rejects inconsistent or unsupported ZIP32 metadata`() {
        assertInvalidZip(mutateZip("multi disk") { bytes ->
            putUnsignedShort(bytes, endOfCentralDirectoryOffset(bytes) + 4, 1)
        })
        assertInvalidZip(mutateZip("ZIP64 sentinel") { bytes ->
            val endOffset = endOfCentralDirectoryOffset(bytes)
            putUnsignedShort(bytes, endOffset + 8, 0xffff)
            putUnsignedShort(bytes, endOffset + 10, 0xffff)
        })
        assertInvalidZip(mutateZip("entry count mismatch") { bytes ->
            val endOffset = endOfCentralDirectoryOffset(bytes)
            putUnsignedShort(bytes, endOffset + 8, 2)
            putUnsignedShort(bytes, endOffset + 10, 2)
        })
        assertInvalidZip(mutateZip("central directory boundary") { bytes ->
            putUnsignedInt(bytes, endOfCentralDirectoryOffset(bytes) + 16, 1)
        })
        assertInvalidZip(mutateZip("central record boundary") { bytes ->
            putUnsignedShort(bytes, centralDirectoryOffset(bytes) + 28, 0xffff)
        })
        assertInvalidZip(mutateZip("local header offset") { bytes ->
            putUnsignedInt(
                bytes,
                centralDirectoryOffset(bytes) + 42,
                centralDirectoryOffset(bytes).toLong(),
            )
        })
        assertInvalidZip(mutateZip("central ZIP64 size") { bytes ->
            putUnsignedInt(bytes, centralDirectoryOffset(bytes) + 20, 0xffffffffL)
        })
        assertInvalidZip(mutateZip("encrypted") { bytes ->
            putUnsignedShort(bytes, 6, unsignedShort(bytes, 6) or 1)
            val centralOffset = centralDirectoryOffset(bytes)
            putUnsignedShort(bytes, centralOffset + 8, unsignedShort(bytes, centralOffset + 8) or 1)
        })
    }

    @Test
    fun `rejects unsafe ZIP entry names`() {
        val unsafeNames = listOf(
            "",
            "/absolute.txt",
            "C:\\absolute.txt",
            "../escape.txt",
            "nested/../../escape.txt",
            "nul\u0000name",
        )
        for (entryName in unsafeNames) {
            assertInvalidZip(zipBytes("unsafe name", entryName = entryName))
        }
    }

    @Test
    fun `rejects ZIP entry count and decompression size bombs`() {
        assertInvalidZip(zipBytes("too many entries", entryCount = 1025))

        assertInvalidZip(mutateZip("oversized entry") { bytes ->
            putUnsignedInt(bytes, centralDirectoryOffset(bytes) + 24, 134_217_729L)
        })

        val excessiveTotal = zipBytes("excessive total", entryCount = 5)
        for (centralOffset in findSignatures(excessiveTotal, 0x02014b50)) {
            putUnsignedInt(excessiveTotal, centralOffset + 24, 110_000_000L)
        }
        assertInvalidZip(excessiveTotal)
    }

    @Test
    fun `rejects a missing non regular or symbolic manifest`() {
        val descriptorBytes = descriptorBytes()
        Files.delete(sourceRoot.resolve(MANIFEST_FILE_NAME))
        assertFailure("SOURCE_FILE_INVALID:pilotManifest.fileName", descriptorBytes, sourceRoot)

        Files.createDirectory(sourceRoot.resolve(MANIFEST_FILE_NAME))
        assertFailure("SOURCE_FILE_INVALID:pilotManifest.fileName", descriptorBytes, sourceRoot)

        Files.delete(sourceRoot.resolve(MANIFEST_FILE_NAME))
        val externalManifest = Files.createDirectory(tempDirectory.resolve("external-manifest"))
        createSymbolicLinkOrJunction(sourceRoot.resolve(MANIFEST_FILE_NAME), externalManifest)
        assertFailure("SOURCE_FILE_INVALID:pilotManifest.fileName", descriptorBytes, sourceRoot)
    }

    @Test
    fun `rejects a pilot manifest digest mismatch`() {
        assertDescriptorFailure("SOURCE_DIGEST_MISMATCH:pilotManifest.sha256") {
            it.withObject("pilotManifest").put("sha256", "f".repeat(64))
        }
    }

    @Test
    fun `rejects pilot manifest facts that disagree with the descriptor`() {
        Files.write(
            sourceRoot.resolve(MANIFEST_FILE_NAME),
            validManifestBytes(classification = "EXTERNAL_VERIFIED"),
        )
        assertFailure("PILOT_MANIFEST_INVALID:classification", descriptorBytes(), sourceRoot)

        Files.write(
            sourceRoot.resolve(MANIFEST_FILE_NAME),
            validManifestBytes(conditionBClosed = true),
        )
        assertFailure("PILOT_MANIFEST_INVALID:conditionBClosed", descriptorBytes(), sourceRoot)
    }

    @Test
    fun `returns two verified ZIP sources in descriptor order`() {
        val descriptorBytes = descriptorBytes()

        val result = verifier.verify(descriptorBytes, sourceRoot)

        assertThat(OperationStatus.entries.map { it.name }).containsExactly("PASS", "FAIL")
        assertThat(result.workPackageId).isEqualTo(WORK_PACKAGE_ID)
        assertThat(result.descriptorSha256).isEqualTo(sha256(descriptorBytes))
        assertThat(result.pilotManifestSha256)
            .isEqualTo(sha256(Files.readAllBytes(sourceRoot.resolve(MANIFEST_FILE_NAME))))
        assertThat(result.artifacts.map { it.artifactId }).containsExactly(FIRST_ARTIFACT_ID, SECOND_ARTIFACT_ID)
        assertThat(result.artifacts.map { it.sourceRunId }).containsExactly("33033752846", "33033740162")
        assertThat(result.artifacts.map { it.sourceCommit })
            .containsExactly("892fb23ce75e7f74a05c1b5e304fccace70ee8d3", "8687d49c9566030bb0829752dbe5dda45af02f4b")
        assertThat(result.artifacts.map { it.path })
            .containsExactly(sourceRoot.resolve(FIRST_FILE_NAME), sourceRoot.resolve(SECOND_FILE_NAME))
        assertThat(result.artifacts.map { it.sizeBytes })
            .containsExactly(
                Files.size(sourceRoot.resolve(FIRST_FILE_NAME)),
                Files.size(sourceRoot.resolve(SECOND_FILE_NAME)),
            )
        assertThat(result.artifacts.map { it.sha256 })
            .containsExactly(
                sha256(Files.readAllBytes(sourceRoot.resolve(FIRST_FILE_NAME))),
                sha256(Files.readAllBytes(sourceRoot.resolve(SECOND_FILE_NAME))),
            )
    }

    private fun assertDescriptorFailure(
        expectedCode: String,
        mutate: (ObjectNode) -> Unit,
    ) {
        val descriptor = descriptorNode()
        mutate(descriptor)
        assertFailure(expectedCode, objectMapper.writeValueAsBytes(descriptor), sourceRoot)
    }

    private fun assertFailure(
        expectedCode: String,
        descriptorBytes: ByteArray,
        root: Path,
    ) {
        assertThatThrownBy { verifier.verify(descriptorBytes, root) }
            .isInstanceOf(EvidenceArchiveInputFailure::class.java)
            .hasMessage(expectedCode)
            .extracting("code")
            .isEqualTo(expectedCode)
    }

    private fun descriptorBytes(): ByteArray = objectMapper.writeValueAsBytes(descriptorNode())

    private fun descriptorNode(): ObjectNode {
        val firstBytes = Files.readAllBytes(sourceRoot.resolve(FIRST_FILE_NAME))
        val secondBytes = Files.readAllBytes(sourceRoot.resolve(SECOND_FILE_NAME))
        val manifestBytes = Files.readAllBytes(sourceRoot.resolve(MANIFEST_FILE_NAME))
        return objectMapper.createObjectNode().apply {
            put("schemaVersion", 1)
            put("workPackageId", WORK_PACKAGE_ID)
            put("subjectCommit", "e3576582b08c154189eb9e7f2796f39280cdb8a5")
            put("pairedSubjectCommit", "6ef2cd2fb234737fad78e96cff4172ef8f92fc45")
            putObject("pilotManifest").apply {
                put("fileName", MANIFEST_FILE_NAME)
                put("sha256", sha256(manifestBytes))
                put("classification", "LOCAL_PILOT_NOT_IMMUTABLE")
                put("conditionBClosed", false)
            }
            putArray("artifacts").apply {
                addObject().apply {
                    put("artifactId", FIRST_ARTIFACT_ID)
                    put("artifactName", "m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3")
                    put("fileName", FIRST_FILE_NAME)
                    put("sourceRunId", "33033752846")
                    put("sourceCommit", "892fb23ce75e7f74a05c1b5e304fccace70ee8d3")
                    put("sizeBytes", firstBytes.size)
                    put("sha256", sha256(firstBytes))
                }
                addObject().apply {
                    put("artifactId", SECOND_ARTIFACT_ID)
                    put("artifactName", "m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b")
                    put("fileName", SECOND_FILE_NAME)
                    put("sourceRunId", "33033740162")
                    put("sourceCommit", "8687d49c9566030bb0829752dbe5dda45af02f4b")
                    put("sizeBytes", secondBytes.size)
                    put("sha256", sha256(secondBytes))
                }
            }
        }
    }

    private fun validManifestBytes(
        classification: String = "LOCAL_PILOT_NOT_IMMUTABLE",
        conditionBClosed: Boolean = false,
    ): ByteArray = objectMapper.writeValueAsBytes(
        mapOf(
            "classification" to classification,
            "conditionBClosed" to conditionBClosed,
        ),
    )

    private fun zipBytes(
        content: String,
        entryName: String = "evidence.txt",
        entryCount: Int = 1,
    ): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            repeat(entryCount) { index ->
                val name = if (entryCount == 1) entryName else "evidence-$index.txt"
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun mutateZip(content: String, mutate: (ByteArray) -> Unit): ByteArray =
        zipBytes(content).apply(mutate)

    private fun assertInvalidZip(bytes: ByteArray) {
        Files.write(sourceRoot.resolve(FIRST_FILE_NAME), bytes)
        assertFailure("SOURCE_ZIP_INVALID:artifacts[0].fileName", descriptorBytes(), sourceRoot)
    }

    private fun createSymbolicLinkOrJunction(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: java.nio.file.FileSystemException) {
            if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                throw failure
            }
            val process = ProcessBuilder(
                "cmd",
                "/c",
                "mklink",
                "/J",
                link.toString(),
                target.toString(),
            ).redirectErrorStream(true).start()
            process.inputStream.use { it.readAllBytes() }
            check(process.waitFor() == 0) { "junction creation failed" }
        }
    }

    private fun findSignature(bytes: ByteArray, signature: Int): Int {
        for (offset in 0..bytes.size - Int.SIZE_BYTES) {
            val candidate = (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
            if (candidate == signature) {
                return offset
            }
        }
        return -1
    }

    private fun findSignatures(bytes: ByteArray, signature: Int): List<Int> = buildList {
        var searchFrom = 0
        while (searchFrom <= bytes.size - Int.SIZE_BYTES) {
            val found = findSignature(bytes.copyOfRange(searchFrom, bytes.size), signature)
            if (found < 0) {
                break
            }
            val absoluteOffset = searchFrom + found
            add(absoluteOffset)
            searchFrom = absoluteOffset + Int.SIZE_BYTES
        }
    }

    private fun endOfCentralDirectoryOffset(bytes: ByteArray): Int {
        val offset = findSignature(bytes, 0x06054b50)
        check(offset >= 0) { "end of central directory missing from test ZIP" }
        return offset
    }

    private fun centralDirectoryOffset(bytes: ByteArray): Int =
        unsignedInt(bytes, endOfCentralDirectoryOffset(bytes) + 16).toInt()

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun putUnsignedShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putUnsignedInt(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val MANIFEST_FILE_NAME = "pilot-preservation-manifest.json"
        const val FIRST_ARTIFACT_ID = "9631253528"
        const val SECOND_ARTIFACT_ID = "9631250285"
        const val FIRST_FILE_NAME = "m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3.zip"
        const val SECOND_FILE_NAME = "m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b.zip"
    }
}
