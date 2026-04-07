package com.github.yakov255.perlinecoverageinfo

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class Cov4WriterReaderTest {

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
                assertEquals(listOf("test_c"), cov[4])
            }
        } finally {
            tempFile.delete()
        }
    }
}
