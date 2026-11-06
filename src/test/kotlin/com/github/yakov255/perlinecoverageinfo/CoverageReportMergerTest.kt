package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.*
import org.junit.Test

class CoverageReportMergerTest {
    @Test
    fun mergesReportsPreservingUncoveredLinesAndDistinctTestOrder() {
        val merger = CoverageReportMerger()
        merger.add(mapOf("a.php" to mapOf(1 to listOf("a", "a"), 2 to emptyList(), 3 to listOf("a"))))
        merger.add(mapOf(
            "a.php" to mapOf(1 to listOf("b", "a"), 2 to listOf("c"), 3 to emptyList(), 4 to emptyList()),
            "b.php" to mapOf(10 to listOf("d")),
        ))
        assertEquals(mapOf(
            "a.php" to mapOf(1 to listOf("a", "b"), 2 to listOf("c"), 3 to listOf("a"), 4 to emptyList()),
            "b.php" to mapOf(10 to listOf("d")),
        ), merger.coverage)
    }

    @Test
    fun keepsSharedTestSetsWhenAddingAndCombiningReports() {
        val merger = CoverageReportMerger()
        val first = listOf("a", "b")
        val second = listOf("b", "c")
        merger.add(mapOf("a.php" to (1..1000).associateWith { first }))
        val initial = merger.coverage.getValue("a.php")
        assertSame(initial[1], initial[1000])
        merger.add(mapOf("a.php" to (1..1000).associateWith { second }))
        val combined = merger.coverage.getValue("a.php")
        assertEquals(listOf("a", "b", "c"), combined[1])
        assertSame(combined[1], combined[1000])
    }
}
