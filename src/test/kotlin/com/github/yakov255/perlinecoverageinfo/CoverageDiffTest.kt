package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageDiffTest {

    @Test
    fun `featureOnly is empty when lists are identical`() {
        assertEquals(emptyList<String>(), CoverageDiff.featureOnly(listOf("t1", "t2"), listOf("t1", "t2")))
    }

    @Test
    fun `featureOnly returns whole primary when baseline empty`() {
        assertEquals(listOf("t1", "t2"), CoverageDiff.featureOnly(listOf("t1", "t2"), emptyList()))
    }

    @Test
    fun `featureOnly is empty when primary empty`() {
        assertEquals(emptyList<String>(), CoverageDiff.featureOnly(emptyList(), listOf("t1")))
    }

    @Test
    fun `featureOnly returns set-diff preserving primary order`() {
        // primary has t2, t1, t3; baseline has t1; expect t2, t3 (primary order, minus t1).
        assertEquals(listOf("t2", "t3"), CoverageDiff.featureOnly(listOf("t2", "t1", "t3"), listOf("t1")))
    }

    @Test
    fun `featureOnly is empty when primary is subset of baseline`() {
        // Test removed on the branch is not "feature-only" — branch lost a test, didn't add one.
        assertEquals(emptyList<String>(), CoverageDiff.featureOnly(listOf("t1"), listOf("t1", "t2")))
    }

    @Test
    fun `union deduplicates and keeps primary first`() {
        assertEquals(
            listOf("p1", "p2", "b1"),
            CoverageDiff.union(listOf("p1", "p2"), listOf("p1", "b1")),
        )
    }

    @Test
    fun `union dedupes within a single side`() {
        assertEquals(listOf("a", "b"), CoverageDiff.union(listOf("a", "a", "b"), listOf("b", "a")))
    }

    @Test
    fun `union with empty baseline returns distinct primary`() {
        assertEquals(listOf("a", "b"), CoverageDiff.union(listOf("a", "b", "a"), emptyList()))
    }

    @Test
    fun `union with empty primary returns distinct baseline`() {
        assertEquals(listOf("a", "b"), CoverageDiff.union(emptyList(), listOf("a", "b", "a")))
    }
}
