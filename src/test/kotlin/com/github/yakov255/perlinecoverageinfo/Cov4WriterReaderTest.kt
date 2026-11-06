package com.github.yakov255.perlinecoverageinfo

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files

class Cov4WriterReaderTest {

    @Test
    fun testConcurrentLookupsKeepIndependentPositions() {
        val coverage = (1..10).associate { file ->
            "src/file$file.php" to (1..100).associateWith { listOf("test_$file") }
        }
        val tempFile = Files.createTempFile("concurrent-coverage", ".cov4").toFile()
        val executor = Executors.newFixedThreadPool(4)
        try {
            Cov4Writer.write(coverage, tempFile)
            Cov4Reader(tempFile).use { reader ->
                val futures = (1..100).map { lookup ->
                    executor.submit {
                        val path = "src/file${lookup % 10 + 1}.php"
                        assertEquals(coverage[path], reader.getCoverage(path))
                    }
                }
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            }
        } finally {
            executor.shutdownNow()
            tempFile.delete()
        }
    }

    @Test
    fun testWritingCoverageLargerThanHeap() {
        val sharedLines = (1..10_000).associateWith { listOf("shared_test") }
        val coverage = (1..1000).associate { "src/file$it.php" to sharedLines }
        val tempFile = Files.createTempFile("large-streamed-coverage", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)
            assertTrue(tempFile.length() > 64L * 1024 * 1024)
            Cov4Reader(tempFile).use { reader ->
                assertEquals(1000, reader.allFilePaths.size)
                assertEquals(sharedLines, reader.getCoverage("src/file1000.php"))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testLargeSnapshotDoesNotNeedFileSizedHeap() {
        val coverage = mapOf("src/main.php" to mapOf(1 to listOf("test")))
        val tempFile = Files.createTempFile("large-coverage", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)
            // Sparse trailing bytes do not change indexed coverage. Run this test with -Xmx64m:
            // reading the entire file into a byte array would exhaust the heap.
            RandomAccessFile(tempFile, "rw").use { it.setLength(128L * 1024 * 1024) }
            Cov4Reader(tempFile).use { reader ->
                assertEquals(coverage["src/main.php"], reader.getCoverage("src/main.php"))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testStreamedOutputMatchesFormatAndChecksums() {
        val coverage = (1..40).associate { file ->
            "dir/file$file.php" to (1..100).associateWith { line ->
                if (line % 3 == 0) emptyList() else listOf("test_$file", "shared_test")
            }
        }
        val tempFile = Files.createTempFile("streamed-coverage", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)
            val bytes = tempFile.readBytes()
            assertArrayEquals(Cov4Writer.toBytes(coverage), bytes)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val indexOffset = buffer.getInt(20)
            val count = buffer.getInt(indexOffset + 4)
            assertEquals(coverage.size, count)
            repeat(count) { index ->
                val entry = indexOffset + 8 + index * 24
                val offset = buffer.getInt(entry + 12)
                val length = buffer.getInt(entry + 16)
                val expected = CRC32().apply { update(bytes, offset, length) }.value.toInt()
                assertEquals(expected, buffer.getInt(entry + 20))
            }
            val expected = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()
            assertEquals(expected, buffer.getInt(bytes.size - 4))
            Cov4Reader(tempFile).use { reader ->
                coverage.forEach { (path, lines) ->
                    assertEquals(lines.mapValues { it.value.toSet() }, reader.getCoverage(path)!!.mapValues { it.value.toSet() })
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testOpenReaderKeepsSnapshotWhenCacheIsRewritten() {
        val original = mapOf("src/main.php" to mapOf(1 to listOf("original_test")))
        val replacement = mapOf(
            "src/main.php" to mapOf(42 to listOf("replacement_test_with_a_different_length")),
            "src/extra.php" to mapOf(2 to listOf("extra_test")),
        )
        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(original, tempFile)
            Cov4Reader(tempFile).use { reader ->
                // Change both the global test table and FILE offsets before the first lookup.
                // In-place overwrites must not affect the already opened reader either.
                tempFile.writeBytes(Cov4Writer.toBytes(replacement))
                assertEquals(original.keys, reader.allFilePaths)
                assertEquals(original["src/main.php"], reader.getCoverage("src/main.php"))
                assertNull(reader.getCoverage("src/extra.php"))

                Cov4Reader(tempFile).use { freshReader ->
                    assertEquals(replacement.keys, freshReader.allFilePaths)
                    for ((path, lines) in replacement) {
                        assertEquals(lines, freshReader.getCoverage(path))
                    }
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testOpenReaderSurvivesCacheReplacementAndDeletion() {
        val original = mapOf("src/main.php" to mapOf(1 to listOf("original_test")))
        val replacement = mapOf("src/main.php" to mapOf(10 to listOf("new_test")))
        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(original, tempFile)
            Cov4Reader(tempFile).use { reader ->
                Cov4Writer.write(replacement, tempFile)
                assertEquals(original["src/main.php"], reader.getCoverage("src/main.php"))
                Cov4Reader(tempFile).use { freshReader ->
                    assertEquals(replacement["src/main.php"], freshReader.getCoverage("src/main.php"))
                }

                assertTrue(tempFile.delete())
                assertEquals(original["src/main.php"], reader.getCoverage("src/main.php"))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testRoundTrip() {
        val coverage = mapOf(
            "src/main.php" to mapOf(
                1 to listOf("test_add", "test_sub"),
                2 to listOf("test_add"),
                3 to emptyList(), // uncovered
            ),
            "src/utils.php" to mapOf(
                10 to listOf("test_helper"),
                11 to emptyList(),
            ),
        )

        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)
            assertTrue("COV4 file should be non-empty", tempFile.length() > 28)

            val reader = Cov4Reader(tempFile)
            reader.use {
                assertEquals(setOf("src/main.php", "src/utils.php"), it.allFilePaths)
                assertTrue(it.hasData())

                // Check first file
                val mainCov = it.getCoverage("src/main.php")
                assertNotNull(mainCov)
                assertEquals(3, mainCov!!.size)
                assertEquals(listOf("test_add", "test_sub"), mainCov[1])
                assertEquals(listOf("test_add"), mainCov[2])
                assertEquals(emptyList<String>(), mainCov[3])

                // Check second file
                val utilsCov = it.getCoverage("src/utils.php")
                assertNotNull(utilsCov)
                assertEquals(2, utilsCov!!.size)
                assertEquals(listOf("test_helper"), utilsCov[10])
                assertEquals(emptyList<String>(), utilsCov[11])

                // Non-existent file
                assertNull(it.getCoverage("nonexistent.php"))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testSingleFileLookup() {
        // Create coverage with many files
        val coverage = (1..50).associate { i ->
            "dir${i % 5}/file$i.php" to mapOf(
                1 to listOf("test_$i"),
                2 to emptyList(),
            )
        }

        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)

            val reader = Cov4Reader(tempFile)
            reader.use {
                assertEquals(50, it.allFilePaths.size)

                // Verify specific file
                val cov = it.getCoverage("dir3/file23.php")
                assertNotNull(cov)
                assertEquals(listOf("test_23"), cov!![1])
                assertEquals(emptyList<String>(), cov[2])
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testFnv1aHash() {
        // Verify against known value from the Go implementation's sample file
        // The sample 1001_calc.cov3 has path "calc/src/BasicCalculator.php"
        // with hash bytes: 4a 95 56 af 6a 41 85 41 (little-endian uint64)
        // = 0x4185416AAF56954A
        val hash = Cov4Writer.fnv1a64("calc/src/BasicCalculator.php")
        assertEquals(
            "FNV-1a hash should match Go implementation",
            java.lang.Long.parseUnsignedLong("4185416AAF56954A", 16),
            hash
        )
    }

    @Test
    fun testReadSampleCov3File() {
        val sampleFile = File("php-sample-code/calc/1001_calc.cov3")
        if (!sampleFile.exists()) {
            println("Skipping testReadSampleCov3File: sample file not found")
            return
        }

        val reader = Cov4Reader(sampleFile)
        reader.use {
            assertTrue(it.hasData())
            assertEquals(1, it.allFilePaths.size)
            assertTrue(it.allFilePaths.contains("calc/src/BasicCalculator.php"))

            val cov = it.getCoverage("calc/src/BasicCalculator.php")
            assertNotNull(cov)
            assertEquals("Expected 6 lines with coverage", 6, cov!!.size)

            // Verify uncovered line
            assertTrue(cov.containsKey(20))
            assertEquals(emptyList<String>(), cov[20])

            // Verify covered line has tests
            assertTrue(cov.containsKey(22))
            assertTrue(cov[22]!!.isNotEmpty())
        }
    }

    @Test
    fun testWriteReadEmptyCoverage() {
        val coverage = emptyMap<String, Map<Int, List<String>>>()
        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)
            val reader = Cov4Reader(tempFile)
            reader.use {
                assertFalse(it.hasData())
                assertTrue(it.allFilePaths.isEmpty())
                assertNull(it.getCoverage("anything"))
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testTestSetDeduplication() {
        // Multiple lines sharing the same test set should produce fewer sets
        val sharedTests = listOf("test_a", "test_b")
        val coverage = mapOf(
            "file.php" to mapOf(
                1 to sharedTests,
                2 to sharedTests,
                3 to sharedTests,
                4 to listOf("test_c"),
            ),
        )

        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)
            val reader = Cov4Reader(tempFile)
            reader.use {
                val cov = it.getCoverage("file.php")!!
                assertEquals(4, cov.size)
                assertEquals(sharedTests, cov[1])
                assertEquals(sharedTests, cov[2])
                assertEquals(sharedTests, cov[3])
                assertSame("Lines using one test set must share its decoded list", cov[1], cov[2])
                assertSame(cov[1], cov[3])
                assertEquals(listOf("test_c"), cov[4])
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testReadsOldRaketaFormatWithNormalizedPaths() {
        val coverage = mapOf(
            "app/raketa/web/app/raketa/api/agency/src/Foo.php" to mapOf(
                1 to listOf("app/raketa/E2E/Agency/feature.feature:10"),
            ),
            "app/raketa/web/cli/scripts/Bar.php" to mapOf(
                2 to listOf("app/raketa/web/app/raketa/raketa/BoundedContext/Client/Features/x.feature:5"),
            ),
        )
        val suites = setOf("E2E", "FeatureTest", "Stubs", "Support")

        val tempFile = Files.createTempFile("test", ".cov4").toFile()
        try {
            Cov4Writer.write(coverage, tempFile)
            val reader = Cov4Reader(
                tempFile,
                pathNormalizer = CoveragePathNormalizer::normalizeRaketaFilePath,
                testNormalizer = { CoveragePathNormalizer.normalizeRaketaBehatName(it, suites) },
            )
            reader.use {
                assertEquals(
                    setOf("app/raketa/api/agency/src/Foo.php", "cli/scripts/Bar.php"),
                    it.allFilePaths,
                )
                assertEquals(
                    listOf("app/raketa/features/E2E/Agency/feature.feature:10"),
                    it.getCoverage("app/raketa/api/agency/src/Foo.php")!![1],
                )
                assertEquals(
                    listOf("app/raketa/raketa/BoundedContext/Client/Features/x.feature:5"),
                    it.getCoverage("cli/scripts/Bar.php")!![2],
                )
                // The old key also resolves because lookup paths are normalized.
                assertEquals(
                    listOf("app/raketa/features/E2E/Agency/feature.feature:10"),
                    it.getCoverage("app/raketa/web/app/raketa/api/agency/src/Foo.php")!![1],
                )
            }
        } finally {
            tempFile.delete()
        }
    }
}
