package com.github.yakov255.perlinecoverageinfo

import java.io.DataInputStream
import java.io.File

/**
 * Random-access reader for COV4 binary coverage files.
 *
 * Holds an immutable disk snapshot of the binary file, decoding only the header,
 * global test list, and file index (INDX) on construction. Individual file coverage
 * is parsed on demand via [getCoverage] using offsets into that same snapshot.
 *
 * Keeping the bytes and index together lets shared readers survive cache rewrites
 * and cleanup. Only the requested file's block is decoded when an editor needs it.
 */
class Cov4Reader private constructor(private val contents: Contents) : AutoCloseable {
    private val owner = contents.lifetime.acquire()

    constructor(
        file: File,
        pathNormalizer: (String) -> String = { it },
        testNormalizer: (String) -> String = { it },
    ) : this(Contents(Cov4Snapshot(file), file.name, pathNormalizer, testNormalizer))

    internal constructor(snapshot: Cov4Snapshot, coverageSize: Long) :
        this(Contents(snapshot, "local coverage", { it }, { it }, coverageSize))

    /** Independent ownership of the same immutable index and snapshot. */
    fun retain(): Cov4Reader = Cov4Reader(contents)

    val allFilePaths: Set<String> get() = contents.allFilePaths
    fun hasData(): Boolean = contents.hasData()
    fun getCoverage(filePath: String): Map<Int, List<String>>? =
        contents.lifetime.withLease { contents.getCoverage(filePath) }

    override fun close() = owner.close()

    data class IndexEntry(
        val pathHash: Long,
        val pathOffset: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val crc32: Int,
        val filePath: String,
    )

}

private class Contents(
    private val snapshot: Cov4Snapshot,
    private val sourceName: String,
    private val pathNormalizer: (String) -> String,
    private val testNormalizer: (String) -> String,
    private val coverageSize: Long = snapshot.size,
) {

    private val log = CoverageLog.get(Cov4Reader::class.java)

    val lifetime = SharedLifetime(snapshot::close)
    private val tests: List<String>
    private val entriesByPath: Map<String, Cov4Reader.IndexEntry>  // normalized path → entry

    init {
        try {
            val (rawTests, idx) = readHeaderAndIndex()
            tests = rawTests.map(testNormalizer)

            val byPath = LinkedHashMap<String, Cov4Reader.IndexEntry>(idx.size)
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
        } catch (t: Throwable) {
            snapshot.close()
            throw t
        }
    }

    val allFilePaths: Set<String> get() = entriesByPath.keys

    fun hasData(): Boolean = entriesByPath.isNotEmpty()

    /**
     * Reads coverage for a single file on demand.
     * Returns lineNum → testNames, or null if file not found in this .cov4.
     */
    fun getCoverage(filePath: String): Map<Int, List<String>>? {
        val entry = entriesByPath[pathNormalizer(filePath)] ?: return null

        // Independent bounded streams let concurrent requests decode only their own block.
        require(entry.dataOffset >= 0 && entry.dataSize >= 0 && entry.dataOffset.toLong() + entry.dataSize <= coverageSize) {
            "Invalid COV4 file block bounds"
        }
        return snapshot.readSection(entry.dataOffset.toLong(), entry.dataSize.toLong(), ::parseFileSection)
    }

    /**
     * Reads header (28 bytes), global test list, and INDX section.
     */
    private fun readHeaderAndIndex(): Pair<List<String>, List<Cov4Reader.IndexEntry>> {
        var indexOffset = 0
        val tests = snapshot.readSection(0, coverageSize) { input ->
            val magic = input.readIntLe()
            if (magic != MAGIC) throw CoverageApiException(
                "Invalid COV4 magic: 0x${Integer.toHexString(magic).uppercase()}, expected 0x${Integer.toHexString(MAGIC).uppercase()}",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
            val version = input.readIntLe()
            if (version != VERSION) throw CoverageApiException(
                "Unsupported COV4 version: $version, expected $VERSION",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
            input.readIntLe() // file count
            val testCount = input.readIntLe()
            input.readIntLe() // directory count
            indexOffset = input.readIntLe()
            input.readIntLe() // directory section offset
            require(testCount >= 0 && testCount.toLong() * 4 <= coverageSize - 28) { "Invalid COV4 test count" }
            List(testCount) { input.readString() }
        }

        // --- INDX SECTION ---
        val indexHeader = snapshot.read(indexOffset.toLong(), 8)
        val indexMarker = indexHeader.int
        if (indexMarker != MARKER_INDEX) throw CoverageApiException(
            "Invalid INDX marker at offset $indexOffset: 0x${Integer.toHexString(indexMarker).uppercase()}",
            kind = CoverageErrorKind.ARTIFACT_PARSE,
        )

        val entryCount = indexHeader.int
        require(entryCount >= 0 && entryCount.toLong() * 24 <= coverageSize - indexOffset - 8) {
            "Invalid COV4 index count"
        }
        val entries = ArrayList<Cov4Reader.IndexEntry>(entryCount)
        snapshot.readSection(indexOffset.toLong() + 8, entryCount.toLong() * 24) { input ->
            repeat(entryCount) {
                val pathHash = java.lang.Long.reverseBytes(input.readLong())
                val pathOffset = input.readIntLe()
                val dataOffset = input.readIntLe()
                val dataSize = input.readIntLe()
                val crc32 = input.readIntLe()

                require(dataOffset >= 0 && dataSize >= 0 && dataOffset.toLong() + dataSize <= coverageSize) {
                    "Invalid COV4 file block bounds"
                }
                require(pathOffset >= 0 && pathOffset.toLong() + 4 <= dataSize) { "Invalid COV4 path offset" }
                // Read the file path from the FILE section
                val pathPosition = dataOffset.toLong() + pathOffset
                val pathLength = snapshot.read(pathPosition, 4).int
                require(pathLength >= 0 && pathLength.toLong() + pathOffset + 4 <= dataSize) { "Invalid COV4 path length" }
                val filePath = String(snapshot.read(pathPosition + 4, pathLength).array(), Charsets.UTF_8)

                entries.add(Cov4Reader.IndexEntry(pathHash, pathOffset, dataOffset, dataSize, crc32, filePath))
            }
        }

        log.info("COV4: opened ${sourceName}: ${tests.size} tests, ${entries.size} files")
        return Pair(tests, entries)
    }

    /**
     * Parses a FILE section's bytes (starting from FILE marker) into coverage data.
     */
    private fun parseFileSection(buf: DataInputStream): Map<Int, List<String>> {
        // FILE marker
        val marker = buf.readIntLe()
        if (marker != MARKER_FILE) throw CoverageApiException(
            "Expected FILE marker, got 0x${Integer.toHexString(marker).uppercase()}",
            kind = CoverageErrorKind.ARTIFACT_PARSE,
        )

        // Skip file path
        buf.readString()

        // Test sets
        val testSetsCount = buf.readIntLe()
        val testSets = ArrayList<List<String>>(testSetsCount)
        for (s in 0 until testSetsCount) {
            val setSize = buf.readIntLe()
            val names = ArrayList<String>(setSize)
            for (idx in 0 until setSize) {
                val testIdx = buf.readIntLe()
                names.add(if (testIdx in tests.indices) tests[testIdx] else "unknown-test-$testIdx")
            }
            testSets.add(names)
        }

        // Line mappings
        val lineCount = buf.readIntLe()
        val lineMap = LinkedHashMap<Int, List<String>>(lineCount)
        for (l in 0 until lineCount) {
            if (l % 256 == 0) CoverageCancellation.check()
            val lineNumber = buf.readIntLe()
            val setIndex = buf.readIntLe() // signed: -1 = uncovered

            if (setIndex < 0) {
                lineMap[lineNumber] = emptyList()
            } else if (setIndex < testSets.size) {
                lineMap[lineNumber] = testSets[setIndex]
            }
        }

        return lineMap
    }

    private fun DataInputStream.readIntLe(): Int = Integer.reverseBytes(readInt())

    private fun DataInputStream.readString(): String {
        val length = readIntLe()
        require(length >= 0 && length.toLong() <= coverageSize) { "Invalid COV4 string length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val MAGIC = 0x434F5633       // "COV3"
        private const val VERSION = 4
        private const val MARKER_FILE = 0x46494C45 // "FILE"
        private const val MARKER_INDEX = 0x494E4458 // "INDX"
    }
}
