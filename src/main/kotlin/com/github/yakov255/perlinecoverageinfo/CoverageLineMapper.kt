package com.github.yakov255.perlinecoverageinfo

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator

/**
 * Maps line numbers from a coverage commit to current editor content.
 * Uses IntelliJ's ComparisonManager to diff old file content against
 * current editor content, then builds a mapping of old→new line numbers
 * for lines whose content is unchanged.
 */
object CoverageLineMapper {

    private val log = Logger.getInstance(CoverageLineMapper::class.java)

    /**
     * Computes a line mapping from old content to current content using ComparisonManager.
     *
     * @return a map of oldLineNumber→newLineNumber for unchanged lines,
     *         or null if content is identical (no mapping needed, use raw coverage).
     */
    fun computeMappingFromContent(oldContent: String, currentContent: String): Map<Int, Int>? {
        if (oldContent == currentContent) return null
        return buildLineMapping(oldContent, currentContent)
    }

    /**
     * Builds a mapping of old 1-based line numbers → new 1-based line numbers
     * for lines that are unchanged between oldContent and newContent.
     *
     * Uses IntelliJ's diff engine to find changed ranges, then computes
     * the shift for unchanged lines around those ranges.
     */
    fun buildLineMapping(oldContent: String, newContent: String): Map<Int, Int> {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        val comparisonManager = ComparisonManager.getInstance()
        val ranges = comparisonManager.compareLines(
            oldContent, newContent,
            ComparisonPolicy.DEFAULT,
            DumbProgressIndicator.INSTANCE
        )

        // Walk through both files in parallel, building old→new mapping for unchanged lines.
        // The diff ranges tell us which blocks changed. Between changed blocks,
        // lines correspond 1:1 with a shift determined by cumulative insertions/deletions.
        val mapping = mutableMapOf<Int, Int>()
        var oldIdx = 0
        var newIdx = 0

        // Sort ranges by start position (they should already be sorted)
        val sortedRanges = ranges.sortedBy { it.startLine1 }

        for (range in sortedRanges) {
            // Map unchanged lines before this range
            while (oldIdx < range.startLine1 && newIdx < range.startLine2) {
                mapping[oldIdx + 1] = newIdx + 1 // convert to 1-based
                oldIdx++
                newIdx++
            }
            // Skip past the changed range
            oldIdx = range.endLine1
            newIdx = range.endLine2
        }

        // Map remaining unchanged lines after the last range
        while (oldIdx < oldLines.size && newIdx < newLines.size) {
            mapping[oldIdx + 1] = newIdx + 1
            oldIdx++
            newIdx++
        }

        return mapping
    }

    /**
     * Applies a line mapping to coverage data, remapping old line numbers to new ones.
     * Lines that were changed/deleted are excluded from the result.
     *
     * @param originalCoverage the raw coverage data with old line numbers
     * @param lineMapping oldLineNumber→newLineNumber for unchanged lines
     * @return coverage data with remapped line numbers
     */
    fun mapCoverage(originalCoverage: Map<Int, List<String>>, lineMapping: Map<Int, Int>): Map<Int, List<String>> {
        val result = mutableMapOf<Int, List<String>>()
        for ((oldLine, tests) in originalCoverage) {
            val newLine = lineMapping[oldLine] ?: continue // line was changed/deleted — skip
            result[newLine] = tests
        }
        return result
    }


}
