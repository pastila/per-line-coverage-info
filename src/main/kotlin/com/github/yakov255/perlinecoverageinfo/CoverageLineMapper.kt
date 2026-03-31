package com.github.yakov255.perlinecoverageinfo

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.DumbProgressIndicator
import java.io.File

/**
 * Maps line numbers from a coverage commit to current editor content.
 * Uses IntelliJ's ComparisonManager to diff old file content (from git) against
 * current editor content, then builds a mapping of old→new line numbers
 * for lines whose content is unchanged.
 */
object CoverageLineMapper {

    private val log = Logger.getInstance(CoverageLineMapper::class.java)

    /**
     * Compute a mapping from old (coverage commit) 1-based line numbers
     * to current 1-based line numbers for lines that haven't changed.
     *
     * @return a map of oldLineNumber→newLineNumber for unchanged lines,
     *         or null if the old content could not be retrieved.
     */
    fun computeMapping(gitRoot: File, commitHash: String, relativePath: String, currentContent: String): Map<Int, Int>? {
        val oldContent = getOldContent(gitRoot, commitHash, relativePath) ?: return null

        // Fast path: content identical — identity mapping
        if (oldContent == currentContent) return null // null signals "no mapping needed, use raw"

        return buildLineMapping(oldContent, currentContent)
    }

    /**
     * Retrieves file content at a specific commit via `git show`.
     */
    private fun getOldContent(gitRoot: File, commitHash: String, relativePath: String): String? {
        return CoverageApiClient.runGitCommand(gitRoot, "show", "$commitHash:$relativePath")
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

    /**
     * Incrementally updates a line mapping after a document change.
     * This avoids re-diffing the entire file on each keystroke.
     *
     * @param currentMapping the existing oldLine→newLine mapping
     * @param changeStartLine 0-based line where the change starts in the current document
     * @param linesRemoved number of lines removed
     * @param linesAdded number of lines added
     * @param changedLineContents list of (0-based new line index, new content) for lines at the change site
     * @param oldContentLines the original (coverage commit) file content split into lines
     * @return updated mapping
     */
    fun updateMappingAfterChange(
        currentMapping: Map<Int, Int>,
        changeStartLine: Int, // 0-based in new document
        linesRemoved: Int,
        linesAdded: Int,
        changedLineContents: List<String>,
        oldContentLines: List<String>
    ): Map<Int, Int> {
        val delta = linesAdded - linesRemoved
        val result = mutableMapOf<Int, Int>()
        val changeStartLine1 = changeStartLine + 1 // 1-based

        for ((oldLine, newLine) in currentMapping) {
            when {
                // Lines before the change: unchanged
                newLine < changeStartLine1 -> result[oldLine] = newLine

                // Lines in the change zone: need to verify content still matches
                newLine < changeStartLine1 + linesRemoved -> {
                    // This line was in the removed/replaced zone
                    // Check if a corresponding new line has same content
                    val offsetInChange = newLine - changeStartLine1
                    if (offsetInChange < linesAdded && offsetInChange < changedLineContents.size) {
                        val newContent = changedLineContents[offsetInChange].trim()
                        val oldContent = oldContentLines.getOrNull(oldLine - 1)?.trim()
                        if (newContent == oldContent) {
                            result[oldLine] = newLine // content still matches
                        }
                        // else: line content changed, drop from mapping
                    }
                    // else: line was deleted, drop from mapping
                }

                // Lines after the change zone: shift by delta
                else -> result[oldLine] = newLine + delta
            }
        }

        return result
    }
}
