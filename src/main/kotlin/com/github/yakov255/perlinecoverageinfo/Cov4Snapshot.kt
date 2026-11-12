package com.github.yakov255.perlinecoverageinfo

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.DELETE_ON_CLOSE

/**
 * Private disk snapshot: cache replacement, deletion and even in-place writes cannot change it.
 * Only requested blocks are brought into the heap. Shared readers have no explicit ownership,
 * so owners explicitly close the snapshot; reads keep the channel alive until completion.
 */
internal class Cov4Snapshot(file: File) : AutoCloseable {
    private val resources: Resources
    private val lifetime: SharedLifetime
    private val owner: AutoCloseable

    init {
        val path = Files.createTempFile("coverage-reader-", ".cov4")
        try {
            Files.copy(file.toPath(), path, REPLACE_EXISTING)
            resources = Resources(path, FileChannel.open(path, READ, DELETE_ON_CLOSE))
        } catch (t: Throwable) {
            Files.deleteIfExists(path)
            throw t
        }
        lifetime = SharedLifetime(resources::run)
        owner = lifetime.acquire()
    }

    val size: Long = resources.channel.size()

    fun read(offset: Long, length: Int): ByteBuffer = lifetime.withLease {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "Invalid COV4 block bounds" }
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var position = offset
        while (buffer.position() < buffer.capacity()) {
            buffer.limit(minOf(buffer.capacity(), buffer.position() + 64 * 1024))
            CoverageCancellation.check()
            val count = resources.channel.read(buffer, position)
            check(count > 0) { "Unexpected end of COV4 snapshot" }
            position += count
        }
        buffer.flip()
        buffer
    }

    fun <T> readHeader(reader: (DataInputStream) -> T): T = readSection(0, size, reader)

    fun <T> readSection(offset: Long, length: Long, reader: (DataInputStream) -> T): T = lifetime.withLease {
        require(offset >= 0 && length >= 0 && offset + length <= size) { "Invalid COV4 block bounds" }
        // DELETE_ON_CLOSE can unlink the name immediately; read through the open descriptor.
        val input = object : InputStream() {
            private var position = offset
            private val end = offset + length
            override fun available(): Int = minOf(end - position, Int.MAX_VALUE.toLong()).toInt()
            override fun read(): Int {
                val byte = ByteArray(1)
                return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                CoverageCancellation.check()
                if (length == 0) return 0
                if (position == end) return -1
                val count = resources.channel.read(
                    ByteBuffer.wrap(bytes, offset, minOf(length.toLong(), end - position).toInt()), position,
                )
                if (count > 0) position += count
                return count
            }
        }
        DataInputStream(BufferedInputStream(input, 64 * 1024)).use(reader)
    }

    // Closing releases ownership; an in-flight read retains the channel until it finishes.
    override fun close() = owner.close()

    private class Resources(val path: Path, val channel: FileChannel) : Runnable {
        override fun run() {
            try {
                channel.close()
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

}
