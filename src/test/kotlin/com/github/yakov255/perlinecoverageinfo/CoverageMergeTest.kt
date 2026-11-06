package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageMergeTest {

    @Test
    fun `concatenates per-line tests in report order`() {
        val first = mapOf(
            "src/a.php" to mapOf(
                1 to listOf("t1", "t2"),
                2 to emptyList(),
            ),
        )
        val second = mapOf(
            "src/a.php" to mapOf(
                1 to listOf("t3", "t1"),
                2 to listOf("t9"),
            ),
            "src/b.php" to mapOf(
                5 to listOf("t4", "t4"),
            ),
        )

        val merged = CoverageMerge.merge(listOf(first, second)).coverage

        assertEquals(setOf("src/a.php", "src/b.php"), merged.keys)
        val a = merged.getValue("src/a.php")
        // Duplicates are removed by the writer, not by the merge.
        assertEquals(listOf("t1", "t2", "t3", "t1"), a.getValue(1))
        assertEquals(listOf("t9"), a.getValue(2))
        assertEquals(listOf("t4", "t4"), merged.getValue("src/b.php").getValue(5))
    }

    @Test
    fun `keeps uncovered lines and does not mutate single reports`() {
        val single = mapOf("src/c.php" to mapOf(7 to emptyList(), 8 to listOf("t1")))

        val merged = CoverageMerge.merge(listOf(single)).coverage

        assertEquals(emptyList<String>(), merged.getValue("src/c.php").getValue(7))
        assertEquals(listOf("t1"), merged.getValue("src/c.php").getValue(8))
        assertSame(single, merged)
    }

    @Test
    fun `shares one merged list between lines with the same contributing sets`() {
        // The parser hands out one list instance per test set, so these lines share it.
        val setA = listOf("t1", "t2")
        val setB = listOf("t3")
        val first = mapOf(
            "src/d.php" to mapOf(
                1 to setA,
                2 to setA,
                3 to setA,
            ),
        )
        val second = mapOf(
            "src/d.php" to mapOf(
                1 to setB,
                2 to setB,
                3 to listOf("t4"),
            ),
        )

        val result = CoverageMerge.merge(listOf(first, second))

        val lines = result.coverage.getValue("src/d.php")
        assertEquals(listOf("t1", "t2", "t3"), lines.getValue(1))
        assertSame("lines with the same set combination share one list", lines.getValue(1), lines.getValue(2))
        assertEquals(listOf("t1", "t2", "t4"), lines.getValue(3))
        assertEquals(3, result.stats.lines)
        assertEquals(2, result.stats.unions)
    }

    @Test
    fun `dedupe per line removes repeated names for the in-memory fallback`() {
        val coverage = mapOf(
            "src/e.php" to mapOf(
                1 to listOf("t1", "t1", "t2", "t2", "t3"),
                2 to emptyList(),
                3 to listOf("t4"),
            ),
        )

        val deduped = CoverageMerge.dedupePerLine(coverage)

        assertEquals(listOf("t1", "t2", "t3"), deduped.getValue("src/e.php").getValue(1))
        assertEquals(emptyList<String>(), deduped.getValue("src/e.php").getValue(2))
        assertEquals(listOf("t4"), deduped.getValue("src/e.php").getValue(3))
    }

    @Test
    fun `empty report list yields empty map`() {
        val merged = CoverageMerge.merge(emptyList())
        assertEquals(emptyMap<String, Map<Int, List<String>>>(), merged.coverage)
        assertEquals(0, merged.stats.lines)
        assertTrue(merged.coverage.isEmpty())
    }
}
