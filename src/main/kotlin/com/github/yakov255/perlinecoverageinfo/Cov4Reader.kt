package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
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

    @Volatile
    private var tests: List<String> = emptyList()

    @Volatile
    private var entriesByPath: Map<String, IndexEntry> = emptyMap()  // normalized path → entry

    /**
     * Identity of the file that [entriesByPath] was indexed from. A rewrite replaces the
     * file (new inode), so every offset becomes invalid and the index is reloaded lazily
     * instead of serving stale or wrong data.
     */
    @Volatile
    private var openedStamp: FileStamp? = null

    private val reloadLock = Any()

    private fun currentStamp(): FileStamp = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
    } catch (_: Exception) {
        FileStamp(file.length(), FileTime.fromMillis(file.lastModified()), null)
    }

    private fun isFileReplaced(): Boolean = currentStamp() != openedStamp

    @Volatile
    private var replacedWarned = false

    data class IndexEntry(
        val pathHash: Long,
        val pathOffset: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val crc32: Int,
        val filePath: String,
    )

    init {
        reloadIndex()
    }

    /** Re-reads the header, test table and index from the current file contents. */
    private fun reloadIndex() {
        synchronized(reloadLock) {
            val stamp = currentStamp()
            if (stamp == openedStamp && openedStamp != null) return

            val (rawTests, idx) = CoveragePerfLog.span("cov4.init", "file" to file.name, "bytes" to stamp.size) {
                readHeaderAndIndex()
            }

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
            tests = rawTests.map(testNormalizer)
            entriesByPath = byPath
            openedStamp = stamp
        }
    }

    val allFilePaths: Set<String>
        get() {
            ensureFreshIndex()
            return entriesByPath.keys
        }

    fun hasData(): Boolean {
        ensureFreshIndex()
        return entriesByPath.isNotEmpty()
    }

    /** Reloads the index when the file was replaced by a newer write. */
    private fun ensureFreshIndex() {
        if (!isFileReplaced()) return
        if (!replacedWarned) {
            replacedWarned = true
            log.info("COV4: ${file.name} was replaced by a newer write, reloading its index")
        }
        reloadIndex()
    }

    /**
     * Reads coverage for a single file on demand.
     * Returns lineNum → testNames, or null if file not found in this .cov4.
     */
    fun getCoverage(filePath: String): Map<Int, List<String>>? {
        val key = pathNormalizer(filePath)

        ensureFreshIndex()
        val first = entriesByPath[key] ?: return null
        val stampBefore = currentStamp()
        val parsed = CoveragePerfLog.counted("cov4.readFile") { readSection(first) }
        if (currentStamp() == stampBefore) return parsed

        // The file was replaced while reading: the offsets may belong to older bytes.
        ensureFreshIndex()
        val fresh = entriesByPath[key] ?: return null
        return CoveragePerfLog.counted("cov4.readFile") { readSection(fresh) }
    }

    private data class FileStamp(val size: Long, val modified: FileTime, val key: Any?)

    private fun readSection(entry: IndexEntry): Map<Int, List<String>> =
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(entry.dataSize)
            raf.seek(entry.dataOffset.toLong())
            raf.readFully(bytes)
            parseFileSection(bytes)
        }

    /**
     * Reads header (28 bytes), global test list, and INDX section.
     *
     * Streams from the file instead of loading it whole: a large cache is ~100 MB and
     * loading it just to read the index cost a full-file allocation per open.
     */
    private fun readHeaderAndIndex(): Pair<List<String>, List<IndexEntry>> {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(HEADER_SIZE)
            raf.readFully(header)
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

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

            @Suppress("UNUSED_VARIABLE")
            val fileCount = buf.int
            val testCount = buf.int
            @Suppress("UNUSED_VARIABLE")
            val dirCount = buf.int
            val indexOffset = buf.int
            @Suppress("UNUSED_VARIABLE")
            val dirSectionOffset = buf.int

            // --- GLOBAL TEST LIST (immediately after the header) ---
            raf.seek(HEADER_SIZE.toLong())
            val tests = ArrayList<String>(testCount)
            for (i in 0 until testCount) {
                tests.add(readString(raf))
            }

            // --- INDX SECTION ---
            raf.seek(indexOffset.toLong())
            val indexMarker = readLeInt(raf)
            if (indexMarker != MARKER_INDEX) throw CoverageApiException(
                "Invalid INDX marker at offset $indexOffset: 0x${Integer.toHexString(indexMarker).uppercase()}",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )

            val entryCount = readLeInt(raf)
            val entries = ArrayList<IndexEntry>(entryCount)

            for (i in 0 until entryCount) {
                val pathHash = readLeLong(raf)
                val pathOffset = readLeInt(raf)
                val dataOffset = readLeInt(raf)
                val dataSize = readLeInt(raf)
                val crc32 = readLeInt(raf)
                val nextEntryPosition = raf.filePointer

                // The FILE section at dataOffset starts with the marker; its path follows.
                raf.seek((dataOffset + pathOffset).toLong())
                val filePath = readString(raf)
                raf.seek(nextEntryPosition)

                entries.add(IndexEntry(pathHash, pathOffset, dataOffset, dataSize, crc32, filePath))
            }

            log.info("COV4: opened ${file.name}: ${tests.size} tests, ${entries.size} files")
            return Pair(tests, entries)
        }
    }

    private fun readString(raf: RandomAccessFile): String {
        val length = readLeInt(raf)
        val bytes = ByteArray(length)
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readLeInt(raf: RandomAccessFile): Int = Integer.reverseBytes(raf.readInt())

    private fun readLeLong(raf: RandomAccessFile): Long = java.lang.Long.reverseBytes(raf.readLong())

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
        private const val HEADER_SIZE = 28
        private const val MAGIC = 0x434F5633       // "COV3"
        private const val VERSION = 4
        private const val MARKER_FILE = 0x46494C45 // "FILE"
        private const val MARKER_INDEX = 0x494E4458 // "INDX"
    }
}
