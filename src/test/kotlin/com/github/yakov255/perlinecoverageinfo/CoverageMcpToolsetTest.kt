package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CoverageMcpToolsetTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- CoveredFileLine ---

    @Test
    fun coveredFileLineSerialization() {
        val line = CoveredFileLine(lineNumber = 1, content = "<?php", testCount = 3, isCovered = true)
        val serialized = json.encodeToString(CoveredFileLine.serializer(), line)
        val deserialized = json.decodeFromString(CoveredFileLine.serializer(), serialized)
        assertEquals(1, deserialized.lineNumber)
        assertEquals("<?php", deserialized.content)
        assertEquals(3, deserialized.testCount)
        assertTrue(deserialized.isCovered)
    }

    @Test
    fun coveredFileLineUncovered() {
        val line = CoveredFileLine(lineNumber = 5, content = "// comment", testCount = 0, isCovered = false)
        assertFalse(line.isCovered)
        assertEquals(0, line.testCount)
    }

    // --- CoverageFileResult ---

    @Test
    fun coverageFileResultNewFormat() {
        val lines = listOf(
            CoveredFileLine(1, "<?php", 2, true),
            CoveredFileLine(2, "", 0, false),
            CoveredFileLine(3, "class Foo {}", 5, true),
        )
        val result = CoverageFileResult(
            file = "src/Foo.php",
            commitHash = "abc12345",
            totalLinesInFile = 100,
            coveredLinesInFile = 50,
            uncoveredLinesInFile = 50,
            offset = 0,
            limit = 3,
            totalMatchingLines = 3,
            lines = lines,
        )
        val serialized = json.encodeToString(CoverageFileResult.serializer(), result)
        val deserialized = json.decodeFromString(CoverageFileResult.serializer(), serialized)
        assertEquals("src/Foo.php", deserialized.file)
        assertEquals("abc12345", deserialized.commitHash)
        assertEquals(100, deserialized.totalLinesInFile)
        assertEquals(50, deserialized.coveredLinesInFile)
        assertEquals(3, deserialized.lines.size)
        assertEquals("<?php", deserialized.lines[0].content)
        assertEquals(2, deserialized.lines[0].testCount)
        assertEquals("class Foo {}", deserialized.lines[2].content)
        assertEquals(5, deserialized.lines[2].testCount)
    }

    @Test
    fun coverageFileResultDefaultValues() {
        val result = CoverageFileResult(file = "x.php")
        assertEquals(0, result.totalLinesInFile)
        assertEquals(0, result.coveredLinesInFile)
        assertEquals(0, result.uncoveredLinesInFile)
        assertTrue(result.lines.isEmpty())
    }

    // --- CoverageLineTestsResult ---

    @Test
    fun coverageLineTestsResultSerialization() {
        val result = CoverageLineTestsResult(
            file = "src/Foo.php",
            lineNumber = 10,
            isCovered = true,
            totalTests = 3,
            offset = 0,
            limit = 2,
            tests = listOf("TestA", "TestB"),
        )
        val serialized = json.encodeToString(CoverageLineTestsResult.serializer(), result)
        val deserialized = json.decodeFromString(CoverageLineTestsResult.serializer(), serialized)
        assertEquals(10, deserialized.lineNumber)
        assertTrue(deserialized.isCovered)
        assertEquals(3, deserialized.totalTests)
        assertEquals(2, deserialized.tests.size)
        assertEquals("TestA", deserialized.tests[0])
        assertEquals("TestB", deserialized.tests[1])
    }

    @Test
    fun coverageLineTestsResultEmpty() {
        val result = CoverageLineTestsResult(
            file = "src/Foo.php",
            lineNumber = 20,
            totalTests = 0,
            offset = 0,
            limit = 5,
            tests = emptyList(),
        )
        assertFalse(result.isCovered)
        assertTrue(result.tests.isEmpty())
    }

    @Test
    fun coverageLineTestsResultDefaultValues() {
        val result = CoverageLineTestsResult(file = "x.php", lineNumber = 1)
        assertFalse(result.isCovered)
        assertEquals(0, result.totalTests)
        assertTrue(result.tests.isEmpty())
    }

    // --- CoveredLineFirstTest ---

    @Test
    fun coveredLineFirstTestSerialization() {
        val line = CoveredLineFirstTest(lineNumber = 5, isCovered = true, totalTests = 3, firstTest = "TestA")
        val serialized = json.encodeToString(CoveredLineFirstTest.serializer(), line)
        val deserialized = json.decodeFromString(CoveredLineFirstTest.serializer(), serialized)
        assertEquals(5, deserialized.lineNumber)
        assertEquals(true, deserialized.isCovered)
        assertEquals(3, deserialized.totalTests)
        assertEquals("TestA", deserialized.firstTest)
    }

    @Test
    fun coveredLineFirstTestUncovered() {
        val line = CoveredLineFirstTest(lineNumber = 10, isCovered = false, totalTests = 0, firstTest = null)
        assertNull(line.firstTest)
        assertEquals(false, line.isCovered)
    }

    // --- CoverageMultipleLinesResult ---

    @Test
    fun coverageMultipleLinesResultSerialization() {
        val lines = listOf(
            CoveredLineFirstTest(1, true, 2, "TestA"),
            CoveredLineFirstTest(5, false, 0, null),
        )
        val result = CoverageMultipleLinesResult(file = "src/Foo.php", lines = lines)
        val serialized = json.encodeToString(CoverageMultipleLinesResult.serializer(), result)
        val deserialized = json.decodeFromString(CoverageMultipleLinesResult.serializer(), serialized)
        assertEquals(2, deserialized.lines.size)
        assertEquals(1, deserialized.lines[0].lineNumber)
        assertEquals("TestA", deserialized.lines[0].firstTest)
        assertNull(deserialized.lines[1].firstTest)
    }

    @Test
    fun coverageMultipleLinesResultDefaultValues() {
        val result = CoverageMultipleLinesResult(file = "x.php")
        assertTrue(result.lines.isEmpty())
    }

    // --- CoverageListResult (unchanged) ---

    @Test
    fun coverageListResultSerialization() {
        val result = CoverageListResult(
            path = "/src/Service/",
            commitHash = "abc12345",
            totalFiles = 3,
            files = listOf(
                CoverageFileInfo("a.php", 10, 12, 83),
                CoverageFileInfo("b.php", 5, 5, 100),
            ),
            nextOffset = 50,
        )
        val serialized = json.encodeToString(CoverageListResult.serializer(), result)
        val deserialized = json.decodeFromString(CoverageListResult.serializer(), serialized)
        assertEquals("/src/Service/", deserialized.path)
        assertEquals("abc12345", deserialized.commitHash)
        assertEquals(3, deserialized.totalFiles)
        assertEquals(2, deserialized.files.size)
        assertEquals("a.php", deserialized.files[0].path)
        assertEquals(10, deserialized.files[0].coveredLines)
        assertEquals(12, deserialized.files[0].totalLines)
        assertEquals(83, deserialized.files[0].coveragePercent)
        assertEquals("b.php", deserialized.files[1].path)
        assertEquals(100, deserialized.files[1].coveragePercent)
        assertEquals(50, deserialized.nextOffset)
    }

    @Test
    fun coverageListResultNoNextPage() {
        val result = CoverageListResult(
            path = "/",
            totalFiles = 5,
            files = listOf(CoverageFileInfo("a.php", 1, 2, 50)),
            nextOffset = null,
        )
        val serialized = json.encodeToString(CoverageListResult.serializer(), result)
        val deserialized = json.decodeFromString(CoverageListResult.serializer(), serialized)
        assertNull(deserialized.nextOffset)
        assertEquals(5, deserialized.totalFiles)
    }

    @Test
    fun coverageListResultDefaultValues() {
        val result = CoverageListResult(path = "/")
        assertEquals(0, result.totalFiles)
        assertTrue(result.files.isEmpty())
        assertNull(result.nextOffset)
        assertNull(result.commitHash)
    }

    // --- CoverageFileInfo (unchanged) ---

    @Test
    fun coverageFileInfoSerialization() {
        val info = CoverageFileInfo("src/Foo.php", 8, 10, 80)
        val serialized = json.encodeToString(CoverageFileInfo.serializer(), info)
        val deserialized = json.decodeFromString(CoverageFileInfo.serializer(), serialized)
        assertEquals("src/Foo.php", deserialized.path)
        assertEquals(8, deserialized.coveredLines)
        assertEquals(10, deserialized.totalLines)
        assertEquals(80, deserialized.coveragePercent)
    }

    // --- PAGE_SIZE constant ---

    @Test
    fun pageSizeConstant() {
        assertEquals(50, CoverageMcpToolset.PAGE_SIZE)
    }

    // --- LocalCoverageStatusResult ---

    @Test
    fun localCoverageStatusSerialization() {
        val status = LocalCoverageStatusResult(
            collectLocalCoverage = true,
            localCoverageDir = "storage/coverage",
            watchedDirectory = "/repo/storage/coverage",
            localRuns = 3,
            staleRuns = 1,
            localTests = 7,
            localFiles = 12,
            pluginEnabled = true,
        )
        val serialized = json.encodeToString(LocalCoverageStatusResult.serializer(), status)
        val deserialized = json.decodeFromString(LocalCoverageStatusResult.serializer(), serialized)
        assertEquals(status, deserialized)
        assertTrue(deserialized.collectLocalCoverage)
        assertEquals("/repo/storage/coverage", deserialized.watchedDirectory)
    }

    @Test
    fun localCoverageStatusDefaultsToNoLocalRuns() {
        val status = LocalCoverageStatusResult(
            collectLocalCoverage = false,
            localCoverageDir = "storage/coverage",
        )
        assertNull(status.watchedDirectory)
        assertEquals(0, status.localRuns)
        assertEquals(0, status.staleRuns)
        assertEquals(0, status.localTests)
    }
}
