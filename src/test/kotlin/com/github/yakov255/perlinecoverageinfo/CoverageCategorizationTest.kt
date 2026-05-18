package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Black-box test of [CoverageHighlighter.categorizeLine] — the per-line classification
 * that the gutter renderer keys off of. Covers every combination that affects the
 * displayed colour.
 */
class CoverageCategorizationTest {

    private data class Case(
        val name: String,
        val primary: List<String>,
        val baseline: List<String>,
        val hasBaseline: Boolean,
        val expected: CoverageCategory,
    )

    private val cases = listOf(
        Case("single mode, covered", listOf("t1"), emptyList(), false, CoverageCategory.COVERED),
        Case("single mode, uncovered", emptyList(), emptyList(), false, CoverageCategory.UNCOVERED),
        Case("dual, identical → covered (no new)", listOf("t1"), listOf("t1"), true, CoverageCategory.COVERED),
        Case("dual, branch added test but master also covers → covered", listOf("t1", "t2"), listOf("t1"), true, CoverageCategory.COVERED),
        Case("dual, branch removed test → still covered", listOf("t1"), listOf("t1", "t2"), true, CoverageCategory.COVERED),
        Case("dual, primary empty → uncovered (primary wins)", emptyList(), listOf("t1"), true, CoverageCategory.UNCOVERED),
        Case("dual, master has no coverage but branch does → feature only", listOf("t9"), emptyList(), true, CoverageCategory.FEATURE_ONLY),
    )

    @Test
    fun `categorizeLine matches the truth table`() {
        for (case in cases) {
            val actual = CoverageHighlighter.categorizeLine(case.primary, case.baseline, case.hasBaseline)
            assertEquals("case='${case.name}'", case.expected, actual)
        }
    }
}
