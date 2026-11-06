package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CoverageMergeTest {

    @Test
    fun `unions tests per line in first-seen order`() {
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

        val merged = CoverageMerge.merge(listOf(first, second))

        assertEquals(setOf("src/a.php", "src/b.php"), merged.keys)
        val a = merged.getValue("src/a.php")
        assertEquals(listOf("t1", "t2", "t3"), a.getValue(1))
        assertEquals(listOf("t9"), a.getValue(2))
        assertEquals(listOf("t4"), merged.getValue("src/b.php").getValue(5))
    }

    @Test
    fun `keeps uncovered lines and does not mutate single reports`() {
        val single = mapOf("src/c.php" to mapOf(7 to emptyList(), 8 to listOf("t1")))

        val merged = CoverageMerge.merge(listOf(single))

        assertEquals(emptyList<String>(), merged.getValue("src/c.php").getValue(7))
        assertEquals(listOf("t1"), merged.getValue("src/c.php").getValue(8))
        assertSame(single, merged)
    }

    @Test
    fun `empty report list yields empty map`() {
        assertEquals(emptyMap<String, Map<Int, List<String>>>(), CoverageMerge.merge(emptyList()))
    }
}
