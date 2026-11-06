package com.github.yakov255.perlinecoverageinfo

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.CRC32

/**
 * Writes merged coverage data to the COV4 binary format.
 *
 * Format (little-endian throughout):
 * - Header (28 bytes): magic "COV3", version 4, counts, index/dir offsets
 * - Global test list: length-prefixed UTF-8 strings
 * - FILE sections: marker + path + test sets + line mappings
 * - INDX section: sorted by FNV-1a 64-bit path hash, 24 bytes per entry
 * - DIRC section: per-directory coverage stats
 * - ENDF trailer + overall CRC32
 *
 * Follows the Go reference implementation in api/coverage_io.go.
 */
object Cov4Writer {

    private const val MAGIC = 0x434F5633          // "COV3"
    private const val VERSION = 4
    private const val MARKER_FILE = 0x46494C45    // "FILE"
    private const val MARKER_INDEX = 0x494E4458   // "INDX"
    private const val MARKER_DIR = 0x44495243     // "DIRC"
    private const val MARKER_END = 0x454E4446     // "ENDF"

    /**
     * Writes merged coverage data to a COV4 file.
     *
     * @param coverage filePath → (1-based lineNum → testNames)
     * @param outputFile destination file
     */
    fun write(coverage: Map<String, Map<Int, List<String>>>, outputFile: File) {
        val destination = outputFile.toPath().toAbsolutePath()
        // Publish only complete snapshots, including to readers in other IDE
        // processes. A failed write must leave the previous cache file intact.
        val temporary = Files.createTempFile(destination.parent, ".cov4-", ".tmp")
        try {
            FileOutput(temporary).use { serialize(coverage, it) }
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * Serializes coverage data to COV4 binary format bytes.
     */
    fun toBytes(coverage: Map<String, Map<Int, List<String>>>): ByteArray {
        val output = MemoryOutput()
        serialize(coverage, output)
        return output.bytes()
    }

    private fun serialize(coverage: Map<String, Map<Int, List<String>>>, buf: BinaryOutput) {
        // Build global test list
        val testToIndex = LinkedHashMap<String, Int>()
        for ((_, lineMap) in coverage) {
            for ((_, tests) in lineMap) {
                for (test in tests) {
                    testToIndex.putIfAbsent(test, testToIndex.size)
                }
            }
        }
        val globalTests = testToIndex.keys.toList()

        // Compute directory coverage stats
        val dirCoverage = calculateDirectoryCoverage(coverage)

        // --- HEADER (28 bytes) with placeholders ---
        buf.writeInt32(MAGIC)
        buf.writeInt32(VERSION)
        buf.writeInt32(coverage.size)               // file count
        buf.writeInt32(globalTests.size)             // test count
        buf.writeInt32(dirCoverage.size)             // directory count
        val indexOffsetPos = buf.size()
        buf.writeInt32(0)                            // placeholder: index offset
        val dirOffsetPos = buf.size()
        buf.writeInt32(0)                            // placeholder: directory offset

        // --- GLOBAL TEST LIST ---
        for (test in globalTests) {
            buf.writeString(test)
        }

        // --- FILE SECTIONS ---
        data class FileEntry(
            val path: String,
            val dataOffset: Int,
            val dataSize: Int,
            val crc32: Int,
        )

        val fileEntries = mutableListOf<FileEntry>()

        for ((filePath, lineMap) in coverage) {
            val dataOffset = buf.size()
            val crc = CRC32()
            buf.sectionCrc = crc

            // FILE marker
            buf.writeInt32(MARKER_FILE)

            // File path
            buf.writeString(filePath)

            // Build deduplicated test sets for this file
            val setKeyToIndex = LinkedHashMap<String, Int>()
            val testSets = mutableListOf<List<Int>>()
            val lineSetIndices = LinkedHashMap<Int, Int>() // lineNum → setIndex

            for ((lineNum, tests) in lineMap.entries.sortedBy { it.key }) {
                if (tests.isEmpty()) {
                    lineSetIndices[lineNum] = -1
                } else {
                    val globalIndices = tests.mapNotNull { testToIndex[it] }.sorted()
                    val setKey = globalIndices.joinToString(",")
                    val setIdx = setKeyToIndex.getOrPut(setKey) {
                        testSets.add(globalIndices)
                        testSets.size - 1
                    }
                    lineSetIndices[lineNum] = setIdx
                }
            }

            // Test sets count
            buf.writeInt32(testSets.size)
            for (set in testSets) {
                buf.writeInt32(set.size)
                for (idx in set) {
                    buf.writeInt32(idx)
                }
            }

            // Line mappings (already sorted by key)
            buf.writeInt32(lineSetIndices.size)
            for ((lineNum, setIdx) in lineSetIndices) {
                buf.writeInt32(lineNum)
                buf.writeSignedInt32(setIdx)
            }

            val dataSize = buf.size() - dataOffset
            buf.sectionCrc = null
            fileEntries.add(FileEntry(filePath, dataOffset, dataSize, crc.value.toInt()))
        }

        // --- INDX SECTION ---
        val indexOffset = buf.size()
        buf.writeInt32(MARKER_INDEX)
        buf.writeInt32(fileEntries.size)

        // Sort by FNV-1a hash for binary search
        val sortedEntries = fileEntries.sortedBy { fnv1a64(it.path).toULong() }

        // Write index entries (24 bytes each), including CRCs computed while streaming FILEs.
        for (entry in sortedEntries) {
            buf.writeInt64(fnv1a64(entry.path))     // path hash (uint64)
            buf.writeInt32(4)                        // pathOffset (always 4, path follows FILE marker)
            buf.writeInt32(entry.dataOffset)         // dataOffset (absolute)
            buf.writeInt32(entry.dataSize)            // dataSize
            buf.writeInt32(entry.crc32)
        }

        // --- DIRC SECTION ---
        val dirSectionOffset = buf.size()
        buf.writeInt32(MARKER_DIR)
        buf.writeInt32(dirCoverage.size)

        for ((dirPath, stats) in dirCoverage.entries.sortedBy { it.key }) {
            buf.writeString(dirPath)
            buf.writeInt32(stats.covered)
            buf.writeInt32(stats.uncovered)
            buf.writeInt32(stats.covered + stats.uncovered)
        }

        // --- ENDF TRAILER ---
        buf.writeInt32(MARKER_END)
        buf.writeInt32(0)  // overall CRC32 placeholder

        // Patch offsets before calculating the overall checksum. File-backed output uses a
        // fixed-size buffer for both writing and the checksum pass, regardless of archive size.
        buf.patchInt32(indexOffsetPos, indexOffset)
        buf.patchInt32(dirOffsetPos, dirSectionOffset)
        buf.patchInt32(buf.size() - 4, buf.crc32(buf.size() - 4))
    }

    /**
     * FNV-1a 64-bit hash (same as Go's hash/fnv).
     */
    fun fnv1a64(s: String): Long {
        var hash = -3750763034362895579L // 0xcbf29ce484222325 as signed Long
        val prime = 1099511628211L       // 0x100000001b3
        for (b in s.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xFF)
            hash *= prime
        }
        return hash
    }

    private data class DirStats(var covered: Int = 0, var uncovered: Int = 0)

    private fun calculateDirectoryCoverage(
        coverage: Map<String, Map<Int, List<String>>>
    ): Map<String, DirStats> {
        val dirStats = mutableMapOf<String, DirStats>()

        for ((filePath, lineMap) in coverage) {
            var covered = 0
            var uncovered = 0
            for ((_, tests) in lineMap) {
                if (tests.isNotEmpty()) covered++ else uncovered++
            }

            // Walk up directory hierarchy
            var dir = filePath.substringBeforeLast('/', "")
            while (dir.isNotEmpty()) {
                val stats = dirStats.getOrPut(dir) { DirStats() }
                stats.covered += covered
                stats.uncovered += uncovered
                dir = dir.substringBeforeLast('/', "")
            }
        }

        return dirStats
    }

    private abstract class BinaryOutput(private val output: OutputStream) {
        private var position = 0
        var sectionCrc: CRC32? = null

        fun size(): Int = position

        private fun writeByte(value: Int) {
            output.write(value)
            sectionCrc?.update(value)
            position = Math.addExact(position, 1)
        }

        fun writeInt32(value: Int) {
            for (shift in 0 until 32 step 8) writeByte((value ushr shift) and 0xff)
        }

        fun writeSignedInt32(value: Int) = writeInt32(value)

        fun writeInt64(value: Long) {
            for (shift in 0 until 64 step 8) writeByte(((value ushr shift) and 0xff).toInt())
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt32(bytes.size)
            output.write(bytes)
            sectionCrc?.update(bytes)
            position = Math.addExact(position, bytes.size)
        }

        abstract fun patchInt32(offset: Int, value: Int)
        abstract fun crc32(length: Int): Int
    }

    private class ByteBufferOutput : ByteArrayOutputStream() {
        fun patchInt32(offset: Int, value: Int) {
            ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        }

        fun crc32(length: Int): Int = CRC32().apply { update(buf, 0, length) }.value.toInt()
    }

    private class MemoryOutput(private val buffer: ByteBufferOutput = ByteBufferOutput()) : BinaryOutput(buffer) {
        override fun patchInt32(offset: Int, value: Int) = buffer.patchInt32(offset, value)
        override fun crc32(length: Int): Int = buffer.crc32(length)
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private class FileOutput private constructor(
        private val channel: FileChannel,
        private val buffer: BufferedOutputStream,
    ) : BinaryOutput(buffer), AutoCloseable {
        private constructor(channel: FileChannel) : this(channel, BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024))
        constructor(path: Path) : this(FileChannel.open(path, READ, WRITE))

        override fun patchInt32(offset: Int, value: Int) {
            buffer.flush()
            val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
            bytes.flip()
            var position = offset.toLong()
            while (bytes.hasRemaining()) position += channel.write(bytes, position)
        }

        override fun crc32(length: Int): Int {
            buffer.flush()
            val bytes = ByteBuffer.allocate(64 * 1024)
            val crc = CRC32()
            var position = 0L
            while (position < length) {
                bytes.clear()
                bytes.limit(minOf(bytes.capacity().toLong(), length - position).toInt())
                val count = channel.read(bytes, position)
                check(count > 0) { "Unexpected end of COV4 output" }
                crc.update(bytes.array(), 0, count)
                position += count
            }
            return crc.value.toInt()
        }

        override fun close() = buffer.close()
    }
}
