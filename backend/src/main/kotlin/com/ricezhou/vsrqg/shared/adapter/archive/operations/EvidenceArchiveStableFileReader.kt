package com.ricezhou.vsrqg.shared.adapter.archive.operations

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal interface EvidenceArchiveStableReadChannel : AutoCloseable {
    fun size(): Long
    fun read(buffer: ByteBuffer): Int
}

internal data class EvidenceArchiveStableFileAccess(
    val attributes: (Path) -> BasicFileAttributes,
    val open: (Path) -> EvidenceArchiveStableReadChannel,
    val fileKey: (Path, BasicFileAttributes) -> Any? = { _, attributes -> attributes.fileKey() },
) {
    companion object {
        fun nio(): EvidenceArchiveStableFileAccess = EvidenceArchiveStableFileAccess(
            attributes = { path ->
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            },
            open = { path ->
                val channel = try {
                    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
                } catch (failure: UnsupportedOperationException) {
                    throw IOException("NOFOLLOW file open is unavailable", failure)
                } catch (failure: IllegalArgumentException) {
                    throw IOException("NOFOLLOW file open is unavailable", failure)
                }
                object : EvidenceArchiveStableReadChannel {
                    override fun size(): Long = channel.size()
                    override fun read(buffer: ByteBuffer): Int = channel.read(buffer)
                    override fun close() = channel.close()
                }
            },
        )
    }
}

internal class EvidenceArchiveStableFileReader(
    private val access: EvidenceArchiveStableFileAccess = EvidenceArchiveStableFileAccess.nio(),
) {
    fun read(
        path: Path,
        maxBytes: Int,
        minimumBytes: Int = 1,
        expectedBytes: ByteArray? = null,
    ): ByteArray {
        if (maxBytes < 0 || minimumBytes !in 0..maxBytes || path.normalize() != path) {
            throw IOException("invalid stable read request")
        }
        val before = access.attributes(path)
        val initialSize = before.size()
        val initialKey = access.fileKey(path, before)
        if (!before.isRegularFile || before.isSymbolicLink) throw IOException("input is not a regular file")
        if (initialKey == null) throw IOException("input identity is unavailable")
        if (initialSize !in minimumBytes.toLong()..maxBytes.toLong()) throw IOException("input size is out of bounds")
        if (expectedBytes != null && initialSize != expectedBytes.size.toLong()) {
            throw IOException("input size does not match expected bytes")
        }

        val bytes = ByteArray(initialSize.toInt())
        access.open(path).use { channel ->
            if (channel.size() != initialSize) throw IOException()
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                when (channel.read(buffer)) {
                    -1 -> throw IOException()
                    0 -> throw IOException()
                }
            }
            val sentinel = ByteBuffer.allocate(1)
            if (channel.read(sentinel) != -1 || channel.size() != initialSize) throw IOException()
        }

        val after = access.attributes(path)
        if (!after.isRegularFile || after.isSymbolicLink || access.fileKey(path, after) != initialKey ||
            after.size() != initialSize || after.lastModifiedTime() != before.lastModifiedTime() ||
            after.creationTime() != before.creationTime()
        ) throw IOException()
        if (expectedBytes != null && !bytes.contentEquals(expectedBytes)) throw IOException()
        return bytes
    }
}
