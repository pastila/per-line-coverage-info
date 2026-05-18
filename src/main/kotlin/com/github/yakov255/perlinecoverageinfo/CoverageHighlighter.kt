package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.awt.Color
import java.io.File
import java.nio.file.Paths

object CoverageHighlighter {

    private const val COVERAGE_LAYER = HighlighterLayer.LAST + 1
    val COVERAGE_HIGHLIGHTER_KEY = Key.create<Boolean>("PER_LINE_COVERAGE_HIGHLIGHTER")

    // Background colours for the line strip. Alpha is low so the editor's own
    // syntax highlighting remains readable on top.
    private val BG_COVERED = Color(100, 180, 120, 60)
    private val BG_UNCOVERED = Color(210, 110, 110, 60)
    private val BG_FEATURE = Color(80, 140, 220, 90)

    fun applyToOpenEditors(project: Project) {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasData()) return

        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            applyToEditor(editor, project)
        }
    }

    fun clearAllEditors(project: Project) {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            clearCoverageHighlighters(editor)
        }
    }

    fun applyToEditor(editor: Editor, project: Project) {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasData()) return
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            clearCoverageHighlighters(editor)
            return
        }

        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return

        val lineMappingService = LineMappingService.getInstance(project)
        val coverageLines = lineMappingService.getMappedCoverage(virtualFile.path, document.text)
            ?: findCoverageForFile(virtualFile.path, project)
            ?: return

        val baselineLines: Map<Int, List<String>>? = if (dataService.hasBaseline()) {
            lineMappingService.getMappedBaselineCoverage(virtualFile.path, document.text)
                ?: findBaselineCoverageForFile(virtualFile.path, project)
        } else {
            null
        }

        clearCoverageHighlighters(editor)

        val markupModel = editor.markupModel

        for (line in 0 until document.lineCount) {
            val lineNumber = line + 1 // coverage data is 1-based
            val tests = coverageLines[lineNumber] ?: continue

            val baselineTests = baselineLines?.get(lineNumber) ?: emptyList()
            val category = categorizeLine(tests, baselineTests, baselineLines != null)
            val bg = backgroundFor(category)
            val startOffset = document.getLineStartOffset(line)
            val endOffset = document.getLineEndOffset(line)
            val highlighter = markupModel.addRangeHighlighter(
                startOffset, endOffset, COVERAGE_LAYER,
                TextAttributes(null, bg, null, null, 0),
                HighlighterTargetArea.LINES_IN_RANGE
            )
            highlighter.lineMarkerRenderer = CoverageGutterRenderer(
                lineNumber = lineNumber,
                tests = tests,
                baselineTests = baselineTests,
                hasBaseline = baselineLines != null,
                category = category,
            )
            highlighter.putUserData(COVERAGE_HIGHLIGHTER_KEY, true)
        }
    }

    /**
     * Pure helper exposed for tests: classify a line given its primary and baseline test lists.
     * - Empty primary → UNCOVERED (regardless of baseline; we trust primary as the source of truth
     *   for what tests currently exercise the line).
     * - Has baseline + master has no coverage on this line, but branch does → FEATURE_ONLY.
     * - Otherwise → COVERED.
     */
    @JvmStatic
    internal fun categorizeLine(
        primary: List<String>,
        baseline: List<String>,
        hasBaseline: Boolean,
    ): CoverageCategory {
        if (primary.isEmpty()) return CoverageCategory.UNCOVERED
        if (!hasBaseline) return CoverageCategory.COVERED
        return if (baseline.isEmpty()) CoverageCategory.FEATURE_ONLY else CoverageCategory.COVERED
    }

    private fun backgroundFor(category: CoverageCategory): Color = when (category) {
        CoverageCategory.UNCOVERED -> BG_UNCOVERED
        CoverageCategory.FEATURE_ONLY -> BG_FEATURE
        CoverageCategory.COVERED -> BG_COVERED
    }

    fun clearCoverageHighlighters(editor: Editor) {
        val toRemove = editor.markupModel.allHighlighters.filter {
            it.getUserData(COVERAGE_HIGHLIGHTER_KEY) == true
        }
        for (h in toRemove) {
            editor.markupModel.removeHighlighter(h)
        }
    }

    private fun findCoverageForFile(absolutePath: String, project: Project): Map<Int, List<String>>? {
        val dataService = CoverageDataService.getInstance(project)
        val gitRoot = dataService.gitRoot ?: return null
        val gitRelativePath = toGitRelative(absolutePath, gitRoot.path) ?: return null
        return CoveragePathResolver.resolve(dataService, listOf(gitRelativePath))
    }

    private fun findBaselineCoverageForFile(absolutePath: String, project: Project): Map<Int, List<String>>? {
        val dataService = CoverageDataService.getInstance(project)
        val gitRoot = dataService.gitRoot ?: return null
        val gitRelativePath = toGitRelative(absolutePath, gitRoot.path) ?: return null
        return CoveragePathResolver.resolveBaseline(dataService, listOf(gitRelativePath))
    }

    private fun toGitRelative(absolutePath: String, gitRootPath: String): String? = try {
        Paths.get(gitRootPath).relativize(Paths.get(absolutePath))
            .toString()
            .replace(File.separatorChar, '/')
    } catch (_: IllegalArgumentException) {
        null
    }
}

