package com.ricezhou.vsrqg.shared.adapter.archive.operations

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

internal data class EvidenceZip32Limits(
    val maxInflatedEntryBytes: Long,
    val maxInflatedTotalBytes: Long,
)

internal val EVIDENCE_COMMIT_PATTERN = Regex("^[0-9a-f]{40}$")
internal val EVIDENCE_SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
internal val EVIDENCE_DECIMAL_ID_PATTERN = Regex("^[1-9][0-9]*$")

internal object EvidenceArchivePortableFileName {
    fun isSafe(name: String, maxUtf8Bytes: Int = MAX_UTF8_BYTES): Boolean {
        if (maxUtf8Bytes < 1 || name.toByteArray(Charsets.UTF_8).size !in 1..maxUtf8Bytes) return false
        if (!PORTABLE_NAME.matches(name) || ".." in name || name.endsWith('.') || name.endsWith(' ')) return false
        val baseName = name.substringBefore('.').uppercase(Locale.ROOT)
        return !WINDOWS_RESERVED_NAME.matches(baseName)
    }

    private const val MAX_UTF8_BYTES = 255
    private val PORTABLE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val WINDOWS_RESERVED_NAME = Regex("^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")
}

internal data class ParsedEvidenceArchiveWorkPackage(
    val workPackageId: String,
    val subjectCommit: String,
    val pairedSubjectCommit: String,
    val pilotManifest: ParsedPilotManifest,
    val artifacts: List<ParsedEvidenceArtifact>,
)

internal data class ParsedPilotManifest(
    val fileName: String,
    val sha256: String,
    val classification: String,
    val conditionBClosed: Boolean,
)

internal data class ParsedEvidenceArtifact(
    val artifactId: String,
    val artifactName: String,
    val fileName: String,
    val sourceRunId: String,
    val sourceCommit: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** The single strict parser for the frozen evidence-archive work-package descriptor. */
internal class EvidenceArchiveWorkPackageParser {
    fun parse(bytes: ByteArray): ParsedEvidenceArchiveWorkPackage {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_DESCRIPTOR_BYTES) fail("DESCRIPTOR_INVALID", "descriptor")
        val root = try {
            JSON.readTree(bytes)
        } catch (_: JacksonException) {
            fail("DESCRIPTOR_INVALID", "descriptor")
        }
        requireObject(root, "descriptor")
        rejectUnknownFields(root, ROOT_FIELDS, "")
        requireExactInteger(root, "schemaVersion", 1, "schemaVersion")
        val workPackageId = requireString(root, "workPackageId", "workPackageId")
        if (workPackageId != WORK_PACKAGE_ID) fail("DESCRIPTOR_INVALID", "workPackageId")
        val subjectCommit = requirePattern(root, "subjectCommit", COMMIT, "subjectCommit")
        val pairedSubjectCommit = requirePattern(root, "pairedSubjectCommit", COMMIT, "pairedSubjectCommit")
        val pilotManifest = parsePilotManifest(requireField(root, "pilotManifest", "pilotManifest"))
        val artifactsNode = requireField(root, "artifacts", "artifacts")
        if (!artifactsNode.isArray || artifactsNode.size() != ARTIFACT_COUNT) {
            fail("DESCRIPTOR_INVALID", "artifacts")
        }
        val artifacts = artifactsNode.mapIndexed(::parseArtifact)
        if (artifacts.map { it.artifactId }.toSet().size != artifacts.size) {
            fail("DESCRIPTOR_CONFLICT", "artifacts.artifactId")
        }
        val names = listOf(pilotManifest.fileName) + artifacts.map { it.fileName }
        if (names.map { it.lowercase(Locale.ROOT) }.toSet().size != ARTIFACT_COUNT + 1) {
            fail("DESCRIPTOR_CONFLICT", "artifacts.fileName")
        }
        return ParsedEvidenceArchiveWorkPackage(
            workPackageId,
            subjectCommit,
            pairedSubjectCommit,
            pilotManifest,
            Collections.unmodifiableList(artifacts),
        )
    }

    private fun parsePilotManifest(node: JsonNode): ParsedPilotManifest {
        val prefix = "pilotManifest"
        requireObject(node, prefix)
        rejectUnknownFields(node, PILOT_FIELDS, "$prefix.")
        val fileName = requireSafeFileName(node, "fileName", "$prefix.fileName")
        val digest = requirePattern(node, "sha256", SHA256, "$prefix.sha256")
        val classification = requireString(node, "classification", "$prefix.classification")
        if (classification != PILOT_CLASSIFICATION) fail("DESCRIPTOR_INVALID", "$prefix.classification")
        val conditionBClosed = requireBoolean(node, "conditionBClosed", "$prefix.conditionBClosed")
        if (conditionBClosed) fail("DESCRIPTOR_INVALID", "$prefix.conditionBClosed")
        return ParsedPilotManifest(fileName, digest, classification, conditionBClosed)
    }

    private fun parseArtifact(index: Int, node: JsonNode): ParsedEvidenceArtifact {
        val prefix = "artifacts[$index]"
        requireObject(node, prefix)
        rejectUnknownFields(node, ARTIFACT_FIELDS, "$prefix.")
        val artifactName = requireString(node, "artifactName", "$prefix.artifactName")
        if (artifactName.length !in 1..MAX_NAME_LENGTH || !ARTIFACT_NAME.matches(artifactName)) {
            fail("DESCRIPTOR_INVALID", "$prefix.artifactName")
        }
        return ParsedEvidenceArtifact(
            requirePattern(node, "artifactId", DECIMAL_ID, "$prefix.artifactId"),
            artifactName,
            requireSafeFileName(node, "fileName", "$prefix.fileName"),
            requirePattern(node, "sourceRunId", DECIMAL_ID, "$prefix.sourceRunId"),
            requirePattern(node, "sourceCommit", COMMIT, "$prefix.sourceCommit"),
            requirePositiveLong(node, "sizeBytes", "$prefix.sizeBytes"),
            requirePattern(node, "sha256", SHA256, "$prefix.sha256"),
        )
    }

    private fun rejectUnknownFields(node: JsonNode, allowed: Set<String>, prefix: String) {
        node.fieldNames().asSequence().firstOrNull { it !in allowed }?.let {
            fail("DESCRIPTOR_INVALID", "$prefix$it")
        }
    }

    private fun requireObject(node: JsonNode?, field: String) {
        if (node == null || !node.isObject) fail("DESCRIPTOR_INVALID", field)
    }

    private fun requireField(node: JsonNode, name: String, field: String): JsonNode =
        node.get(name) ?: fail("DESCRIPTOR_INVALID", field)

    private fun requireString(node: JsonNode, name: String, field: String): String {
        val value = requireField(node, name, field)
        if (!value.isTextual) fail("DESCRIPTOR_INVALID", field)
        return value.textValue()
    }

    private fun requirePattern(node: JsonNode, name: String, pattern: Regex, field: String): String {
        val value = requireString(node, name, field)
        if (!pattern.matches(value)) fail("DESCRIPTOR_INVALID", field)
        return value
    }

    private fun requireSafeFileName(node: JsonNode, name: String, field: String): String {
        val value = requireString(node, name, field)
        if (!EvidenceArchivePortableFileName.isSafe(value, MAX_NAME_LENGTH)) {
            fail("DESCRIPTOR_INVALID", field)
        }
        return value
    }

    private fun requireBoolean(node: JsonNode, name: String, field: String): Boolean {
        val value = requireField(node, name, field)
        if (!value.isBoolean) fail("DESCRIPTOR_INVALID", field)
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

    private fun fail(code: String, field: String): Nothing = throw EvidenceArchiveInputFailure("$code:$field")

    private companion object {
        const val MAX_DESCRIPTOR_BYTES = 1L * 1024 * 1024
        const val WORK_PACKAGE_ID = "V0-2-EVIDENCE-ARCHIVE-001"
        const val PILOT_CLASSIFICATION = "LOCAL_PILOT_NOT_IMMUTABLE"
        const val ARTIFACT_COUNT = 2
        const val MAX_NAME_LENGTH = 255
        val ROOT_FIELDS = setOf(
            "schemaVersion", "workPackageId", "subjectCommit", "pairedSubjectCommit", "pilotManifest", "artifacts",
        )
        val PILOT_FIELDS = setOf("fileName", "sha256", "classification", "conditionBClosed")
        val ARTIFACT_FIELDS = setOf(
            "artifactId", "artifactName", "fileName", "sourceRunId", "sourceCommit", "sizeBytes", "sha256",
        )
        val COMMIT = EVIDENCE_COMMIT_PATTERN
        val SHA256 = EVIDENCE_SHA256_PATTERN
        val DECIMAL_ID = EVIDENCE_DECIMAL_ID_PATTERN
        val ARTIFACT_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        val JSON: JsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
    }
}

class EvidenceArchiveSourceVerifier {
    private val snapshotReader = EvidenceArchiveSnapshotReader()
    private val zipValidator = EvidenceZip32Validator()
    private val descriptorParser = EvidenceArchiveWorkPackageParser()

    fun verify(
        descriptorBytes: ByteArray,
        sourceRoot: Path,
    ): VerifiedEvidenceArchiveWorkPackage {
        if (descriptorBytes.isEmpty() || descriptorBytes.size.toLong() > DEFAULT_DESCRIPTOR_MAX_BYTES) {
            fail("DESCRIPTOR_INVALID", "descriptor")
        }
        val descriptorSha256 = sha256(descriptorBytes)
        val descriptor = descriptorParser.parse(descriptorBytes)
        val verifiedRoot = verifySourceRoot(sourceRoot)
        val manifestBytes = readSource(
            sourceRoot = verifiedRoot,
            fileName = descriptor.pilotManifest.fileName,
            field = "pilotManifest.fileName",
            expectedSize = null,
            sizeField = null,
            maxBytes = DEFAULT_MANIFEST_MAX_BYTES,
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
                expectedSize = artifact.sizeBytes,
                sizeField = "$prefix.sizeBytes",
                maxBytes = DEFAULT_ARTIFACT_MAX_BYTES,
            )
            val digest = sha256(bytes)
            if (digest != artifact.sha256) {
                fail("SOURCE_DIGEST_MISMATCH", "$prefix.sha256")
            }
            if (!zipValidator.isValid(bytes)) {
                fail("SOURCE_ZIP_INVALID", "$prefix.fileName")
            }
            VerifiedArchiveSource(
                artifactId = artifact.artifactId,
                sourceRunId = artifact.sourceRunId,
                sourceCommit = artifact.sourceCommit,
                path = verifiedRoot.path.resolve(artifact.fileName),
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

    private fun verifySourceRoot(sourceRoot: Path): EvidenceArchiveTrustedDirectory {
        if (!sourceRoot.isAbsolute || sourceRoot.normalize() != sourceRoot || Files.isSymbolicLink(sourceRoot)) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        }
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        }
        return try {
            EvidenceArchiveTrustedDirectory.require(sourceRoot)
        } catch (_: IOException) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        } catch (_: SecurityException) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        } catch (_: UnsupportedOperationException) {
            fail("SOURCE_ROOT_INVALID", "sourceRoot")
        }
    }

    private fun readSource(
        sourceRoot: EvidenceArchiveTrustedDirectory,
        fileName: String,
        field: String,
        expectedSize: Long?,
        sizeField: String?,
        maxBytes: Long,
    ): ByteArray {
        val expectedPath = sourceRoot.path.resolve(fileName).normalize()
        if (!expectedPath.startsWith(sourceRoot.path) || expectedPath.parent != sourceRoot.path) {
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
        if (realPath != expectedPath || !realPath.startsWith(sourceRoot.path)) {
            fail("SOURCE_FILE_INVALID", field)
        }
        return snapshotReader.read(
            path = expectedPath,
            sourceRoot = sourceRoot,
            expectedSize = expectedSize,
            sizeField = sizeField,
            maxBytes = maxBytes,
            field = field,
        )
    }

    internal class EvidenceArchiveSnapshotReader(
        private val openChannel: (Path, Set<OpenOption>) -> SeekableByteChannel =
            { path, options -> Files.newByteChannel(path, options) },
        private val attributesReader: (Path) -> BasicFileAttributes = { path ->
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        },
        private val fileKeyReader: EvidenceArchiveFileKeyReader =
            EvidenceArchiveFileKeyReader { _, attributes -> attributes.fileKey() },
        private val accessReader: EvidenceArchiveDirectoryAccessReader = EvidenceArchiveDirectoryAccessReader.nio(),
        private val trustedDirectoryReader: (Path) -> EvidenceArchiveTrustedDirectory =
            EvidenceArchiveTrustedDirectory::require,
    ) {
        internal fun read(
            path: Path,
            sourceRoot: EvidenceArchiveTrustedDirectory,
            expectedSize: Long?,
            sizeField: String?,
            maxBytes: Long,
            field: String,
        ): ByteArray = try {
            requireSourceRootUnchanged(sourceRoot)
            val initial = snapshot(path)
            val bytes = openChannel(
                path,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                requireSourceRootUnchanged(sourceRoot)
                requireFileUnchanged(path, initial)
                readSnapshot(channel, expectedSize, sizeField, maxBytes, field).also {
                    requireFileUnchanged(path, initial)
                    requireSourceRootUnchanged(sourceRoot)
                }
            }
            requireFileUnchanged(path, initial)
            requireSourceRootUnchanged(sourceRoot)
            bytes
        } catch (_: IOException) {
            fail("SOURCE_FILE_INVALID", field)
        } catch (_: SecurityException) {
            fail("SOURCE_FILE_INVALID", field)
        } catch (_: UnsupportedOperationException) {
            fail("SOURCE_FILE_INVALID", field)
        }

        private fun snapshot(path: Path): SourceFileSnapshot {
            val attributes = attributesReader(path)
            if (!attributes.isRegularFile || attributes.isSymbolicLink) throw IOException("invalid source file")
            val identity = EvidenceArchiveLocalFileIdentity.require(
                path,
                attributes,
                fileKeyReader.read(path, attributes),
                accessReader.proof(path),
            )
            return SourceFileSnapshot(
                identity,
                attributes.size(),
                attributes.creationTime(),
                attributes.lastModifiedTime(),
            )
        }

        private fun requireFileUnchanged(path: Path, expected: SourceFileSnapshot) {
            if (snapshot(path) != expected) throw IOException("source file changed")
        }

        private fun requireSourceRootUnchanged(expected: EvidenceArchiveTrustedDirectory) {
            if (trustedDirectoryReader(expected.path) != expected) throw IOException("source root changed")
        }

        private data class SourceFileSnapshot(
            val identity: EvidenceArchiveLocalFileIdentity,
            val size: Long,
            val creationTime: FileTime,
            val lastModifiedTime: FileTime,
        )

        private fun readSnapshot(
            channel: SeekableByteChannel,
            expectedSize: Long?,
            sizeField: String?,
            maxBytes: Long,
            field: String,
        ): ByteArray {
            val initialSize = channel.size()
            validateSnapshotSize(initialSize, expectedSize, sizeField, maxBytes, field)
            return ByteArrayOutputStream(initialSize.toInt()).use { output ->
                val buffer = ByteBuffer.allocate(READ_BUFFER_SIZE)
                var totalRead = 0L
                while (true) {
                    val count = channel.read(buffer)
                    if (count < 0) {
                        break
                    }
                    if (count == 0) {
                        fail("SOURCE_FILE_INVALID", field)
                    }
                    val nextTotal = totalRead + count
                    if (nextTotal > maxBytes) {
                        fail("SOURCE_FILE_INVALID", field)
                    }
                    if (expectedSize != null && nextTotal > expectedSize) {
                        fail("SOURCE_SIZE_MISMATCH", checkNotNull(sizeField))
                    }
                    if (nextTotal > initialSize) {
                        fail("SOURCE_FILE_INVALID", field)
                    }
                    output.write(buffer.array(), 0, count)
                    totalRead = nextTotal
                    buffer.clear()
                }
                val finalSize = channel.size()
                validateSnapshotSize(finalSize, expectedSize, sizeField, maxBytes, field)
                validateSnapshotSize(totalRead, expectedSize, sizeField, maxBytes, field)
                if (totalRead != initialSize || finalSize != initialSize) {
                    fail("SOURCE_FILE_INVALID", field)
                }
                output.toByteArray()
            }
        }

        private fun validateSnapshotSize(
            size: Long,
            expectedSize: Long?,
            sizeField: String?,
            maxBytes: Long,
            field: String,
        ) {
            if (size < 0 || size > maxBytes) {
                fail("SOURCE_FILE_INVALID", field)
            }
            if (expectedSize != null && size != expectedSize) {
                fail("SOURCE_SIZE_MISMATCH", checkNotNull(sizeField))
            }
            if (size == 0L) {
                fail("SOURCE_FILE_INVALID", field)
            }
        }

        private fun fail(code: String, field: String): Nothing =
            throw EvidenceArchiveInputFailure("$code:$field")
    }

    private fun verifyPilotManifest(
        bytes: ByteArray,
        expected: ParsedPilotManifest,
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

    internal class EvidenceZip32Validator(
        private val limits: EvidenceZip32Limits = EvidenceZip32Limits(
            maxInflatedEntryBytes = MAX_ZIP_ENTRY_UNCOMPRESSED_BYTES,
            maxInflatedTotalBytes = MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES,
        ),
    ) {
        internal fun isValid(bytes: ByteArray): Boolean = try {
            val entries = parseZip32(bytes)
            verifyInflatedEntries(bytes, entries)
            true
        } catch (_: InvalidZipStructure) {
            false
        } catch (_: ZipException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun parseZip32(bytes: ByteArray): List<Zip32Entry> {
            val endOffset = uniqueEndOfCentralDirectory(bytes)
            val diskNumber = unsignedShort(bytes, endOffset + ZIP_END_DISK_NUMBER_OFFSET)
            val centralDirectoryDisk = unsignedShort(bytes, endOffset + ZIP_END_DIRECTORY_DISK_OFFSET)
            val entriesOnDisk = unsignedShort(bytes, endOffset + ZIP_END_DISK_ENTRY_COUNT_OFFSET)
            val entryCount = unsignedShort(bytes, endOffset + ZIP_END_TOTAL_ENTRY_COUNT_OFFSET)
            val centralDirectorySize = unsignedInt(bytes, endOffset + ZIP_END_DIRECTORY_SIZE_OFFSET)
            val centralDirectoryOffset = unsignedInt(bytes, endOffset + ZIP_END_DIRECTORY_OFFSET_OFFSET)
            zipRequire(diskNumber == 0 && centralDirectoryDisk == 0 && entriesOnDisk == entryCount)
            zipRequire(entryCount != ZIP16_SENTINEL && entryCount in 1..MAX_ZIP_ENTRY_COUNT)
            zipRequire(centralDirectorySize != ZIP32_SENTINEL && centralDirectoryOffset != ZIP32_SENTINEL)
            val centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize
            zipRequire(centralDirectoryEnd == endOffset.toLong())

            var cursor = centralDirectoryOffset.toIntWithin(bytes.size)
            val directoryEnd = centralDirectoryEnd.toIntWithin(bytes.size)
            val entries = ArrayList<Zip32Entry>(entryCount)
            var totalUncompressedSize = 0L
            repeat(entryCount) {
                requireRange(cursor, ZIP_CENTRAL_HEADER_SIZE, directoryEnd)
                zipRequire(unsignedInt(bytes, cursor) == ZIP_CENTRAL_HEADER_SIGNATURE)
                val versionMadeBy = unsignedShort(bytes, cursor + ZIP_CENTRAL_VERSION_MADE_BY_OFFSET)
                val versionNeeded = unsignedShort(bytes, cursor + ZIP_CENTRAL_VERSION_NEEDED_OFFSET)
                val flags = unsignedShort(bytes, cursor + ZIP_CENTRAL_FLAGS_OFFSET)
                val method = unsignedShort(bytes, cursor + ZIP_CENTRAL_METHOD_OFFSET)
                validateVersionFlagsAndMethod(versionNeeded, flags, method)
                val crc32 = unsignedInt(bytes, cursor + ZIP_CENTRAL_CRC_OFFSET)
                val compressedSize = unsignedInt(bytes, cursor + ZIP_CENTRAL_COMPRESSED_SIZE_OFFSET)
                val uncompressedSize = unsignedInt(bytes, cursor + ZIP_CENTRAL_UNCOMPRESSED_SIZE_OFFSET)
                val nameLength = unsignedShort(bytes, cursor + ZIP_CENTRAL_NAME_LENGTH_OFFSET)
                val extraLength = unsignedShort(bytes, cursor + ZIP_CENTRAL_EXTRA_LENGTH_OFFSET)
                val commentLength = unsignedShort(bytes, cursor + ZIP_CENTRAL_COMMENT_LENGTH_OFFSET)
                val startDisk = unsignedShort(bytes, cursor + ZIP_CENTRAL_START_DISK_OFFSET)
                val externalAttributes = unsignedInt(bytes, cursor + ZIP_CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET)
                val localHeaderOffset = unsignedInt(bytes, cursor + ZIP_CENTRAL_LOCAL_OFFSET_OFFSET)
                zipRequire(compressedSize != ZIP32_SENTINEL && uncompressedSize != ZIP32_SENTINEL)
                zipRequire(localHeaderOffset != ZIP32_SENTINEL && startDisk == 0)
                zipRequire(nameLength > 0 && uncompressedSize <= MAX_ZIP_ENTRY_UNCOMPRESSED_BYTES)
                zipRequire(totalUncompressedSize <= MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES - uncompressedSize)
                totalUncompressedSize += uncompressedSize

                val nameStartValue = cursor.toLong() + ZIP_CENTRAL_HEADER_SIZE
                val extraStartValue = nameStartValue + nameLength
                val commentStartValue = extraStartValue + extraLength
                val recordEnd = commentStartValue + commentLength
                zipRequire(recordEnd <= directoryEnd.toLong())
                val nameStart = nameStartValue.toIntWithin(directoryEnd)
                val extraStart = extraStartValue.toIntWithin(directoryEnd)
                val rawName = bytes.copyOfRange(nameStart, extraStart)
                val validatedName = decodeAndValidateEntryName(rawName, flags)
                validateExtraFields(bytes, extraStart, extraLength, ZipExtraLocation.CENTRAL)
                val directory = validateEntryType(
                    versionMadeBy = versionMadeBy,
                    externalAttributes = externalAttributes,
                    directoryByName = validatedName.directory,
                )
                if (directory) {
                    zipRequire(crc32 == 0L && compressedSize == 0L && uncompressedSize == 0L)
                    zipRequire(method == ZIP_STORED_METHOD && flags and ZIP_DATA_DESCRIPTOR_FLAG == 0)
                }
                entries += Zip32Entry(
                    name = validatedName.original,
                    portableKey = validatedName.portableKey,
                    directory = directory,
                    rawName = rawName,
                    versionNeeded = versionNeeded,
                    flags = flags,
                    method = method,
                    crc32 = crc32,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localHeaderOffset.toIntWithin(directoryEnd),
                )
                cursor = recordEnd.toInt()
            }
            zipRequire(cursor == directoryEnd)
            val portableKeys = entries.map { it.portableKey }
            zipRequire(portableKeys.toSet().size == entries.size)
            validateEntryTree(entries)
            zipRequire(entries.any { !it.directory })
            validateLocalRecords(bytes, entries, centralDirectoryOffset.toInt())
            return Collections.unmodifiableList(entries)
        }

        private fun uniqueEndOfCentralDirectory(bytes: ByteArray): Int {
            zipRequire(bytes.size >= ZIP_END_RECORD_SIZE)
            val searchStart = maxOf(0, bytes.size - ZIP_END_RECORD_SIZE - ZIP_MAX_COMMENT_SIZE)
            var match = -1
            for (offset in bytes.size - ZIP_END_RECORD_SIZE downTo searchStart) {
                if (unsignedInt(bytes, offset) != ZIP_END_SIGNATURE) {
                    continue
                }
                val commentLength = unsignedShort(bytes, offset + ZIP_END_COMMENT_LENGTH_OFFSET)
                if (offset + ZIP_END_RECORD_SIZE + commentLength == bytes.size) {
                    zipRequire(match < 0)
                    match = offset
                }
            }
            zipRequire(match >= 0)
            return match
        }

        private fun validateLocalRecords(
            bytes: ByteArray,
            entries: List<Zip32Entry>,
            centralDirectoryOffset: Int,
        ) {
            var expectedOffset = 0
            for (entry in entries.sortedBy { it.localHeaderOffset }) {
                zipRequire(entry.localHeaderOffset == expectedOffset)
                requireRange(entry.localHeaderOffset, ZIP_LOCAL_HEADER_SIZE, centralDirectoryOffset)
                zipRequire(unsignedInt(bytes, entry.localHeaderOffset) == ZIP_LOCAL_HEADER_SIGNATURE)
                val versionNeeded = unsignedShort(bytes, entry.localHeaderOffset + ZIP_LOCAL_VERSION_NEEDED_OFFSET)
                val flags = unsignedShort(bytes, entry.localHeaderOffset + ZIP_LOCAL_FLAGS_OFFSET)
                val method = unsignedShort(bytes, entry.localHeaderOffset + ZIP_LOCAL_METHOD_OFFSET)
                zipRequire(versionNeeded == entry.versionNeeded && flags == entry.flags && method == entry.method)
                val localCrc32 = unsignedInt(bytes, entry.localHeaderOffset + ZIP_LOCAL_CRC_OFFSET)
                val localCompressedSize = unsignedInt(bytes, entry.localHeaderOffset + ZIP_LOCAL_COMPRESSED_SIZE_OFFSET)
                val localUncompressedSize = unsignedInt(bytes, entry.localHeaderOffset + ZIP_LOCAL_UNCOMPRESSED_SIZE_OFFSET)
                val nameLength = unsignedShort(bytes, entry.localHeaderOffset + ZIP_LOCAL_NAME_LENGTH_OFFSET)
                val extraLength = unsignedShort(bytes, entry.localHeaderOffset + ZIP_LOCAL_EXTRA_LENGTH_OFFSET)
                zipRequire(nameLength == entry.rawName.size)
                val nameStartValue = entry.localHeaderOffset.toLong() + ZIP_LOCAL_HEADER_SIZE
                val extraStartValue = nameStartValue + nameLength
                val dataStartValue = extraStartValue + extraLength
                zipRequire(dataStartValue <= centralDirectoryOffset.toLong())
                val nameStart = nameStartValue.toIntWithin(centralDirectoryOffset)
                val extraStart = extraStartValue.toIntWithin(centralDirectoryOffset)
                val dataStart = dataStartValue.toInt()
                zipRequire(bytes.copyOfRange(nameStart, extraStart).contentEquals(entry.rawName))
                validateExtraFields(bytes, extraStart, extraLength, ZipExtraLocation.LOCAL)
                val dataEnd = dataStart.toLong() + entry.compressedSize
                zipRequire(dataEnd <= centralDirectoryOffset.toLong())
                expectedOffset = if (flags and ZIP_DATA_DESCRIPTOR_FLAG != 0) {
                    zipRequire(localCrc32 == 0L || localCrc32 == entry.crc32)
                    zipRequire(localCompressedSize == 0L || localCompressedSize == entry.compressedSize)
                    zipRequire(localUncompressedSize == 0L || localUncompressedSize == entry.uncompressedSize)
                    dataDescriptorEnd(bytes, dataEnd.toInt(), centralDirectoryOffset, entry)
                } else {
                    zipRequire(localCrc32 == entry.crc32)
                    zipRequire(localCompressedSize == entry.compressedSize)
                    zipRequire(localUncompressedSize == entry.uncompressedSize)
                    dataEnd.toInt()
                }
            }
            zipRequire(expectedOffset == centralDirectoryOffset)
        }

        private fun dataDescriptorEnd(
            bytes: ByteArray,
            descriptorOffset: Int,
            limit: Int,
            entry: Zip32Entry,
        ): Int {
            val candidates = buildList {
                if (descriptorOffset.toLong() + ZIP_DATA_DESCRIPTOR_WITHOUT_SIGNATURE_SIZE <= limit) {
                    if (dataDescriptorMatches(bytes, descriptorOffset, entry)) {
                        add(descriptorOffset + ZIP_DATA_DESCRIPTOR_WITHOUT_SIGNATURE_SIZE)
                    }
                }
                if (descriptorOffset.toLong() + ZIP_DATA_DESCRIPTOR_WITH_SIGNATURE_SIZE <= limit &&
                    unsignedInt(bytes, descriptorOffset) == ZIP_DATA_DESCRIPTOR_SIGNATURE &&
                    dataDescriptorMatches(bytes, descriptorOffset + Int.SIZE_BYTES, entry)
                ) {
                    add(descriptorOffset + ZIP_DATA_DESCRIPTOR_WITH_SIGNATURE_SIZE)
                }
            }
            zipRequire(candidates.size == 1)
            return candidates.single()
        }

        private fun dataDescriptorMatches(bytes: ByteArray, offset: Int, entry: Zip32Entry): Boolean =
            unsignedInt(bytes, offset) == entry.crc32 &&
                unsignedInt(bytes, offset + Int.SIZE_BYTES) == entry.compressedSize &&
                unsignedInt(bytes, offset + Int.SIZE_BYTES * 2) == entry.uncompressedSize

        private fun validateExtraFields(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            location: ZipExtraLocation,
        ) {
            val end = offset.toLong() + length
            zipRequire(end <= bytes.size.toLong())
            var cursor = offset
            val seenHeaderIds = mutableSetOf<Int>()
            while (cursor < end) {
                zipRequire(cursor.toLong() + ZIP_EXTRA_FIELD_HEADER_SIZE <= end)
                val headerId = unsignedShort(bytes, cursor)
                val dataLength = unsignedShort(bytes, cursor + Short.SIZE_BYTES)
                zipRequire(seenHeaderIds.add(headerId))
                cursor += ZIP_EXTRA_FIELD_HEADER_SIZE
                zipRequire(cursor.toLong() + dataLength <= end)
                when (headerId) {
                    ZIP_EXTENDED_TIMESTAMP_EXTRA_FIELD_ID ->
                        validateExtendedTimestampExtra(bytes, cursor, dataLength, location)
                    ZIP_NTFS_EXTRA_FIELD_ID -> validateNtfsExtra(bytes, cursor, dataLength)
                    else -> throw InvalidZipStructure()
                }
                cursor += dataLength
            }
            zipRequire(cursor.toLong() == end)
        }

        private fun validateExtendedTimestampExtra(
            bytes: ByteArray,
            offset: Int,
            length: Int,
            location: ZipExtraLocation,
        ) {
            zipRequire(length >= 1)
            val timestampFlags = bytes[offset].toInt() and 0xff
            zipRequire(timestampFlags and ZIP_TIMESTAMP_UNSUPPORTED_FLAGS_MASK == 0)
            zipRequire(timestampFlags != 0)
            if (location == ZipExtraLocation.CENTRAL) {
                val centralTimestampCount = if (timestampFlags and ZIP_TIMESTAMP_MODIFIED_FLAG != 0) 1 else 0
                zipRequire(length == 1 + centralTimestampCount * Int.SIZE_BYTES)
                return
            }
            val timestampCount = Integer.bitCount(timestampFlags)
            zipRequire(length == 1 + timestampCount * Int.SIZE_BYTES)
        }

        private fun validateNtfsExtra(bytes: ByteArray, offset: Int, length: Int) {
            zipRequire(length == ZIP_NTFS_EXTRA_DATA_SIZE)
            zipRequire(unsignedInt(bytes, offset) == 0L)
            zipRequire(unsignedShort(bytes, offset + Int.SIZE_BYTES) == ZIP_NTFS_TIMESTAMP_TAG)
            zipRequire(unsignedShort(bytes, offset + Int.SIZE_BYTES + Short.SIZE_BYTES) == ZIP_NTFS_TIMESTAMP_DATA_SIZE)
        }

        private fun validateVersionFlagsAndMethod(versionNeeded: Int, flags: Int, method: Int) {
            zipRequire(versionNeeded in ZIP_MIN_VERSION_NEEDED..ZIP_MAX_VERSION_NEEDED)
            zipRequire(flags and ZIP_UNSUPPORTED_FLAGS_MASK == 0)
            zipRequire(method == ZIP_STORED_METHOD || method == ZIP_DEFLATED_METHOD)
            if (method == ZIP_STORED_METHOD) {
                zipRequire(flags and ZIP_DEFLATE_OPTION_FLAGS == 0)
            } else {
                zipRequire(versionNeeded >= ZIP_DEFLATE_VERSION_NEEDED)
            }
        }

        private fun decodeAndValidateEntryName(rawName: ByteArray, flags: Int): ValidatedZipEntryName {
            val charset = if (flags and ZIP_UTF8_FLAG != 0) Charsets.UTF_8 else ZIP_LEGACY_CHARSET
            val name = try {
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawName))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw InvalidZipStructure()
            }
            zipRequire(name.isNotEmpty() && name.all { it.code in ASCII_PRINTABLE_RANGE })
            zipRequire('\\' !in name && !name.startsWith('/'))
            val directory = name.endsWith('/')
            val path = if (directory) name.dropLast(1) else name
            zipRequire(path.isNotEmpty())
            val segments = path.split('/')
            zipRequire(segments.none { !isPortableZipSegment(it) })
            return ValidatedZipEntryName(
                original = name,
                portableKey = portableCollisionKey(path),
                directory = directory,
            )
        }

        private fun isPortableZipSegment(segment: String): Boolean {
            if (segment.isEmpty() || segment == "." || segment == ".." || segment.endsWith('.') || segment.endsWith(' ')) {
                return false
            }
            if (segment.any { it in WINDOWS_FORBIDDEN_ZIP_SEGMENT_CHARACTERS }) {
                return false
            }
            val deviceBaseName = segment.substringBefore('.').uppercase(Locale.ROOT)
            return !WINDOWS_RESERVED_DEVICE_NAMES.matches(deviceBaseName)
        }

        private fun validateEntryTree(entries: List<Zip32Entry>) {
            val fileKeys = entries.asSequence()
                .filterNot { it.directory }
                .mapTo(mutableSetOf()) { it.portableKey }
            for (entry in entries) {
                var separator = entry.portableKey.indexOf('/')
                while (separator >= 0) {
                    zipRequire(entry.portableKey.substring(0, separator) !in fileKeys)
                    separator = entry.portableKey.indexOf('/', separator + 1)
                }
            }
        }

        private fun validateEntryType(
            versionMadeBy: Int,
            externalAttributes: Long,
            directoryByName: Boolean,
        ): Boolean {
            zipRequire(versionMadeBy and 0xff in ZIP_MIN_VERSION_MADE_BY..ZIP_MAX_VERSION_MADE_BY)
            val hostSystem = versionMadeBy ushr 8
            val dosAttributes = externalAttributes and ZIP_DOS_ATTRIBUTE_WORD_MASK
            zipRequire(dosAttributes and ZIP_DOS_ALLOWED_ATTRIBUTES_MASK.inv() == 0L)
            val dosDirectory = dosAttributes and ZIP_DOS_DIRECTORY_ATTRIBUTE != 0L
            if (hostSystem in ZIP_UNIX_HOST_SYSTEMS) {
                val unixType = ((externalAttributes ushr 16) and ZIP_UNIX_FILE_TYPE_MASK).toInt()
                val unixDirectory = unixType == ZIP_UNIX_DIRECTORY_TYPE
                zipRequire(unixType == ZIP_UNIX_REGULAR_FILE_TYPE || unixDirectory)
                zipRequire(!dosDirectory || unixDirectory)
                zipRequire(unixDirectory == directoryByName)
                return unixDirectory
            }
            zipRequire(externalAttributes == dosAttributes)
            zipRequire(dosDirectory == directoryByName)
            return dosDirectory
        }

        private fun verifyInflatedEntries(bytes: ByteArray, entries: List<Zip32Entry>) {
            val expectedEntries = entries.sortedBy { it.localHeaderOffset }
            var totalInflated = 0L
            ZipInputStream(ByteArrayInputStream(bytes), ZIP_LEGACY_CHARSET).use { zip ->
                for (expected in expectedEntries) {
                    val actual = zip.nextEntry ?: throw InvalidZipStructure()
                    zipRequire(actual.name == expected.name)
                    zipRequire(actual.isDirectory == expected.directory)
                    var entryInflated = 0L
                    val buffer = ByteArray(READ_BUFFER_SIZE)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) {
                            break
                        }
                        if (count == 0) {
                            continue
                        }
                        entryInflated += count
                        totalInflated += count
                        zipRequire(entryInflated <= limits.maxInflatedEntryBytes)
                        zipRequire(totalInflated <= limits.maxInflatedTotalBytes)
                    }
                    zipRequire(entryInflated == expected.uncompressedSize)
                    zipRequire(actual.crc == expected.crc32)
                    zip.closeEntry()
                }
                zipRequire(zip.nextEntry == null)
            }
        }

        private fun requireRange(offset: Int, length: Int, limit: Int) {
            zipRequire(offset >= 0 && length >= 0 && offset.toLong() + length <= limit.toLong())
        }

        private fun unsignedShort(bytes: ByteArray, offset: Int): Int {
            requireRange(offset, Short.SIZE_BYTES, bytes.size)
            return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
        }

        private fun unsignedInt(bytes: ByteArray, offset: Int): Long {
            requireRange(offset, Int.SIZE_BYTES, bytes.size)
            return (bytes[offset].toLong() and 0xff) or
                ((bytes[offset + 1].toLong() and 0xff) shl 8) or
                ((bytes[offset + 2].toLong() and 0xff) shl 16) or
                ((bytes[offset + 3].toLong() and 0xff) shl 24)
        }

        private fun Long.toIntWithin(limit: Int): Int {
            zipRequire(this in 0..limit.toLong())
            return toInt()
        }

        private fun zipRequire(condition: Boolean) {
            if (!condition) {
                throw InvalidZipStructure()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun fail(code: String, field: String): Nothing =
        throw EvidenceArchiveInputFailure("$code:$field")

    private data class Zip32Entry(
        val name: String,
        val portableKey: String,
        val directory: Boolean,
        val rawName: ByteArray,
        val versionNeeded: Int,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Int,
    )

    private data class ValidatedZipEntryName(
        val original: String,
        val portableKey: String,
        val directory: Boolean,
    )

    private enum class ZipExtraLocation {
        CENTRAL,
        LOCAL,
    }

    private class InvalidZipStructure : RuntimeException(null, null, false, false)

    private companion object {
        const val READ_BUFFER_SIZE = 8192

        const val ZIP_END_SIGNATURE = 0x06054b50L
        const val ZIP_END_RECORD_SIZE = 22
        const val ZIP_END_DISK_NUMBER_OFFSET = 4
        const val ZIP_END_DIRECTORY_DISK_OFFSET = 6
        const val ZIP_END_DISK_ENTRY_COUNT_OFFSET = 8
        const val ZIP_END_TOTAL_ENTRY_COUNT_OFFSET = 10
        const val ZIP_END_DIRECTORY_SIZE_OFFSET = 12
        const val ZIP_END_DIRECTORY_OFFSET_OFFSET = 16
        const val ZIP_END_COMMENT_LENGTH_OFFSET = 20
        const val ZIP_MAX_COMMENT_SIZE = 65535
        const val ZIP_CENTRAL_HEADER_SIGNATURE = 0x02014b50L
        const val ZIP_CENTRAL_HEADER_SIZE = 46
        const val ZIP_CENTRAL_VERSION_MADE_BY_OFFSET = 4
        const val ZIP_CENTRAL_VERSION_NEEDED_OFFSET = 6
        const val ZIP_CENTRAL_FLAGS_OFFSET = 8
        const val ZIP_CENTRAL_METHOD_OFFSET = 10
        const val ZIP_CENTRAL_CRC_OFFSET = 16
        const val ZIP_CENTRAL_COMPRESSED_SIZE_OFFSET = 20
        const val ZIP_CENTRAL_UNCOMPRESSED_SIZE_OFFSET = 24
        const val ZIP_CENTRAL_NAME_LENGTH_OFFSET = 28
        const val ZIP_CENTRAL_EXTRA_LENGTH_OFFSET = 30
        const val ZIP_CENTRAL_COMMENT_LENGTH_OFFSET = 32
        const val ZIP_CENTRAL_START_DISK_OFFSET = 34
        const val ZIP_CENTRAL_EXTERNAL_ATTRIBUTES_OFFSET = 38
        const val ZIP_CENTRAL_LOCAL_OFFSET_OFFSET = 42
        const val ZIP_LOCAL_HEADER_SIGNATURE = 0x04034b50L
        const val ZIP_LOCAL_HEADER_SIZE = 30
        const val ZIP_LOCAL_VERSION_NEEDED_OFFSET = 4
        const val ZIP_LOCAL_FLAGS_OFFSET = 6
        const val ZIP_LOCAL_METHOD_OFFSET = 8
        const val ZIP_LOCAL_CRC_OFFSET = 14
        const val ZIP_LOCAL_COMPRESSED_SIZE_OFFSET = 18
        const val ZIP_LOCAL_UNCOMPRESSED_SIZE_OFFSET = 22
        const val ZIP_LOCAL_NAME_LENGTH_OFFSET = 26
        const val ZIP_LOCAL_EXTRA_LENGTH_OFFSET = 28
        const val ZIP_DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L
        const val ZIP_DATA_DESCRIPTOR_WITHOUT_SIGNATURE_SIZE = 12
        const val ZIP_DATA_DESCRIPTOR_WITH_SIGNATURE_SIZE = 16
        const val ZIP_DATA_DESCRIPTOR_FLAG = 0x0008
        const val ZIP_UTF8_FLAG = 0x0800
        const val ZIP_DEFLATE_OPTION_FLAGS = 0x0006
        const val ZIP_UNSUPPORTED_FLAGS_MASK = 0xf7f1
        const val ZIP_STORED_METHOD = 0
        const val ZIP_DEFLATED_METHOD = 8
        const val ZIP_EXTRA_FIELD_HEADER_SIZE = 4
        const val ZIP_NTFS_EXTRA_FIELD_ID = 0x000a
        const val ZIP_EXTENDED_TIMESTAMP_EXTRA_FIELD_ID = 0x5455
        const val ZIP_TIMESTAMP_MODIFIED_FLAG = 0x01
        const val ZIP_TIMESTAMP_UNSUPPORTED_FLAGS_MASK = 0xf8
        const val ZIP_NTFS_EXTRA_DATA_SIZE = 32
        const val ZIP_NTFS_TIMESTAMP_TAG = 0x0001
        const val ZIP_NTFS_TIMESTAMP_DATA_SIZE = 24
        const val ZIP_MIN_VERSION_NEEDED = 10
        const val ZIP_MAX_VERSION_NEEDED = 20
        const val ZIP_DEFLATE_VERSION_NEEDED = 20
        const val ZIP_DOS_DIRECTORY_ATTRIBUTE = 0x10L
        const val ZIP_DOS_ALLOWED_ATTRIBUTES_MASK = 0x37L
        const val ZIP_DOS_ATTRIBUTE_WORD_MASK = 0xffffL
        const val ZIP_UNIX_FILE_TYPE_MASK = 0xf000L
        const val ZIP_UNIX_REGULAR_FILE_TYPE = 0x8000
        const val ZIP_UNIX_DIRECTORY_TYPE = 0x4000
        const val ZIP16_SENTINEL = 0xffff
        const val ZIP32_SENTINEL = 0xffffffffL
        const val MAX_ZIP_ENTRY_COUNT = 1024
        const val MAX_ZIP_ENTRY_UNCOMPRESSED_BYTES = 128L * 1024 * 1024
        const val MAX_ZIP_TOTAL_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
        const val DEFAULT_DESCRIPTOR_MAX_BYTES = 1L * 1024 * 1024
        const val DEFAULT_MANIFEST_MAX_BYTES = 1L * 1024 * 1024
        const val DEFAULT_ARTIFACT_MAX_BYTES = 64L * 1024 * 1024
        const val ZIP_MIN_VERSION_MADE_BY = 10
        const val ZIP_MAX_VERSION_MADE_BY = 20

        val jsonMapper: JsonMapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build()
        val ZIP_LEGACY_CHARSET: Charset = Charset.forName("IBM437")
        val ZIP_UNIX_HOST_SYSTEMS = setOf(3, 19)
        val WINDOWS_RESERVED_DEVICE_NAMES = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")
        val ASCII_PRINTABLE_RANGE = 0x20..0x7e
        const val WINDOWS_FORBIDDEN_ZIP_SEGMENT_CHARACTERS = "<>:\"\\|?*"

        private fun portableCollisionKey(validatedAsciiPath: String): String =
            validatedAsciiPath.replace('\\', '/').lowercase(Locale.ROOT)
    }
}
