package com.github.yakov255.perlinecoverageinfo

/**
 * Helpers for diffing per-line test sets between primary and baseline coverage.
 *
 * "Diff" between two binary coverage files reduces to comparing the test-name lists
 * for each line — once both have been mapped onto the current document via
 * [LineMappingService].
 */
object CoverageDiff {

    /**
     * Returns tests that appear in [primary] but not in [baseline].
     * Order follows [primary]; comparison is by exact test-name string.
     */
    fun featureOnly(primary: List<String>, baseline: List<String>): List<String> {
        if (primary.isEmpty()) return emptyList()
        if (baseline.isEmpty()) return primary.toList()
        val baselineSet = baseline.toHashSet()
        return primary.filter { it !in baselineSet }
    }

    /**
     * Returns the de-duplicated union of [primary] and [baseline], primary tests first.
     */
    fun union(primary: List<String>, baseline: List<String>): List<String> {
        if (baseline.isEmpty()) return primary.distinct()
        if (primary.isEmpty()) return baseline.distinct()
        val seen = LinkedHashSet<String>(primary.size + baseline.size)
        seen.addAll(primary)
        seen.addAll(baseline)
        return seen.toList()
    }
}
