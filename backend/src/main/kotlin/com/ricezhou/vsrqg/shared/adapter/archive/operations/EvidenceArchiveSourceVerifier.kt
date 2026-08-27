package com.ricezhou.vsrqg.shared.adapter.archive.operations

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class EvidenceArchiveSourceVerifier {
    fun verify(
        descriptorBytes: ByteArray,
        sourceRoot: Path,
    ): VerifiedEvidenceArchiveWorkPackage {
        val descriptorSha256 = sha256(descriptorBytes)
        val descriptor = parseDescriptor(descriptorBytes)
        val verifiedRoot = verifySourceRoot(sourceRoot)
        val manifestBytes = readSource(
            sourceRoot = verifiedRoot,
            fileName = descriptor.pilotManifest.fileName,
            field = "pilotManifest.fileName",
        )
        val pilotManifestSha256 = sha256(manifestBytes)
        if (pilotManifestSha256 != descriptor.pilotManifest.sha256) {
            fail("SOURCE_DIGEST_MISMATCH", "pilotManifest.sha256")
        }
        verifyPilotManifest(manifestBytes, descriptor.pilotManifest)

        val verifiedArtifacts = descriptor.artifacts.mapIndexed { index, artifact ->
            val prefix = "artifacts[$index]"
            val bytes = readSource(
                sourceRoot = verifiedRoot,
                fileName = artifact.fileName,
                field = "$prefix.fileName",
            )
            if (bytes.size.toLong() != artifact.sizeBytes) {
                fail("SOURCE_SIZE_MISMATCH", "$prefix.sizeBytes")
            }
            val digest = sha256(bytes)
            if (digest != artifact.sha256) {
                fail("SOURCE_DIGEST_MISMATCH", "$prefix.sha256")
            }
            if (!isValidZip(bytes)) {
                fail("SOURCE_ZIP_INVALID", "$prefix.fileName")
            }
            VerifiedArchiveSource(
                artifactId = artifact.artifactId,
                sourceRunId = artifact.sourceRunId,
                sourceCommit = artifact.sourceCommit,
                path = verifiedRoot.resolve(artifact.fileName),
                sizeBytes = bytes.size.toLong(),
                sha256 = digest,
            )
        }

        return VerifiedEvidenceArchiveWorkPackage(
            workPackageId = descriptor.workPackageId,
            descriptorSha256 = descriptorSha256,
            pilotManifestSha256 = pilotManifestSha256,
            artifacts = Collections.unmodifiableList(verifiedArtifacts),
        )
    }

    private fun parseDescriptor(bytes: ByteArray): WorkPackageDescriptor {
        val root = try {
            jsonMapper.readTree(bytes)
        } catch (_: JacksonException) {
            fail("DESCRIPTOR_INVALID", "descriptor")
        }
        requireObject(root, "descriptor")
        rejectUnknownFields(root, ROOT_FIELDS, "")
        requireExactInteger(root, "schemaVersion", 1, "schemaVersion")
        val workPackageId = requireString(root, "workPackageId", "workPackageId")
        if (workPackageId != WORK_PACKAGE_ID) {
            fail("DESCRIPTOR_INVALID", "workPackageId")
        }
        requirePattern(root, "subjectCommit", COMMIT_PATTERN, "subjectCommit")
        requirePattern(root, "pairedSubjectCommit", COMMIT_PATTERN, "pairedSubjectCommit")
        val pilotManifest = parsePilotManifest(requireField(root, "pilotManifest", "pilotManifest"))
        val artifactsNode = requireField(root, "artifacts", "artifacts")
        if (!artifactsNode.isArray || artifactsNode.size() != ARTIFACT_COUNT) {
            fail("DESCRIPTOR_INVALID", "artifacts")
        }
        val artifacts = artifactsNode.mapIndexed { index, node -> parseArtifact(node, index) }
        if (artifacts.map { it.artifactId }.toSet().size != artifacts.size) {
            fail("DESCRIPTOR_CONFLICT", "artifacts.artifactId")
        }
        val artifactFileNames = artifacts.map { it.fileName }
        if (artifactFileNames.toSet().size != artifactFileNames.size ||
            descriptorFileNames(pilotManifest, artifacts).toSet().size != ARTIFACT_COUNT + 1
        ) {
            fail("DESCRIPTOR_CONFLICT", "artifacts.fileName")
        }
        return WorkPackageDescriptor(
            workPackageId = workPackageId,
            pilotManifest = pilotManifest,
            artifacts = Collections.unmodifiableList(artifacts),
        )
    }

    private fun parsePilotManifest(node: JsonNode): PilotManifestDescriptor {
        val prefix = "pilotManifest"
        requireObject(node, prefix)
        rejectUnknownFields(node, PILOT_MANIFEST_FIELDS, "$prefix.")
        val fileName = requireSafeFileName(node, "fileName", "$prefix.fileName")
        val digest = requirePattern(node, "sha256", SHA256_PATTERN, "$prefix.sha256")
        val classification = requireString(node, "classification", "$prefix.classification")
        if (classification != PILOT_CLASSIFICATION) {
            fail("DESCRIPTOR_INVALID", "$prefix.classification")
        }
        val conditionBClosed = requireBoolean(node, "conditionBClosed", "$prefix.conditionBClosed")
        if (conditionBClosed) {
            fail("DESCRIPTOR_INVALID", "$prefix.conditionBClosed")
        }
        return PilotManifestDescriptor(
            fileName = fileName,
            sha256 = digest,
            classification = classification,
            conditionBClosed = conditionBClosed,
        )
    }

    private fun parseArtifact(node: JsonNode, index: Int): ArtifactDescriptor {
        val prefix = "artifacts[$index]"
        requireObject(node, prefix)
        rejectUnknownFields(node, ARTIFACT_FIELDS, "$prefix.")
        val artifactName = requireString(node, "artifactName", "$prefix.artifactName")
        if (artifactName.length !in 1..MAX_NAME_LENGTH || !ARTIFACT_NAME_PATTERN.matches(artifactName)) {
            fail("DESCRIPTOR_INVALID", "$prefix.artifactName")
        }
        return ArtifactDescriptor(
            artifactId = requirePattern(node, "artifactId", DECIMAL_ID_PATTERN, "$prefix.artifactId"),
            artifactName = artifactName,
            fileName = requireSafeFileName(node, "fileName", "$prefix.fileName"),
            sourceRunId = requirePattern(node, "sourceRunId", DECIMAL_ID_PATTERN, "$prefix.sourceRunId"),
            sourceCommit = requirePattern(node, "sourceCommit", COMMIT_PATTERN, "$prefix.sourceCommit"),
            sizeBytes = requirePositiveLong(node, "sizeBytes", "$prefix.sizeBytes"),
            sha256 = requirePattern(node, "sha256", SHA256_PATTERN, "$prefix.sha256"),
        )
    }

    private fun verifySourceRoot(sourceRoot: Path): Path {
        if (!sourceRoot.isAbsolute || sourceRoot.normalize() != sourceRoot || Files.isSymbolicLink(sourceRoot)) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        }
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        }
        val realRoot = try {
            val noFollowRoot = sourceRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
            val followedRoot = sourceRoot.toRealPath()
            if (noFollowRoot != followedRoot) {
                fail("SOURCE_ROOT_INVALID", "sourceRoot")
            }
            noFollowRoot
        } catch (_: IOException) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        } catch (_: SecurityException) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        }
        if (realRoot != sourceRoot) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        }
        return realRoot
    }

    private fun readSource(
        sourceRoot: Path,
        fileName: String,
        field: String,
    ): ByteArray {
        val expectedPath = sourceRoot.resolve(fileName).normalize()
        if (!expectedPath.startsWith(sourceRoot) || expectedPath.parent != sourceRoot) {
            fail("SOURCE_FILE_INVALID", field)
        }
        if (Files.isSymbolicLink(expectedPath) || !Files.isRegularFile(expectedPath, LinkOption.NOFOLLOW_LINKS)) {
            fail("SOURCE_FILE_INVALID", field)
        }
        val realPath = try {
            expectedPath.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            fail("SOURCE_FILE_INVALID", field)
        } catch (_: SecurityException) {
            fail("SOURCE_FILE_INVALID", field)
        }
        if (realPath != expectedPath || !realPath.startsWith(sourceRoot)) {
            fail("SOURCE_FILE_INVALID", field)
        }
        return try {
            Files.newByteChannel(
                expectedPath,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use(::readSnapshot)
        } catch (_: IOException) {
            fail("SOURCE_FILE_INVALID", field)
        } catch (_: SecurityException) {
            fail("SOURCE_FILE_INVALID", field)
        } catch (_: UnsupportedOperationException) {
            fail("SOURCE_FILE_INVALID", field)
        }
    }

    private fun readSnapshot(channel: SeekableByteChannel): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteBuffer.allocate(READ_BUFFER_SIZE)
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) {
                break
            }
            if (count == 0) {
                continue
            }
            output.write(buffer.array(), 0, count)
            buffer.clear()
        }
        output.toByteArray()
    }

    private fun verifyPilotManifest(
        bytes: ByteArray,
        expected: PilotManifestDescriptor,
    ) {
        val manifest = try {
            jsonMapper.readTree(bytes)
        } catch (_: JacksonException) {
            fail("PILOT_MANIFEST_INVALID", "manifest")
        }
        if (!manifest.isObject) {
            fail("PILOT_MANIFEST_INVALID", "manifest")
        }
        val classification = manifest.get("classification")
        if (classification == null || !classification.isTextual || classification.textValue() != expected.classification) {
            fail("PILOT_MANIFEST_INVALID", "classification")
        }
        val conditionBClosed = manifest.get("conditionBClosed")
        if (conditionBClosed == null || !conditionBClosed.isBoolean || conditionBClosed.booleanValue() != expected.conditionBClosed) {
            fail("PILOT_MANIFEST_INVALID", "conditionBClosed")
        }
    }

    private fun isValidZip(bytes: ByteArray): Boolean = hasEndOfCentralDirectory(bytes) && try {
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                val buffer = ByteArray(READ_BUFFER_SIZE)
                while (zip.read(buffer) >= 0) {
                    // Fully consume each entry so CRC and stream structure are checked.
                }
                zip.closeEntry()
            }
        }
        entryCount > 0
    } catch (_: ZipException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun hasEndOfCentralDirectory(bytes: ByteArray): Boolean {
        if (bytes.size < ZIP_END_RECORD_SIZE) {
            return false
        }
        val searchStart = maxOf(0, bytes.size - ZIP_END_RECORD_SIZE - ZIP_MAX_COMMENT_SIZE)
        for (offset in bytes.size - ZIP_END_RECORD_SIZE downTo searchStart) {
            if (littleEndianInt(bytes, offset) != ZIP_END_SIGNATURE) {
                continue
            }
            val commentLength = littleEndianShort(bytes, offset + ZIP_COMMENT_LENGTH_OFFSET)
            if (offset + ZIP_END_RECORD_SIZE + commentLength == bytes.size) {
                return true
            }
        }
        return false
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun rejectUnknownFields(
        node: JsonNode,
        allowedFields: Set<String>,
        prefix: String,
    ) {
        val unknown = node.fieldNames().asSequence().firstOrNull { it !in allowedFields }
        if (unknown != null) {
            fail("DESCRIPTOR_INVALID", "$prefix$unknown")
        }
    }

    private fun requireObject(node: JsonNode?, field: String) {
        if (node == null || !node.isObject) {
            fail("DESCRIPTOR_INVALID", field)
        }
    }

    private fun requireField(node: JsonNode, name: String, field: String): JsonNode =
        node.get(name) ?: fail("DESCRIPTOR_INVALID", field)

    private fun requireString(node: JsonNode, name: String, field: String): String {
        val value = requireField(node, name, field)
        if (!value.isTextual) {
            fail("DESCRIPTOR_INVALID", field)
        }
        return value.textValue()
    }

    private fun requirePattern(
        node: JsonNode,
        name: String,
        pattern: Regex,
        field: String,
    ): String {
        val value = requireString(node, name, field)
        if (!pattern.matches(value)) {
            fail("DESCRIPTOR_INVALID", field)
        }
        return value
    }

    private fun requireSafeFileName(node: JsonNode, name: String, field: String): String {
        val value = requireString(node, name, field)
        if (value.length !in 1..MAX_NAME_LENGTH || !SAFE_FILE_NAME_PATTERN.matches(value)) {
            fail("DESCRIPTOR_INVALID", field)
        }
        return value
    }

    private fun requireBoolean(node: JsonNode, name: String, field: String): Boolean {
        val value = requireField(node, name, field)
        if (!value.isBoolean) {
            fail("DESCRIPTOR_INVALID", field)
        }
        return value.booleanValue()
    }

    private fun requireExactInteger(node: JsonNode, name: String, expected: Int, field: String) {
        val value = requireField(node, name, field)
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.intValue() != expected) {
            fail("DESCRIPTOR_INVALID", field)
        }
    }

    private fun requirePositiveLong(node: JsonNode, name: String, field: String): Long {
        val value = requireField(node, name, field)
        if (!value.isIntegralNumber || !value.canConvertToLong() || value.longValue() < 1L) {
            fail("DESCRIPTOR_INVALID", field)
        }
        return value.longValue()
    }

    private fun descriptorFileNames(
        pilotManifest: PilotManifestDescriptor,
        artifacts: List<ArtifactDescriptor>,
    ): List<String> = listOf(pilotManifest.fileName) + artifacts.map { it.fileName }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun fail(code: String, field: String): Nothing =
        throw EvidenceArchiveInputFailure("$code:$field")

    private data class WorkPackageDescriptor(
        val workPackageId: String,
        val pilotManifest: PilotManifestDescriptor,
        val artifacts: List<ArtifactDescriptor>,
    )

    private data class PilotManifestDescriptor(
        val fileName: String,
        val sha256: String,
        val classification: String,
        val conditionBClosed: Boolean,
    )

    private data class ArtifactDescriptor(
        val artifactId: String,
        val artifactName: String,
        val fileName: String,
        val sourceRunId: String,
        val sourceCommit: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    private companion object {
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val PILOT_CLASSIFICATION = "LOCAL_PILOT_NOT_IMMUTABLE"
        const val ARTIFACT_COUNT = 2
        const val MAX_NAME_LENGTH = 255
        const val READ_BUFFER_SIZE = 8192
        const val ZIP_END_SIGNATURE = 0x06054b50
        const val ZIP_END_RECORD_SIZE = 22
        const val ZIP_COMMENT_LENGTH_OFFSET = 20
        const val ZIP_MAX_COMMENT_SIZE = 65535

        val ROOT_FIELDS = setOf(
            "schemaVersion",
            "workPackageId",
            "subjectCommit",
            "pairedSubjectCommit",
            "pilotManifest",
            "artifacts",
        )
        val PILOT_MANIFEST_FIELDS = setOf("fileName", "sha256", "classification", "conditionBClosed")
        val ARTIFACT_FIELDS = setOf(
            "artifactId",
            "artifactName",
            "fileName",
            "sourceRunId",
            "sourceCommit",
            "sizeBytes",
            "sha256",
        )
        val COMMIT_PATTERN = Regex("^[0-9a-f]{40}$")
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        val DECIMAL_ID_PATTERN = Regex("^[1-9][0-9]*$")
        val ARTIFACT_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        val SAFE_FILE_NAME_PATTERN = Regex(
            "^(?!.*\\.\\.)(?!.*\\.$)(?!(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\.|$))" +
                "[A-Za-z0-9][A-Za-z0-9._-]*$",
        )
        val jsonMapper: JsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
    }
}
