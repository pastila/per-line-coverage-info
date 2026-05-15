package com.github.yakov255.perlinecoverageinfo

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

class BinaryCoverageParserTest {

    @Test
    fun testParseSampleCovtFile() {
        val data = File("php-sample-code/calc/coverage.covt").readBytes()
        assertTrue("COVT file should not be empty", data.isNotEmpty())

        val result = BinaryCoverageParser.parseCovtBytes(data)

        assertEquals("Expected exactly 1 file entry", 1, result.size)
        assertTrue(result.containsKey("calc/src/BasicCalculator.php"))

        val lines = result["calc/src/BasicCalculator.php"]!!
        assertTrue("File should have at least one line of coverage data", lines.isNotEmpty())

        var uncovered = 0
        var covered = 0
        for ((lineNum, testNames) in lines) {
            if (testNames.isEmpty()) {
                uncovered++
            } else {
                covered++
                for (name in testNames) {
                    assertTrue(
                        "Test name should reference calculator.feature: $name",
                        name.startsWith("calc/features/calculator.feature:"),
                    )
                }
            }
        }
        assertTrue("File should have at least one uncovered line", uncovered > 0)
        assertTrue("File should have at least one covered line", covered > 0)
    }

    @Test
    fun testParseCovtEmptyData() {
        try {
            BinaryCoverageParser.parseCovtBytes(ByteArray(0))
            fail("Expected CoverageApiException for empty data")
        } catch (e: CoverageApiException) {
            assertEquals(CoverageErrorKind.ARTIFACT_PARSE, e.kind)
        }
    }

    @Test
    fun testParseCovtInvalidMagic() {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xDEADBEEF.toInt()) // wrong magic
        buf.putInt(1)                   // version
        buf.putInt(0)                   // flags

        try {
            BinaryCoverageParser.parseCovtBytes(buf.array())
            fail("Expected CoverageApiException for invalid magic")
        } catch (e: CoverageApiException) {
            assertEquals(CoverageErrorKind.ARTIFACT_PARSE, e.kind)
            assertTrue(e.message!!.contains("Invalid COVT magic"))
        }
    }

    @Test
    fun testParsePossiblyGzippedCovtBytesWithRawInput() {
        val covtBytes = File("php-sample-code/calc/coverage.covt").readBytes()
        val result = BinaryCoverageParser.parsePossiblyGzippedCovtBytes(covtBytes)

        assertEquals("Expected exactly 1 file entry", 1, result.size)
        assertTrue(result.containsKey("calc/src/BasicCalculator.php"))
        val lines = result["calc/src/BasicCalculator.php"]!!
        assertTrue("File should have at least one line of coverage data", lines.isNotEmpty())

        var uncovered = false
        var covered = false
        for ((_, testNames) in lines) {
            if (testNames.isEmpty()) uncovered = true else covered = true
        }
        assertTrue("Should have at least one uncovered line", uncovered)
        assertTrue("Should have at least one covered line", covered)
    }

    @Test
    fun testParsePossiblyGzippedCovtBytesWithGzipInput() {
        val covtBytes = File("php-sample-code/calc/coverage.covt").readBytes()
        val gzipBaos = ByteArrayOutputStream()
        GZIPOutputStream(gzipBaos).use { it.write(covtBytes) }
        val gzippedBytes = gzipBaos.toByteArray()

        val result = BinaryCoverageParser.parsePossiblyGzippedCovtBytes(gzippedBytes)

        assertEquals("Expected exactly 1 file entry", 1, result.size)
        assertTrue(result.containsKey("calc/src/BasicCalculator.php"))
    }

    @Test
    fun testParsePossiblyGzippedCovtBytesWithEmptyInput() {
        try {
            BinaryCoverageParser.parsePossiblyGzippedCovtBytes(ByteArray(0))
            fail("Expected CoverageApiException for empty data")
        } catch (e: CoverageApiException) {
            assertEquals(CoverageErrorKind.ARTIFACT_PARSE, e.kind)
        }
    }
}
