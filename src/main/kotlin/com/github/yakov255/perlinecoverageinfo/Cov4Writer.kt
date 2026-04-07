package com.github.yakov255.perlinecoverageinfo

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        outputFile.writeBytes(toBytes(coverage))
    }

    /**
     * Serializes coverage data to COV4 binary format bytes.
     */
    fun toBytes(coverage: Map<String, Map<Int, List<String>>>): ByteArray {
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

        val buf = ByteArrayOutputStream()

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
        )

        val fileEntries = mutableListOf<FileEntry>()

        for ((filePath, lineMap) in coverage) {
            val dataOffset = buf.size()

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
            fileEntries.add(FileEntry(filePath, dataOffset, dataSize))
        }

        // --- INDX SECTION ---
        val indexOffset = buf.size()
        buf.writeInt32(MARKER_INDEX)
        buf.writeInt32(fileEntries.size)

        // Sort by FNV-1a hash for binary search
        val sortedEntries = fileEntries.sortedBy { fnv1a64(it.path).toULong() }

        // Write index entries (24 bytes each), CRC placeholders
        val indexEntriesStart = buf.size()
        for (entry in sortedEntries) {
            buf.writeInt64(fnv1a64(entry.path))     // path hash (uint64)
            buf.writeInt32(4)                        // pathOffset (always 4, path follows FILE marker)
            buf.writeInt32(entry.dataOffset)         // dataOffset (absolute)
            buf.writeInt32(entry.dataSize)            // dataSize
            buf.writeInt32(0)                        // CRC32 placeholder
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

        // --- BACK-PATCH ---
        val data = buf.toByteArray()
        val le = ByteOrder.LITTLE_ENDIAN

        // Patch index offset in header
        ByteBuffer.wrap(data, indexOffsetPos, 4).order(le).putInt(indexOffset)

        // Patch directory offset in header
        ByteBuffer.wrap(data, dirOffsetPos, 4).order(le).putInt(dirSectionOffset)

        // Patch per-file CRC32s in index entries
        for ((i, entry) in sortedEntries.withIndex()) {
            val fileData = data.copyOfRange(entry.dataOffset, entry.dataOffset + entry.dataSize)
            val crc = CRC32()
            crc.update(fileData)
            val crcPos = indexEntriesStart + i * 24 + 20 // 24 bytes/entry, CRC at offset +20
            ByteBuffer.wrap(data, crcPos, 4).order(le).putInt(crc.value.toInt())
        }

        // Patch overall CRC32 (covers everything except last 4 bytes)
        val overallCrc = CRC32()
        overallCrc.update(data, 0, data.size - 4)
        ByteBuffer.wrap(data, data.size - 4, 4).order(le).putInt(overallCrc.value.toInt())

        return data
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

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        val buf = ByteArray(4)
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        write(buf)
    }

    private fun ByteArrayOutputStream.writeSignedInt32(value: Int) {
        writeInt32(value)
    }

    private fun ByteArrayOutputStream.writeInt64(value: Long) {
        val buf = ByteArray(8)
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
        write(buf)
    }

    private fun ByteArrayOutputStream.writeString(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeInt32(bytes.size)
        write(bytes)
    }
}
