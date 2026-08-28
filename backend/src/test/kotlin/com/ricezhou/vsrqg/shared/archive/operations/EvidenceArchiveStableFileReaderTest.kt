package com.ricezhou.vsrqg.shared.archive.operations

import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableFileAccess
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableFileReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableReadChannel
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveDirectoryAccessControl
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveLocalFileIdentity
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveTrustedDirectory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class EvidenceArchiveStableFileReaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @BeforeEach
    fun secureTestDirectory() = prepareControlledTestDirectory(temporaryDirectory)

    @Test
    fun `accepts exact maximum and rejects oversize before opening a channel`() {
        val maximum = 1024 * 1024
        val exactChannel = ScriptedChannel(ByteArray(maximum), longArrayOf(maximum.toLong()), zeroFirstRead = false)
        val exactAccess = stableAccess(maximum.toLong()).copy(open = { exactChannel })
        assertThat(EvidenceArchiveStableFileReader(exactAccess).read(temporaryDirectory.resolve("exact.bin"), maximum))
            .hasSize(maximum)

        var opens = 0
        val access = stableAccess(maximum.toLong() + 1).copy(open = {
            opens += 1
            throw AssertionError("oversize file must not be opened")
        })

        assertThatThrownBy { EvidenceArchiveStableFileReader(access).read(temporaryDirectory.resolve("oversized.bin"), maximum) }
            .isInstanceOf(IOException::class.java)
        assertThat(opens).isEqualTo(0)
    }

    @Test
    fun `fails closed for symlinks`() {
        val target = temporaryDirectory.resolve("target.bin")
        val link = temporaryDirectory.resolve("link.bin")
        Files.write(target, byteArrayOf(1))
        try {
            Files.createSymbolicLink(link, target.fileName)
        } catch (failure: UnsupportedOperationException) {
            assumeTrue(false, "symbolic links unsupported: ${failure.message}")
        } catch (failure: IOException) {
            assumeTrue(false, "symbolic link creation unavailable: ${failure.message}")
        }

        assertThatThrownBy { EvidenceArchiveStableFileReader().read(link, 16) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `rejects early EOF zero progress growth and identity replacement`() {
        listOf(
            ScriptedChannel(byteArrayOf(1, 2), longArrayOf(3, 3), zeroFirstRead = false) to stableAccess(3),
            ScriptedChannel(byteArrayOf(1, 2, 3), longArrayOf(3, 3), zeroFirstRead = true) to stableAccess(3),
            ScriptedChannel(byteArrayOf(1, 2, 3, 4), longArrayOf(3, 4), zeroFirstRead = false) to stableAccess(3),
            ScriptedChannel(byteArrayOf(1, 2, 3), longArrayOf(3, 3), zeroFirstRead = false) to stableAccess(3, "other"),
        ).forEach { (channel, access) ->
            val reader = EvidenceArchiveStableFileReader(access.copy(open = { channel }))
            assertThatThrownBy { reader.read(temporaryDirectory.resolve("stable.bin"), 3) }
                .isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun `channel close failure remains visible`() {
        val channel = ScriptedChannel(byteArrayOf(1), longArrayOf(1), zeroFirstRead = false, failClose = true)
        val access = stableAccess(1).copy(open = { channel })

        assertThatThrownBy { EvidenceArchiveStableFileReader(access).read(temporaryDirectory.resolve("close.bin"), 1) }
            .isInstanceOf(IOException::class.java)
            .hasMessage("close failed")
    }

    @Test
    fun `rejects shared writable parent before opening`() {
        val view = Files.getFileAttributeView(temporaryDirectory, PosixFileAttributeView::class.java)
        assumeTrue(view != null, "POSIX permissions unavailable")
        val original = checkNotNull(view).readAttributes().permissions()
        val shared = original + PosixFilePermission.GROUP_WRITE
        Files.setPosixFilePermissions(temporaryDirectory, shared)
        val input = temporaryDirectory.resolve("shared.bin")
        Files.write(input, byteArrayOf(1))
        try {
            assertThatThrownBy { EvidenceArchiveStableFileReader().read(input, 1) }
                .isInstanceOf(IOException::class.java)
        } finally {
            Files.setPosixFilePermissions(temporaryDirectory, original)
        }
    }

    @Test
    fun `revalidates parent identity and access after open before reading`() {
        val events = mutableListOf<String>()
        val channel = ScriptedChannel(byteArrayOf(1), longArrayOf(1), zeroFirstRead = false, events = events)
        val access = stableAccess(1).copy(
            open = {
                events += "open"
                channel
            },
        )
        val trusted = trustedParent("parent-key", EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL)
        var reads = 0
        val reader = EvidenceArchiveStableFileReader(access, trustedDirectoryReader = {
            reads += 1
            events += "parent-$reads"
            if (reads == 1) trusted else trusted.copy(
                identity = EvidenceArchiveLocalFileIdentity.FileKey("changed-parent"),
            )
        })

        assertThatThrownBy { reader.read(temporaryDirectory.resolve("parent-change.bin"), 1) }
            .isInstanceOf(IOException::class.java)
        assertThat(events).containsExactly("parent-1", "open", "parent-2")
    }

    @Test
    fun `revalidates parent access control after reading`() {
        val channel = ScriptedChannel(byteArrayOf(1), longArrayOf(1), zeroFirstRead = false)
        val trusted = trustedParent("parent-key", EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL)
        var reads = 0
        val reader = EvidenceArchiveStableFileReader(stableAccess(1).copy(open = { channel }), trustedDirectoryReader = {
            reads += 1
            if (reads < 3) trusted else trusted.copy(
                accessControl = EvidenceArchiveDirectoryAccessControl.POSIX_NOT_SHARED_WRITABLE,
            )
        })

        assertThatThrownBy { reader.read(temporaryDirectory.resolve("access-change.bin"), 1) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `operator controlled ACL accepts stable metadata identity when file keys are unavailable`() {
        val trusted = trustedParent("parent-key", EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL)
        val channel = ScriptedChannel(byteArrayOf(1), longArrayOf(1, 1), zeroFirstRead = false)
        val access = stableAccess(1).copy(open = { channel }, fileKey = { _, _ -> null })
        val input = Files.write(temporaryDirectory.resolve("windows fallback.bin"), byteArrayOf(1))

        assertThat(
            EvidenceArchiveStableFileReader(access, trustedDirectoryReader = { trusted })
                .read(input, 1),
        ).containsExactly(1)
    }

    @Test
    fun `operator controlled ACL metadata identity rejects file metadata changes`() {
        val trusted = trustedParent("parent-key", EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL)
        val input = Files.write(temporaryDirectory.resolve("changed fallback.bin"), byteArrayOf(1))
        var reads = 0
        val access = stableAccess(1).copy(
            attributes = {
                reads += 1
                TestAttributes(1, null, if (reads == 1) TIME else FileTime.fromMillis(2))
            },
            open = { ScriptedChannel(byteArrayOf(1), longArrayOf(1, 1), zeroFirstRead = false) },
            fileKey = { _, _ -> null },
        )

        assertThatThrownBy {
            EvidenceArchiveStableFileReader(access, trustedDirectoryReader = { trusted })
                .read(input, 1)
        }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `POSIX parent rejects a missing file key instead of using metadata fallback`() {
        val trusted = trustedParent("parent-key", EvidenceArchiveDirectoryAccessControl.POSIX_NOT_SHARED_WRITABLE)
        val input = Files.write(temporaryDirectory.resolve("posix missing key.bin"), byteArrayOf(1))
        val access = stableAccess(1).copy(
            open = { ScriptedChannel(byteArrayOf(1), longArrayOf(1, 1), zeroFirstRead = false) },
            fileKey = { _, _ -> null },
        )

        assertThatThrownBy {
            EvidenceArchiveStableFileReader(access, trustedDirectoryReader = { trusted })
                .read(input, 1)
        }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `trusted directory uses controlled metadata only for operator ACL`() {
        val realPath = temporaryDirectory.toRealPath()
        val controlled = EvidenceArchiveTrustedDirectory.require(
            realPath,
            { it.toRealPath() },
            { _, _ -> null },
            { EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL },
        )
        assertThat(controlled.identity).isInstanceOf(EvidenceArchiveLocalFileIdentity.ControlledDirectory::class.java)

        assertThatThrownBy {
            EvidenceArchiveTrustedDirectory.require(
                realPath,
                { it.toRealPath() },
                { _, _ -> null },
                { EvidenceArchiveDirectoryAccessControl.POSIX_NOT_SHARED_WRITABLE },
            )
        }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `controlled parent metadata replacement fails revalidation`() {
        val realPath = temporaryDirectory.toRealPath()
        val first = EvidenceArchiveTrustedDirectory(
            realPath,
            realPath,
            EvidenceArchiveLocalFileIdentity.ControlledDirectory(realPath, TIME),
            EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL,
        )
        val changed = first.copy(
            identity = EvidenceArchiveLocalFileIdentity.ControlledDirectory(realPath, FileTime.fromMillis(2)),
        )
        var reads = 0
        val input = Files.write(temporaryDirectory.resolve("parent metadata.bin"), byteArrayOf(1))
        val reader = EvidenceArchiveStableFileReader(
            stableAccess(1).copy(
                open = { ScriptedChannel(byteArrayOf(1), longArrayOf(1, 1), zeroFirstRead = false) },
                fileKey = { _, _ -> null },
            ),
            trustedDirectoryReader = { if (reads++ == 0) first else changed },
        )

        assertThatThrownBy { reader.read(input, 1) }.isInstanceOf(IOException::class.java)
    }

    private fun trustedParent(key: Any, access: EvidenceArchiveDirectoryAccessControl) = EvidenceArchiveTrustedDirectory(
        temporaryDirectory,
        temporaryDirectory,
        key,
        access,
    )

    private fun stableAccess(size: Long, finalKey: Any = "file-key"): EvidenceArchiveStableFileAccess {
        var reads = 0
        return EvidenceArchiveStableFileAccess(
            attributes = {
                reads += 1
                TestAttributes(size, if (reads == 1) "file-key" else finalKey)
            },
            open = { throw AssertionError("channel supplied by test") },
            fileKey = { path, attributes -> attributes.fileKey() ?: path.toAbsolutePath().normalize().toString() },
        )
    }

    private class ScriptedChannel(
        private val content: ByteArray,
        private val sizes: LongArray,
        private val zeroFirstRead: Boolean,
        private val failClose: Boolean = false,
        private val events: MutableList<String>? = null,
    ) : EvidenceArchiveStableReadChannel {
        private var offset = 0
        private var sizeCalls = 0
        private var reads = 0

        override fun size(): Long = sizes[minOf(sizeCalls++, sizes.lastIndex)]

        override fun read(buffer: ByteBuffer): Int {
            events?.add("read")
            reads += 1
            if (zeroFirstRead && reads == 1) return 0
            if (offset == content.size) return -1
            val count = minOf(buffer.remaining(), content.size - offset)
            buffer.put(content, offset, count)
            offset += count
            return count
        }

        override fun close() {
            if (failClose) throw IOException("close failed")
        }
    }

    private class TestAttributes(
        private val length: Long,
        private val key: Any?,
        private val modified: FileTime = TIME,
    ) : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = modified
        override fun lastAccessTime(): FileTime = TIME
        override fun creationTime(): FileTime = TIME
        override fun isRegularFile(): Boolean = true
        override fun isDirectory(): Boolean = false
        override fun isSymbolicLink(): Boolean = false
        override fun isOther(): Boolean = false
        override fun size(): Long = length
        override fun fileKey(): Any? = key
    }

    private companion object {
        val TIME: FileTime = FileTime.fromMillis(1)
    }
}
