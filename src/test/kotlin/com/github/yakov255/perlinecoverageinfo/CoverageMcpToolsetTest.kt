package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CoverageMcpToolsetTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- collapseToRanges ---

    @Test
    fun collapseToRangesEmpty() {
        assertEquals("", CoverageMcpToolset.collapseToRanges(emptyList()))
    }

    @Test
    fun collapseToRangesSingleLine() {
        assertEquals("5", CoverageMcpToolset.collapseToRanges(listOf(5)))
    }

    @Test
    fun collapseToRangesConsecutiveLines() {
        assertEquals("1-5", CoverageMcpToolset.collapseToRanges(listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun collapseToRangesMixed() {
        assertEquals("1-3, 7, 10-12", CoverageMcpToolset.collapseToRanges(listOf(1, 2, 3, 7, 10, 11, 12)))
    }

    @Test
    fun collapseToRangesTwoRanges() {
        assertEquals("1-2, 5-6", CoverageMcpToolset.collapseToRanges(listOf(1, 2, 5, 6)))
    }

    @Test
    fun collapseToRangesAllSeparate() {
        assertEquals("1, 3, 5, 7", CoverageMcpToolset.collapseToRanges(listOf(1, 3, 5, 7)))
    }

    // --- CoverageFileResult serialization ---

    @Test
    fun coverageFileResultSummarySerialization() {
        val result = CoverageFileResult(
            file = "src/Service/Foo.php",
            commitHash = "abc12345",
            coveredLines = 10,
            uncoveredLines = 2,
            totalLines = 12,
            coveredRanges = "1-10",
            uncoveredRanges = "11-12",
        )

        val serialized = json.encodeToString(CoverageFileResult.serializer(), result)
        val deserialized = json.decodeFromString(CoverageFileResult.serializer(), serialized)

        assertEquals("src/Service/Foo.php", deserialized.file)
        assertEquals("abc12345", deserialized.commitHash)
        assertEquals(10, deserialized.coveredLines)
        assertEquals(2, deserialized.uncoveredLines)
        assertEquals(12, deserialized.totalLines)
        assertNull(deserialized.lines)
        assertEquals("1-10", deserialized.coveredRanges)
        assertEquals("11-12", deserialized.uncoveredRanges)
    }

    @Test
    fun coverageFileResultDetailedSerialization() {
        val result = CoverageFileResult(
            file = "src/Service/Foo.php",
            commitHash = "abc12345",
            coveredLines = 3,
            uncoveredLines = 1,
            totalLines = 4,
            lines = mapOf(1 to "TestA, TestB", 2 to "TestA", 3 to "TestC", 4 to ""),
            coveredRanges = "1-3",
            uncoveredRanges = "4",
        )

        val serialized = json.encodeToString(CoverageFileResult.serializer(), result)
        val deserialized = json.decodeFromString(CoverageFileResult.serializer(), serialized)

        val lines = checkNotNull(deserialized.lines) { "lines should not be null" }
        assertEquals(4, lines.size)
        assertEquals("TestA, TestB", lines[1])
        assertEquals("TestA", lines[2])
        assertEquals("TestC", lines[3])
        assertEquals("", lines[4])
    }

    @Test
    fun coverageFileResultDefaultValues() {
        val result = CoverageFileResult(file = "x.php")
        assertEquals(0, result.coveredLines)
        assertEquals(0, result.uncoveredLines)
        assertEquals(0, result.totalLines)
        assertNull(result.lines)
        assertEquals("", result.coveredRanges)
        assertEquals("", result.uncoveredRanges)
        assertNull(result.commitHash)
    }

    // --- CoverageListResult serialization ---

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

    // --- CoverageFileInfo serialization ---

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
}
