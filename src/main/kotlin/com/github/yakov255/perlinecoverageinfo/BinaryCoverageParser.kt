package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.diagnostic.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream

/**
 * Parses the COVT binary coverage format and extracts `.covt` files from ZIP archives.
 *
 * The COVT format is a compact binary representation of per-line coverage data
 * that maps file paths → line numbers → covering test names.
 */
object BinaryCoverageParser {

    private val log = Logger.getInstance(BinaryCoverageParser::class.java)

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
    fun parseCovtBytes(data: ByteArray): Map<String, Map<Int, List<String>>> {
        if (data.isEmpty()) {
            throw CoverageApiException(
                "COVT data is empty",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        try {
            // --- HEADER (12 bytes) ---
            ensureRemaining(buf, 12, "header")
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
            ensureRemaining(buf, 4, "test count")
            val testCount = buf.int.toUInt().toInt()
            val tests = ArrayList<String>(testCount)
            for (i in 0 until testCount) {
                tests.add(readString(buf, "test[$i]"))
            }
            log.info("COVT: read $testCount test(s)")

            // --- FILE BLOCKS ---
            val result = LinkedHashMap<String, Map<Int, List<String>>>()

            while (buf.hasRemaining()) {
                ensureRemaining(buf, 4, "file/end marker")
                val marker = buf.int.toUInt()

                when (marker) {
                    MARKER_END -> {
                        log.info("COVT: end marker reached, parsed ${result.size} file(s)")
                        return result
                    }
                    MARKER_FILE -> {
                        val filePath = readString(buf, "file path")

                        // Test sets for this file
                        ensureRemaining(buf, 4, "test sets count for $filePath")
                        val testSetsCount = buf.int.toUInt().toInt()
                        val testSets = ArrayList<List<Int>>(testSetsCount)
                        for (s in 0 until testSetsCount) {
                            ensureRemaining(buf, 4, "test set[$s] size for $filePath")
                            val setSize = buf.int.toUInt().toInt()
                            ensureRemaining(buf, setSize * 4, "test set[$s] indices for $filePath")
                            val indices = ArrayList<Int>(setSize)
                            for (idx in 0 until setSize) {
                                indices.add(buf.int.toUInt().toInt())
                            }
                            testSets.add(indices)
                        }

                        // Line mappings
                        ensureRemaining(buf, 4, "line count for $filePath")
                        val lineCount = buf.int.toUInt().toInt()
                        val lineMap = LinkedHashMap<Int, List<String>>(lineCount)
                        for (l in 0 until lineCount) {
                            ensureRemaining(buf, 8, "line[$l] for $filePath")
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
                                val testNames = testSets[setIndex].map { testIdx ->
                                    if (testIdx >= tests.size) {
                                        throw CoverageApiException(
                                            "Invalid test index $testIdx in set $setIndex for line $lineNumber in $filePath (only ${tests.size} tests)",
                                            kind = CoverageErrorKind.ARTIFACT_PARSE,
                                        )
                                    }
                                    tests[testIdx]
                                }
                                lineMap[lineNumber] = testNames
                            }
                        }

                        result[filePath] = lineMap
                    }
                    else -> {
                        throw CoverageApiException(
                            "Unknown COVT marker: 0x${marker.toString(16).uppercase()} at position ${buf.position() - 4}",
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
     * Extracts the first `.covt` file from a ZIP archive.
     *
     * @return the raw bytes of the `.covt` entry, or `null` if none found.
     */
    fun extractCovtFromZip(zipBytes: ByteArray): ByteArray? {
        try {
            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".covt")) {
                        log.info("COVT: found ${entry.name} in ZIP archive")
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to read ZIP archive for .covt extraction", e)
            return null
        }
        return null
    }

    /**
     * Convenience method: extracts a `.covt` file from a ZIP archive and parses it.
     *
     * @throws CoverageApiException with kind [CoverageErrorKind.ARTIFACT_PARSE] if
     *         extraction fails or parsing fails.
     */
    fun parseZipArtifact(zipBytes: ByteArray): Map<String, Map<Int, List<String>>> {
        val covtBytes = extractCovtFromZip(zipBytes)
            ?: throw CoverageApiException(
                "No .covt file found in ZIP artifact",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        try {
            return parseCovtBytes(covtBytes)
        } catch (e: CoverageApiException) {
            throw e
        } catch (e: Exception) {
            throw CoverageApiException(
                "Failed to parse .covt file from ZIP artifact",
                cause = e,
                kind = CoverageErrorKind.ARTIFACT_PARSE,
                details = mapOf("error" to e.message),
            )
        }
    }

    private fun readString(buf: ByteBuffer, context: String): String {
        ensureRemaining(buf, 4, "string length for $context")
        val length = buf.int.toUInt().toInt()
        ensureRemaining(buf, length, "string data for $context")
        val bytes = ByteArray(length)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun ensureRemaining(buf: ByteBuffer, needed: Int, context: String) {
        if (buf.remaining() < needed) {
            throw CoverageApiException(
                "Unexpected end of COVT data: need $needed bytes for $context, but only ${buf.remaining()} remaining",
                kind = CoverageErrorKind.ARTIFACT_PARSE,
            )
        }
    }
}
