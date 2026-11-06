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
class Cov4Reader(
    private val file: File,
    private val pathNormalizer: (String) -> String = { it },
    private val testNormalizer: (String) -> String = { it },
) : AutoCloseable {

    private val log = CoverageLog.get(Cov4Reader::class.java)

    private val tests: List<String>
    private val entriesByPath: Map<String, IndexEntry>  // normalized path → entry

    /** Size and mtime captured at open time; a rewrite invalidates the index offsets. */
    private val openedLength = file.length()
    private val openedModifiedMs = file.lastModified()

    private fun isFileReplaced(): Boolean =
        file.length() != openedLength || file.lastModified() != openedModifiedMs

    data class IndexEntry(
        val pathHash: Long,
        val pathOffset: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val crc32: Int,
        val filePath: String,
    )

    init {
        val (rawTests, idx) = CoveragePerfLog.span("cov4.init", "file" to file.name, "bytes" to file.length()) {
            readHeaderAndIndex()
        }
        tests = rawTests.map(testNormalizer)

        val byPath = LinkedHashMap<String, IndexEntry>(idx.size)
        for (entry in idx) {
            val normalizedPath = pathNormalizer(entry.filePath)
            val previous = byPath.putIfAbsent(normalizedPath, entry)
            if (previous != null) {
                log.warn(
                    "COV4: duplicate normalized path '$normalizedPath' " +
                        "(${previous.filePath} and ${entry.filePath}); keeping the first"
                )
            }
        }
        entriesByPath = byPath
    }

    val allFilePaths: Set<String> get() = entriesByPath.keys

    fun hasData(): Boolean = entriesByPath.isNotEmpty()

    /**
     * Reads coverage for a single file on demand.
     * Returns lineNum → testNames, or null if file not found in this .cov4.
     */
    fun getCoverage(filePath: String): Map<Int, List<String>>? {
        if (isFileReplaced()) {
            log.warn("COV4: ${file.name} was replaced after this reader was opened, refusing to read stale offsets")
            return null
        }
        val entry = entriesByPath[pathNormalizer(filePath)] ?: return null

        return CoveragePerfLog.counted("cov4.readFile") {
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(entry.dataSize)
                raf.seek(entry.dataOffset.toLong())
                raf.readFully(bytes)
                parseFileSection(bytes)
            }
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
        // The writer deduplicates test sets per file, so one set is usually shared by
        // many lines. Expand each set once instead of allocating a list per line.
        val resolvedTestSets = HashMap<Int, List<String>>()
        val lineCount = buf.int
        val lineMap = LinkedHashMap<Int, List<String>>(lineCount)
        for (l in 0 until lineCount) {
            val lineNumber = buf.int
            val setIndex = buf.int // signed: -1 = uncovered

            if (setIndex < 0) {
                lineMap[lineNumber] = emptyList()
            } else if (setIndex < testSets.size) {
                lineMap[lineNumber] = resolvedTestSets.getOrPut(setIndex) {
                    testSets[setIndex].map { testIdx ->
                        if (testIdx in tests.indices) tests[testIdx] else "unknown-test-$testIdx"
                    }
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
