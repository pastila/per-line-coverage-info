package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class BehatTestRunnerTest {

    @Test
    fun `buildPositionalPathArgs creates separate args for each line`() {
        val relativePaths = mapOf(
            "features/a.feature" to listOf(10, 20),
            "features/b.feature" to listOf(5)
        )
        val result = buildPositionalPathArgsForTest(relativePaths)
        assertEquals("features/a.feature:10 features/a.feature:20 features/b.feature:5", result)
    }

    @Test
    fun `buildPositionalPathArgs handles file with no specific lines`() {
        val relativePaths = mapOf(
            "features/a.feature" to emptyList(),
            "features/b.feature" to listOf(1)
        )
        val result = buildPositionalPathArgsForTest(relativePaths)
        assertEquals("features/a.feature features/b.feature:1", result)
    }

    @Test
    fun `buildPositionalPathArgs quotes paths with spaces`() {
        val relativePaths = mapOf(
            "features/my feature.feature" to listOf(10)
        )
        val result = buildPositionalPathArgsForTest(relativePaths)
        assertEquals("\"features/my feature.feature:10\"", result)
    }

    @Test
    fun `buildPositionalPathArgs handles multiple files with multiple lines`() {
        val relativePaths = mapOf(
            "suite-a/alpha.feature" to listOf(3, 6),
            "suite-b/beta.feature" to listOf(1)
        )
        val result = buildPositionalPathArgsForTest(relativePaths)
        assertEquals("suite-a/alpha.feature:3 suite-a/alpha.feature:6 suite-b/beta.feature:1", result)
    }
}
