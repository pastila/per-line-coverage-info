package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.nio.file.Paths

object CoverageHighlighter {

    private const val COVERAGE_LAYER = HighlighterLayer.LAST + 1
    val COVERAGE_HIGHLIGHTER_KEY = Key.create<Boolean>("PER_LINE_COVERAGE_HIGHLIGHTER")

    fun applyToOpenEditors(project: Project) {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasData()) {
            return
        }

        var count = 0
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            applyToEditor(editor, project)
            count++
        }
    }

    fun clearAllEditors(project: Project) {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            clearCoverageHighlighters(editor)
        }
    }

    /**
     * Applies coverage highlights to a single editor asynchronously.
     *
     * Phase 1 (synchronous — any thread): captures a snapshot of the document text and
     * relevant service state. This is fast (no I/O).
     *
     * Phase 2 (pooled thread): git show + Cov4Reader reads + line-mapping computation.
     *
     * Phase 3 (EDT via invokeLater): clears old highlighters and applies new ones.
     * If the document text changed since the snapshot, the apply is skipped — a
     * subsequent [CoverageDocumentListener] alarm will retrigger.
     */
    fun applyToEditor(editor: Editor, project: Project) {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasData()) {
            return
        }
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            clearCoverageHighlighters(editor)
            return
        }

        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document)
        if (virtualFile == null) {
            return
        }

        val snapshot = HighlightSnapshot(
            text = document.text,
            lineCount = document.lineCount,
            hasBaseline = dataService.hasBaseline(),
            filePath = virtualFile.path,
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            val lineMappingService = LineMappingService.getInstance(project)
            val coverageLines = lineMappingService.getMappedCoverage(snapshot.filePath, snapshot.text)
                ?: findCoverageForFile(snapshot.filePath, project)
            if (coverageLines == null) {
                return@executeOnPooledThread
            }

            val baselineLines: Map<Int, List<String>>? = if (snapshot.hasBaseline) {
                lineMappingService.getMappedBaselineCoverage(snapshot.filePath, snapshot.text)
                    ?: findBaselineCoverageForFile(snapshot.filePath, project)
            } else {
                null
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed || editor.isDisposed) return@invokeLater
                if (document.text != snapshot.text) {
                    return@invokeLater
                }

                clearCoverageHighlighters(editor)

                val warnings = CoverageWarningService.getInstance(project).getWarnings()
                val renderer = FileCoverageRenderer(
                    filePath = snapshot.filePath,
                    project = project,
                    coverageLines = coverageLines,
                    baselineLines = baselineLines,
                    hasBaseline = snapshot.hasBaseline,
                    warnings = warnings,
                )

                val highlighter = editor.markupModel.addRangeHighlighter(
                    0, document.textLength, COVERAGE_LAYER,
                    TextAttributes(null, null, null, null, 0),
                    HighlighterTargetArea.LINES_IN_RANGE
                )
                highlighter.lineMarkerRenderer = renderer
                highlighter.putUserData(COVERAGE_HIGHLIGHTER_KEY, true)
            }
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

/**
 * Snapshot of document state captured synchronously before async I/O work.
 * Used to detect stale results when the document changes during background computation.
 */
private data class HighlightSnapshot(
    val text: String,
    val lineCount: Int,
    val hasBaseline: Boolean,
    val filePath: String,
)

