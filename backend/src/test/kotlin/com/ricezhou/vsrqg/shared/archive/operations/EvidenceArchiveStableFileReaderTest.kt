package com.ricezhou.vsrqg.shared.archive.operations

import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableFileAccess
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableFileReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveStableReadChannel
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class EvidenceArchiveStableFileReaderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `accepts exact maximum and rejects oversize before opening a channel`() {
        val maximum = 1024 * 1024
        val exactChannel = ScriptedChannel(ByteArray(maximum), longArrayOf(maximum.toLong()), zeroFirstRead = false)
        val exactAccess = stableAccess(maximum.toLong()).copy(open = { exactChannel })
        assertThat(EvidenceArchiveStableFileReader(exactAccess).read(Path.of("exact.bin"), maximum)).hasSize(maximum)

        var opens = 0
        val access = stableAccess(maximum.toLong() + 1).copy(open = {
            opens += 1
            throw AssertionError("oversize file must not be opened")
        })

        assertThatThrownBy { EvidenceArchiveStableFileReader(access).read(Path.of("oversized.bin"), maximum) }
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
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: IOException) {
            return
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
            assertThatThrownBy { reader.read(Path.of("stable.bin"), 3) }
                .isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun `channel close failure remains visible`() {
        val channel = ScriptedChannel(byteArrayOf(1), longArrayOf(1), zeroFirstRead = false, failClose = true)
        val access = stableAccess(1).copy(open = { channel })

        assertThatThrownBy { EvidenceArchiveStableFileReader(access).read(Path.of("close.bin"), 1) }
            .isInstanceOf(IOException::class.java)
            .hasMessage("close failed")
    }

    private fun stableAccess(size: Long, finalKey: Any = "file-key"): EvidenceArchiveStableFileAccess {
        var reads = 0
        return EvidenceArchiveStableFileAccess(
            attributes = {
                reads += 1
                TestAttributes(size, if (reads == 1) "file-key" else finalKey)
            },
            open = { throw AssertionError("channel supplied by test") },
        )
    }

    private class ScriptedChannel(
        private val content: ByteArray,
        private val sizes: LongArray,
        private val zeroFirstRead: Boolean,
        private val failClose: Boolean = false,
    ) : EvidenceArchiveStableReadChannel {
        private var offset = 0
        private var sizeCalls = 0
        private var reads = 0

        override fun size(): Long = sizes[minOf(sizeCalls++, sizes.lastIndex)]

        override fun read(buffer: ByteBuffer): Int {
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

    private class TestAttributes(private val length: Long, private val key: Any) : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = TIME
        override fun lastAccessTime(): FileTime = TIME
        override fun creationTime(): FileTime = TIME
        override fun isRegularFile(): Boolean = true
        override fun isDirectory(): Boolean = false
        override fun isSymbolicLink(): Boolean = false
        override fun isOther(): Boolean = false
        override fun size(): Long = length
        override fun fileKey(): Any = key
    }

    private companion object {
        val TIME: FileTime = FileTime.fromMillis(1)
    }
}
