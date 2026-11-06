package com.github.yakov255.perlinecoverageinfo

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Parses the COVT binary coverage format and extracts `.covt` files from ZIP archives.
 *
 * The COVT format is a compact binary representation of per-line coverage data
 * that maps file paths → line numbers → covering test names.
 */
object BinaryCoverageParser {

    private val log = CoverageLog.get(BinaryCoverageParser::class.java)

    private const val MAGIC = 0x434F5654u       // "COVT"
    private const val VERSION = 1u
    private const val MARKER_FILE = 0x46494C45u // "FILE"
    private const val MARKER_END = 0x454E4446u  // "ENDF"

    /**
     * Parses raw `.covt` file bytes into per-line coverage data.
     *
     * @return outer map: file path → inner map: 1-based line number → list of test names.
     *         Lines with set index -1 (uncovered but coverable) map to an empty list.
     */
    fun parseCovtBytes(data: ByteArray): Map<String, Map<Int, List<String>>> = parseCovt(data).coverage

    /**
     * Same as [parseCovtBytes], but also returns the full test list from the header —
     * every test that was executed in the run, including ones that hit no included file.
     */
    fun parseCovt(data: ByteArray): CovtData = parseCovt(data.inputStream())

    private fun parseCovt(input: InputStream): CovtData {
        val buf = DataInputStream(BufferedInputStream(input))
        try {
            // --- HEADER (12 bytes) ---
            val magic = buf.int.toUInt()
            if (magic != MAGIC) {
                throw CoverageApiException(
                    "Invalid COVT magic: 0x${magic.toString(16).uppercase()}, expected 0x${MAGIC.toString(16).uppercase()}",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }

            val version = buf.int.toUInt()
            if (version != VERSION) {
                throw CoverageApiException(
                    "Unsupported COVT version: $version, expected $VERSION",
                    kind = CoverageErrorKind.ARTIFACT_PARSE,
                )
            }

            @Suppress("UNUSED_VARIABLE")
            val flags = buf.int.toUInt() // reserved

            // --- TEST SECTION ---
            val testCount = buf.int.toUInt().toInt()
            val tests = ArrayList<String>(testCount)
            for (i in 0 until testCount) {
                tests.add(readString(buf, "test[$i]"))
            }
            log.info("COVT: read $testCount test(s)")

            // --- FILE BLOCKS ---
            val result = LinkedHashMap<String, Map<Int, List<String>>>()

            while (buf.hasRemaining()) {
                val marker = buf.int.toUInt()

                when (marker) {
                    MARKER_END -> {
                        log.info("COVT: end marker reached, parsed ${result.size} file(s)")
                        return CovtData(tests, result)
                    }
                    MARKER_FILE -> {
                        val filePath = readString(buf, "file path")

                        // Test sets for this file
                        val testSetsCount = buf.int.toUInt().toInt()
                        val testSets = ArrayList<List<Int>>(testSetsCount)
                        for (s in 0 until testSetsCount) {
                            val setSize = buf.int.toUInt().toInt()
                            val indices = ArrayList<Int>(setSize)
                            for (idx in 0 until setSize) {
                                indices.add(buf.int.toUInt().toInt())
                            }
                            testSets.add(indices)
                        }

                        // Line mappings
                        // A set is usually referenced by many lines. Decode its names once
                        // rather than expanding the same list for every line in the file.
                        val resolvedTestSets = arrayOfNulls<List<String>>(testSetsCount)
                        val lineCount = buf.int.toUInt().toInt()
                        val lineMap = LinkedHashMap<Int, List<String>>(lineCount)
                        for (l in 0 until lineCount) {
                            val lineNumber = buf.int.toUInt().toInt()
                            val setIndex = buf.int // signed: -1 means uncovered

                            if (setIndex < 0) {
                                lineMap[lineNumber] = emptyList()
                            } else {
                                if (setIndex >= testSets.size) {
                                    throw CoverageApiException(
                                        "Invalid test set index $setIndex for line $lineNumber in $filePath (only ${testSets.size} sets)",
                                        kind = CoverageErrorKind.ARTIFACT_PARSE,
                                    )
                                }
                                val testNames = resolvedTestSets[setIndex] ?: testSets[setIndex].map { testIdx ->
                                    if (testIdx !in tests.indices) {
                                        throw CoverageApiException(
                                            "Invalid test index $testIdx in set $setIndex for line $lineNumber in $filePath (only ${tests.size} tests)",
                                            kind = CoverageErrorKind.ARTIFACT_PARSE,
                                        )
                                    }
                                    tests[testIdx]
                                }.also { resolvedTestSets[setIndex] = it }
                                lineMap[lineNumber] = testNames
                            }
                        }

                        result[filePath] = lineMap
                    }
                    else -> {
                        throw CoverageApiException(
                            "Unknown COVT marker: 0x${marker.toString(16).uppercase()}",
                            kind = CoverageErrorKind.ARTIFACT_PARSE,
                        )
                    }
                }
            }

            // Reached end of buffer without ENDF marker
            throw CoverageApiException(
                "COVT data ended without ENDF marker",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        } catch (e: CoverageApiException) {
            throw e
        } catch (e: EOFException) {
            throw CoverageApiException("Unexpected end of COVT data", e, kind = CoverageErrorKind.ARTIFACT_PARSE)
        } catch (e: Exception) {
            throw CoverageApiException(
                "Failed to parse COVT binary data",
                cause = e,
                kind = CoverageErrorKind.ARTIFACT_PARSE,
                details = mapOf("error" to e.message),
            )
        }
    }

    /**
     * Parses raw bytes that may be gzipped (`.covt.gz`) or plain (`.covt`).
     * Auto-detects GZIP via the magic bytes `0x1F 0x8B`.
     */
    fun parsePossiblyGzippedCovtBytes(bytes: ByteArray): Map<String, Map<Int, List<String>>> =
        parsePossiblyGzippedCovt(bytes).coverage

    /** [parseCovt] for input that may be gzipped; decompression uses a fixed-size buffer. */
    fun parsePossiblyGzippedCovt(bytes: ByteArray): CovtData {
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            log.info("COVT: input is gzipped, decompressing")
            return GZIPInputStream(bytes.inputStream()).use { input ->
                parseCovt(input).also {
                    // Reach the gzip trailer even when ENDF was in an earlier read buffer.
                    // This preserves checksum/truncation validation from full decompression.
                    input.copyTo(OutputStream.nullOutputStream())
                }
            }
        }
        return parseCovt(bytes)
    }

    /** Parsed `.covt`: all executed tests plus file → line → covering tests. */
    data class CovtData(
        val tests: List<String>,
        val coverage: Map<String, Map<Int, List<String>>>,
    )

    private val DataInputStream.int: Int get() = Integer.reverseBytes(readInt())

    private fun DataInputStream.hasRemaining(): Boolean {
        mark(1)
        val next = read()
        reset()
        return next != -1
    }

    private fun readString(buf: DataInputStream, context: String): String {
        val length = buf.int
        require(length >= 0) { "Invalid COVT string length for $context" }
        val bytes = ByteArray(length)
        buf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
