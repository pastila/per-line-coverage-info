package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Random-access reader for COV4 binary coverage files.
 *
 * On construction, reads only the header, global test list, and file index (INDX).
 * Individual file coverage is parsed on demand via [getCoverage], seeking directly
 * to the file's data offset using the index.
 *
 * This avoids loading the entire coverage dataset into memory — only the requested
 * file's block is parsed when an editor needs it.
 */
class Cov4Reader(private val file: File) : AutoCloseable {

    private val log = CoverageLog.get(Cov4Reader::class.java)

    private val tests: List<String>
    private val fileIndex: Map<Long, IndexEntry>  // pathHash → entry
    private val pathsByHash: Map<Long, String>     // pathHash → filePath

    data class IndexEntry(
        val pathHash: Long,
        val pathOffset: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val crc32: Int,
        val filePath: String,
    )

    init {
        val (t, idx) = readHeaderAndIndex()
        tests = t
        fileIndex = idx.associateBy { it.pathHash }
        pathsByHash = idx.associate { it.pathHash to it.filePath }
    }

    val allFilePaths: Set<String> get() = pathsByHash.values.toSet()

    fun hasData(): Boolean = fileIndex.isNotEmpty()

    /**
     * Reads coverage for a single file on demand.
     * Returns lineNum → testNames, or null if file not found in this .cov4.
     */
    fun getCoverage(filePath: String): Map<Int, List<String>>? {
        val hash = Cov4Writer.fnv1a64(filePath)
        val entry = fileIndex[hash] ?: return null
        // Verify path matches (hash collision check)
        if (entry.filePath != filePath) return null

        return RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(entry.dataSize)
            raf.seek(entry.dataOffset.toLong())
            raf.readFully(bytes)
            parseFileSection(bytes)
        }
    }

    /**
     * Reads header (28 bytes), global test list, and INDX section.
     */
    private fun readHeaderAndIndex(): Pair<List<String>, List<IndexEntry>> {
        val data = file.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // --- HEADER (28 bytes) ---
        val magic = buf.int
        if (magic != MAGIC) throw CoverageApiException(
            "Invalid COV4 magic: 0x${Integer.toHexString(magic).uppercase()}, expected 0x${Integer.toHexString(MAGIC).uppercase()}",
            kind = CoverageErrorKind.ARTIFACT_PARSE,
        )
        val version = buf.int
        if (version != VERSION) throw CoverageApiException(
            "Unsupported COV4 version: $version, expected $VERSION",
            kind = CoverageErrorKind.ARTIFACT_PARSE,
        )

        val fileCount = buf.int
        val testCount = buf.int
        @Suppress("UNUSED_VARIABLE")
        val dirCount = buf.int
        val indexOffset = buf.int
        @Suppress("UNUSED_VARIABLE")
        val dirSectionOffset = buf.int

        // --- GLOBAL TEST LIST ---
        val tests = ArrayList<String>(testCount)
        for (i in 0 until testCount) {
            tests.add(readString(buf))
        }

        // --- INDX SECTION ---
        buf.position(indexOffset)
        val indexMarker = buf.int
        if (indexMarker != MARKER_INDEX) throw CoverageApiException(
            "Invalid INDX marker at offset $indexOffset: 0x${Integer.toHexString(indexMarker).uppercase()}",
            kind = CoverageErrorKind.ARTIFACT_PARSE,
        )

        val entryCount = buf.int
        val entries = ArrayList<IndexEntry>(entryCount)

        for (i in 0 until entryCount) {
            val pathHash = buf.long
            val pathOffset = buf.int
            val dataOffset = buf.int
            val dataSize = buf.int
            val crc32 = buf.int

            // Read the file path from the FILE section
            val pathBuf = ByteBuffer.wrap(data, dataOffset + pathOffset, dataSize - pathOffset)
                .order(ByteOrder.LITTLE_ENDIAN)
            val filePath = readString(pathBuf)

            entries.add(IndexEntry(pathHash, pathOffset, dataOffset, dataSize, crc32, filePath))
        }

        log.info("COV4: opened ${file.name}: ${tests.size} tests, ${entries.size} files")
        return Pair(tests, entries)
    }

    /**
     * Parses a FILE section's bytes (starting from FILE marker) into coverage data.
     */
    private fun parseFileSection(sectionBytes: ByteArray): Map<Int, List<String>> {
        val buf = ByteBuffer.wrap(sectionBytes).order(ByteOrder.LITTLE_ENDIAN)

        // FILE marker
        val marker = buf.int
        if (marker != MARKER_FILE) throw CoverageApiException(
            "Expected FILE marker, got 0x${Integer.toHexString(marker).uppercase()}",
            kind = CoverageErrorKind.ARTIFACT_PARSE,
        )

        // Skip file path
        readString(buf)

        // Test sets
        val testSetsCount = buf.int
        val testSets = ArrayList<List<Int>>(testSetsCount)
        for (s in 0 until testSetsCount) {
            val setSize = buf.int
            val indices = ArrayList<Int>(setSize)
            for (idx in 0 until setSize) {
                indices.add(buf.int)
            }
            testSets.add(indices)
        }

        // Line mappings
        val lineCount = buf.int
        val lineMap = LinkedHashMap<Int, List<String>>(lineCount)
        for (l in 0 until lineCount) {
            val lineNumber = buf.int
            val setIndex = buf.int // signed: -1 = uncovered

            if (setIndex < 0) {
                lineMap[lineNumber] = emptyList()
            } else if (setIndex < testSets.size) {
                lineMap[lineNumber] = testSets[setIndex].map { testIdx ->
                    if (testIdx < tests.size) tests[testIdx] else "unknown-test-$testIdx"
                }
            }
        }

        return lineMap
    }

    override fun close() {
        // No persistent resources to close
    }

    private fun readString(buf: ByteBuffer): String {
        val length = buf.int
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val MAGIC = 0x434F5633       // "COV3"
        private const val VERSION = 4
        private const val MARKER_FILE = 0x46494C45 // "FILE"
        private const val MARKER_INDEX = 0x494E4458 // "INDX"
    }
}
