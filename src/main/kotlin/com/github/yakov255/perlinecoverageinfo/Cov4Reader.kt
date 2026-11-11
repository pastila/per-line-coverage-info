package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
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
 *
 * Robustness rules:
 *  - the index is read through one buffered channel cursor instead of one seek per field;
 *  - every length and counter is validated against the file size before it is allocated, so a
 *    damaged cache raises [CoverageApiException] instead of `NegativeArraySizeException`/OOM;
 *  - the per-section CRC32 from the index is verified when a section is read: a damaged section
 *    yields no coverage for that file and one log line, the rest of the file stays usable;
 *  - a cache file removed by another IDE (or by the daemon) degrades to an empty reader
 *    instead of throwing from [allFilePaths]/[getCoverage].
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

    /** The "file is gone" line is written once per reader, not once per editor refresh. */
    @Volatile
    private var goneWarned = false

    /** Paths whose section failed to parse or failed its CRC: warn once per path. */
    private val damagedWarned = ConcurrentHashMap.newKeySet<String>()

    data class IndexEntry(
        val pathHash: Long,
        val pathOffset: Int,
        val dataOffset: Int,
        val dataSize: Int,
        val crc32: Int,
        val filePath: String,
    )

    init {
        reloadIndexQuietly()
    }

    /** Re-reads the header, test table and index, treating a removed cache file as empty. */
    private fun reloadIndexQuietly() {
        try {
            reloadIndex()
        } catch (e: NoSuchFileException) {
            onFileGone(e)
        } catch (e: FileNotFoundException) {
            onFileGone(e)
        }
    }

    /** Another IDE deleted the cache file: serve no coverage and stay quiet from now on. */
    private fun onFileGone(cause: Exception) {
        synchronized(reloadLock) {
            tests = emptyList()
            entriesByPath = emptyMap()
            // Remember the missing stamp so the next access does not retry the same failing open.
            openedStamp = currentStamp()
            if (!goneWarned) {
                goneWarned = true
                log.info(
                    "COV4: ${file.name} disappeared (${cause.message ?: cause.javaClass.simpleName}); " +
                        "serving no coverage",
                )
            }
        }
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
            goneWarned = false
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
        reloadIndexQuietly()
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
        val parsed = CoveragePerfLog.counted("cov4.readFile") { readSectionOrNull(first) } ?: return null
        if (currentStamp() == stampBefore) return parsed

        // The file was replaced while reading: the offsets may belong to older bytes.
        ensureFreshIndex()
        val fresh = entriesByPath[key] ?: return null
        return CoveragePerfLog.counted("cov4.readFile") { readSectionOrNull(fresh) }
    }

    private data class FileStamp(val size: Long, val modified: FileTime, val key: Any?)

    /**
     * Reads one section; a damaged section (bad CRC, truncated bytes) yields an empty map so the
     * rest of the highlighting still works, a vanished file yields null.
     */
    private fun readSectionOrNull(entry: IndexEntry): Map<Int, List<String>>? = try {
        readSection(entry)
    } catch (e: CoverageApiException) {
        warnDamaged(entry, e.userMessage)
        emptyMap()
    } catch (e: java.io.EOFException) {
        warnDamaged(entry, "truncated section (${e.message})")
        emptyMap()
    } catch (e: NoSuchFileException) {
        onFileGone(e)
        null
    } catch (e: FileNotFoundException) {
        onFileGone(e)
        null
    }

    private fun warnDamaged(entry: IndexEntry, reason: String) {
        if (damagedWarned.add(entry.filePath)) {
            log.warn("COV4: ${file.name}: damaged section for '${entry.filePath}': $reason — showing no coverage for it")
        }
    }

    private fun readSection(entry: IndexEntry): Map<Int, List<String>> {
        val bytes = java.io.RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (entry.dataOffset < HEADER_SIZE || entry.dataSize <= 0 ||
                entry.dataOffset.toLong() + entry.dataSize > fileLength
            ) {
                throw CoverageApiException(
                    "COV4 index entry for '${entry.filePath}' points outside ${file.name} " +
                        "(offset=${entry.dataOffset}, size=${entry.dataSize}, file=$fileLength)",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
            val section = ByteArray(entry.dataSize)
            raf.seek(entry.dataOffset.toLong())
            raf.readFully(section)
            section
        }
        verifyCrc(entry, bytes)
        return try {
            parseFileSection(bytes)
        } catch (e: java.nio.BufferUnderflowException) {
            throw CoverageApiException(
                "Truncated COV4 FILE section for '${entry.filePath}' in ${file.name}",
                e,
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
    }

    /**
     * Verifies the CRC32 the writer stored for this section. A stored value of 0 means an older
     * writer left the placeholder in place; such caches are read without verification.
     */
    private fun verifyCrc(entry: IndexEntry, bytes: ByteArray) {
        if (entry.crc32 == 0) return
        val actual = CRC32().apply { update(bytes) }.value.toInt()
        if (actual != entry.crc32) {
            throw CoverageApiException(
                "COV4 CRC mismatch for '${entry.filePath}' in ${file.name}",
                details = mapOf(
                    "expected" to Integer.toHexString(entry.crc32),
                    "actual" to Integer.toHexString(actual),
                ),
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
    }

    /**
     * Reads header (28 bytes), global test list, and INDX section.
     *
     * The header, the test table and the index records are consumed through one buffered
     * channel cursor; only the per-entry path strings are read at their own offset. A large
     * cache is ~100 MB, so the file is never loaded whole just to read the index.
     */
    private fun readHeaderAndIndex(): Pair<List<String>, List<IndexEntry>> =
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val fileSize = channel.size()
            if (fileSize < HEADER_SIZE) {
                throw CoverageApiException(
                    "COV4 file ${file.name} is too short: $fileSize bytes",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
            val buf = ByteBuffer.wrap(readFully(channel, 0, HEADER_SIZE.toLong())).order(ByteOrder.LITTLE_ENDIAN)

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

            checkCount("test", testCount, fileSize)
            if (indexOffset < HEADER_SIZE || indexOffset.toLong() + INDEX_HEADER_SIZE > fileSize) {
                throw CoverageApiException(
                    "COV4 index offset $indexOffset is outside ${file.name} ($fileSize bytes)",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }

            // --- GLOBAL TEST LIST (immediately after the header) ---
            val cursor = ChannelCursor(channel, HEADER_SIZE.toLong(), fileSize)
            val tests = ArrayList<String>(minOf(testCount, MAX_INITIAL_CAPACITY))
            for (i in 0 until testCount) {
                tests.add(cursor.readString())
            }

            // --- INDX SECTION ---
            cursor.seek(indexOffset.toLong())
            val indexMarker = cursor.readInt()
            if (indexMarker != MARKER_INDEX) throw CoverageApiException(
                "Invalid INDX marker at offset $indexOffset: 0x${Integer.toHexString(indexMarker).uppercase()}",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )

            val entryCount = cursor.readInt()
            if (entryCount < 0 || indexOffset.toLong() + INDEX_HEADER_SIZE + entryCount.toLong() * INDEX_ENTRY_SIZE > fileSize) {
                throw CoverageApiException(
                    "COV4 index entry count $entryCount does not fit ${file.name} ($fileSize bytes)",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
            val entries = ArrayList<IndexEntry>(minOf(entryCount, MAX_INITIAL_CAPACITY))

            for (i in 0 until entryCount) {
                val pathHash = cursor.readLong()
                val pathOffset = cursor.readInt()
                val dataOffset = cursor.readInt()
                val dataSize = cursor.readInt()
                val crc32 = cursor.readInt()

                if (dataOffset < HEADER_SIZE || dataSize <= 0 ||
                    dataOffset.toLong() + dataSize > fileSize
                ) {
                    throw CoverageApiException(
                        "COV4 index entry $i points outside ${file.name} " +
                            "(offset=$dataOffset, size=$dataSize, file=$fileSize)",
                        kind = CoverageErrorKind.ARTIFACT_PARSE,
                    )
                }
                if (pathOffset < 4 || pathOffset >= dataSize) {
                    throw CoverageApiException(
                        "COV4 index entry $i has an invalid path offset $pathOffset",
                        kind = CoverageErrorKind.ARTIFACT_PARSE,
                    )
                }

                // The FILE section at dataOffset starts with the marker; its path follows.
                val filePath = readStringAt(channel, dataOffset.toLong() + pathOffset, fileSize)
                entries.add(IndexEntry(pathHash, pathOffset, dataOffset, dataSize, crc32, filePath))
            }

            log.info("COV4: opened ${file.name}: ${tests.size} tests, ${entries.size} files")
            return Pair(tests, entries)
        }

    /** Reads [length] bytes at [position] without moving the channel position. */
    private fun readFully(channel: FileChannel, position: Long, length: Long): ByteArray {
        if (length < 0 || length > MAX_SECTION_BYTES) {
            throw CoverageApiException(
                "COV4: refusing to read $length bytes at $position from ${file.name}",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
        val bytes = ByteArray(length.toInt())
        val buffer = ByteBuffer.wrap(bytes)
        var offset = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, offset)
            if (read < 0) throw CoverageApiException(
                "Unexpected end of ${file.name} at offset $offset",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
            offset += read
        }
        return bytes
    }

    /**
     * Reads one length-prefixed string at [offset]. The first read grabs a small window, which
     * covers every real path; a longer string is completed with a second positional read.
     */
    private fun readStringAt(channel: FileChannel, offset: Long, fileSize: Long): String {
        if (offset < HEADER_SIZE || offset + 4 > fileSize) {
            throw CoverageApiException(
                "COV4 string offset $offset is outside ${file.name} ($fileSize bytes)",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
        val windowSize = minOf(PATH_WINDOW_BYTES.toLong(), fileSize - offset).toInt()
        val window = readFully(channel, offset, windowSize.toLong())
        val buf = ByteBuffer.wrap(window).order(ByteOrder.LITTLE_ENDIAN)
        val length = buf.int
        if (length < 0) {
            throw CoverageApiException(
                "COV4: negative string length $length at offset $offset in ${file.name}",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
        if (length > MAX_STRING_BYTES) {
            throw CoverageApiException(
                "COV4: string length $length at offset $offset in ${file.name} exceeds the limit",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
        if (length.toLong() + 4 > fileSize - offset) {
            throw CoverageApiException(
                "COV4: string length $length at offset $offset does not fit ${file.name}",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
        val bytes = if (length + 4 <= window.size) {
            ByteArray(length).also { buf.get(it) }
        } else {
            readFully(channel, offset + 4, length.toLong())
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun checkCount(what: String, count: Int, fileSize: Long) {
        if (count < 0 || count.toLong() * 4 > fileSize) {
            throw CoverageApiException(
                "COV4: implausible $what count $count in ${file.name} ($fileSize bytes)",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
    }

    /**
     * Buffered sequential reader over a channel. Keeps a [BUFFER_SIZE] window and refills it
     * only when a field crosses the window edge, so reading a 21k-entry index costs a few
     * reads instead of one seek per field.
     */
    private class ChannelCursor(
        private val channel: FileChannel,
        start: Long,
        private val fileSize: Long,
    ) {
        private val buffer = ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        init {
            buffer.limit(0)
            seek(start)
        }

        fun seek(position: Long) {
            channel.position(position)
            buffer.clear()
            buffer.limit(0)
        }

        fun readInt(): Int {
            require(4)
            return buffer.int
        }

        fun readLong(): Long {
            require(8)
            return buffer.long
        }

        fun readString(): String {
            val length = readInt()
            if (length < 0) {
                throw CoverageApiException(
                    "COV4: negative string length $length",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
            if (length > MAX_STRING_BYTES) {
                throw CoverageApiException(
                    "COV4: string length $length exceeds the limit",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
            if (length.toLong() > fileSize) {
                throw CoverageApiException(
                    "COV4: string length $length does not fit a $fileSize byte file",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
            val bytes = ByteArray(length)
            var filled = 0
            while (filled < length) {
                if (!buffer.hasRemaining()) fill()
                val chunk = minOf(buffer.remaining(), length - filled)
                buffer.get(bytes, filled, chunk)
                filled += chunk
            }
            return String(bytes, Charsets.UTF_8)
        }

        private fun require(bytes: Int) {
            while (buffer.remaining() < bytes) fill()
        }

        private fun fill() {
            if (buffer.remaining() > 0) buffer.compact() else buffer.clear()
            val read = channel.read(buffer)
            if (read < 0) {
                throw CoverageApiException(
                    "Unexpected end of COV4 file",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
            buffer.flip()
        }
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
        if (testSetsCount < 0 || testSetsCount.toLong() * 4 > buf.remaining()) {
            throw CoverageApiException(
                "COV4: implausible test set count $testSetsCount in a FILE section",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
        val testSets = ArrayList<List<Int>>(testSetsCount)
        for (s in 0 until testSetsCount) {
            val setSize = buf.int
            if (setSize < 0 || setSize.toLong() * 4 > buf.remaining()) {
                throw CoverageApiException(
                    "COV4: implausible test set size $setSize in a FILE section",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }
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
        if (lineCount < 0 || lineCount.toLong() * 8 > buf.remaining()) {
            throw CoverageApiException(
                "COV4: implausible line count $lineCount in a FILE section",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
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
        if (length < 0 || length > MAX_STRING_BYTES || length > buf.remaining()) {
            throw CoverageApiException(
                "COV4: invalid string length $length in a FILE section",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
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

        /** `INDX` marker + entry count. */
        private const val INDEX_HEADER_SIZE = 8

        /** path hash (8) + path offset (4) + data offset (4) + data size (4) + CRC32 (4). */
        private const val INDEX_ENTRY_SIZE = 24

        private const val BUFFER_SIZE = 64 * 1024
        private const val PATH_WINDOW_BYTES = 256

        /** A cache string longer than this cannot be a real path or test name. */
        private const val MAX_STRING_BYTES = 1 shl 20

        /** Never preallocate an ArrayList for more than this many records. */
        private const val MAX_INITIAL_CAPACITY = 4096

        /** Sanity ceiling for one buffered read; a section never exceeds the file size anyway. */
        private const val MAX_SECTION_BYTES = Int.MAX_VALUE.toLong()
    }
}
