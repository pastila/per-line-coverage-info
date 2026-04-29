package com.github.yakov255.perlinecoverageinfo

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    fun testExtractCovtFromZipWithNoCovt() {
        val zipBytes = createZip("data.txt", "hello".toByteArray())
        val result = BinaryCoverageParser.extractCovtFromZip(zipBytes)
        assertNull("Should return null when ZIP has no .covt file", result)
    }

    @Test
    fun testExtractCovtFromZipWithCovtFile() {
        val covtContent = File("php-sample-code/calc/coverage.covt").readBytes()
        val zipBytes = createZip("coverage.covt", covtContent)

        val extracted = BinaryCoverageParser.extractCovtFromZip(zipBytes)
        assertNotNull("Should extract .covt file from ZIP", extracted)
        assertArrayEquals(covtContent, extracted)
    }

    @Test
    fun testParseZipArtifactReturnsNullWhenNoCovtInZip() {
        val zipBytes = createZip("readme.txt", "no coverage here".toByteArray())
        val result = BinaryCoverageParser.parseZipArtifact(zipBytes)
        assertNull("Should return null when ZIP has no .covt file", result)
    }

    @Test
    fun testExtractCovtGzFromZip() {
        val covtContent = File("php-sample-code/calc/coverage.covt").readBytes()

        // Gzip the .covt content
        val gzipBaos = ByteArrayOutputStream()
        GZIPOutputStream(gzipBaos).use { it.write(covtContent) }
        val gzippedBytes = gzipBaos.toByteArray()

        // Create ZIP with .covt.gz entry
        val zipBytes = createZip("coverage.covt.gz", gzippedBytes)

        val extracted = BinaryCoverageParser.extractCovtFromZip(zipBytes)
        assertNotNull("Should extract and decompress .covt.gz file from ZIP", extracted)
        assertArrayEquals("Decompressed content should match original .covt", covtContent, extracted)
    }

    @Test
    fun testParseZipArtifactWithGzippedCovt() {
        val covtContent = File("php-sample-code/calc/coverage.covt").readBytes()

        val gzipBaos = ByteArrayOutputStream()
        GZIPOutputStream(gzipBaos).use { it.write(covtContent) }
        val gzippedBytes = gzipBaos.toByteArray()

        val zipBytes = createZip("coverage.covt.gz", gzippedBytes)

        val result = BinaryCoverageParser.parseZipArtifact(zipBytes)
        assertNotNull("Should parse gzipped .covt from ZIP", result)
        assertEquals("Expected exactly 1 file entry", 1, result!!.size)
        assertTrue(result.containsKey("calc/src/BasicCalculator.php"))
    }

    private fun createZip(entryName: String, content: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content)
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
