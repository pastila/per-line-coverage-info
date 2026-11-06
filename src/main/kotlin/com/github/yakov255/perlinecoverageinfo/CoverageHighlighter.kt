package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.io.File
import java.nio.file.Paths

object CoverageHighlighter {

    private val log = CoverageLog.get(CoverageHighlighter::class.java)
    private val TASK_KEY = Key.create<EditorTask>("PER_LINE_COVERAGE_TASK")
    private const val COVERAGE_LAYER = HighlighterLayer.LAST + 1
    val COVERAGE_HIGHLIGHTER_KEY = Key.create<Boolean>("PER_LINE_COVERAGE_HIGHLIGHTER")

    fun applyToOpenEditors(project: Project) {
        if (!hasAnyData(project)) {
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
        if (editor.isDisposed || project.isDisposed) return
        val data = CoverageDataService.getInstance(project)
        val local = LocalCoverageService.getInstance(project)
        if ((!data.hasData() && !local.hasActiveData()) || !CoverageGutterVisibilityService.getInstance(project).visible) {
            clearCoverageHighlighters(editor)
            return
        }
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val task = synchronized(editor) {
            editor.getUserData(TASK_KEY) ?: EditorTask(editor, project).also {
                editor.putUserData(TASK_KEY, it)
                if (!Disposer.tryRegister(CoveragePluginDisposable.getInstance(project), it)) it.dispose()
            }
        }
        val snapshot = ReadAction.compute<HighlightSnapshot, RuntimeException> {
            HighlightSnapshot(editor.document.text, editor.document.modificationStamp, data.hasBaseline(), file.path,
                data.coverageGeneration, local.coverageGeneration)
        }
        task.submit(snapshot)
    }

    fun cancelPending(editor: Editor) { editor.getUserData(TASK_KEY)?.cancel() }

    fun editorReleased(editor: Editor) {
        editor.getUserData(TASK_KEY)?.let { Disposer.dispose(it) }
    }

    private class EditorTask(private val editor: Editor, private val project: Project) : Disposable {
        private data class Result(
            val snapshot: HighlightSnapshot, val ticket: LatestTaskQueue.Ticket,
            val lines: Map<Int, List<String>>, val baseline: Map<Int, List<String>>?, val localTests: Set<String>,
        )
        private var result: Result? = null
        private var applyQueued = false
        private var disposed = false
        private val queue = LatestTaskQueue<HighlightSnapshot>(
            java.util.concurrent.Executor { ApplicationManager.getApplication().executeOnPooledThread(it) },
            { log.warn("Coverage: highlight task failed", it) },
        ) { snapshot, ticket ->
            val indicator = ProgressIndicatorBase()
            ticket.onCancel(indicator::cancel)
            try {
                ProgressManager.getInstance().runProcess(Runnable {
                    CoverageCancellation.withIndicator(indicator) { compute(snapshot, ticket) }
                }, indicator)
            } catch (_: ProcessCanceledException) {
                // Cooperative cancellation keeps shared file channels intact.
            }
        }

        @Synchronized fun submit(snapshot: HighlightSnapshot) {
            if (disposed) return
            result = null
            queue.submit(snapshot)
        }

        @Synchronized fun cancel() {
            result = null
            queue.cancel()
        }

        @Synchronized override fun dispose() {
            if (disposed) return
            disposed = true
            result = null
            queue.close()
            editor.putUserData(TASK_KEY, null)
        }

        private fun compute(snapshot: HighlightSnapshot, ticket: LatestTaskQueue.Ticket) {
            CoverageCancellation.check()
            if (project.isDisposed || editor.isDisposed) return
            val data = CoverageDataService.getInstance(project)
            val local = LocalCoverageService.getInstance(project)
            val mapper = LineMappingService.getInstance(project)
            val ci = if (data.hasData()) mapper.getMappedCoverage(snapshot.filePath, snapshot.text)
                ?: findCoverageForFile(snapshot.filePath, project) else null
            CoverageCancellation.check()
            val tests = local.tests()
            val localLines = local.getMappedCoverage(snapshot.filePath, snapshot.text)
            val lines = LocalCoverageMerge.merge(ci, localLines, tests) ?: return
            CoverageCancellation.check()
            val baseline = if (snapshot.hasBaseline) mapper.getMappedBaselineCoverage(snapshot.filePath, snapshot.text)
                ?: findBaselineCoverageForFile(snapshot.filePath, project) else null
            CoverageCancellation.check()
            synchronized(this) {
                if (disposed || ticket.cancelled) return
                result = Result(snapshot, ticket, lines, baseline, tests)
                if (applyQueued) return
                applyQueued = true
            }
            // The queued closure holds only the task; replaced results release their maps immediately.
            ApplicationManager.getApplication().invokeLater { applyResult() }
        }

        @Synchronized private fun applyResult() {
            applyQueued = false
            val ready = result.also { result = null } ?: return
            if (disposed || ready.ticket.cancelled || project.isDisposed || editor.isDisposed) return
            val snapshot = ready.snapshot
            if (editor.document.modificationStamp != snapshot.modificationStamp ||
                CoverageDataService.getInstance(project).coverageGeneration != snapshot.ciGeneration ||
                LocalCoverageService.getInstance(project).coverageGeneration != snapshot.localGeneration ||
                !CoverageGutterVisibilityService.getInstance(project).visible) return
            removeCoverageHighlighters(editor)
            val renderer = FileCoverageRenderer(snapshot.filePath, project, ready.lines, ready.baseline,
                snapshot.hasBaseline, CoverageWarningService.getInstance(project).getWarnings(), ready.localTests)
            val highlighter = editor.markupModel.addRangeHighlighter(
                0, editor.document.textLength, COVERAGE_LAYER, TextAttributes(null, null, null, null, 0),
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            highlighter.lineMarkerRenderer = renderer
            highlighter.putUserData(COVERAGE_HIGHLIGHTER_KEY, true)
        }
    }

    /**
     * Pure helper exposed for tests: classify a line given its primary and baseline test lists.
     * - Empty primary → UNCOVERED (regardless of baseline; we trust primary as the source of truth
     *   for what tests currently exercise the line).
     * - Covered by at least one test from a local run → LOCAL.
     * - Has baseline + master has no coverage on this line, but branch does → FEATURE_ONLY.
     * - Otherwise → COVERED.
     */
    @JvmStatic
    internal fun categorizeLine(
        primary: List<String>,
        baseline: List<String>,
        hasBaseline: Boolean,
    ): CoverageCategory = categorizeLine(primary, baseline, hasBaseline, emptySet())

    @JvmStatic
    internal fun categorizeLine(
        primary: List<String>,
        baseline: List<String>,
        hasBaseline: Boolean,
        localTests: Set<String>,
    ): CoverageCategory {
        if (primary.isEmpty()) return CoverageCategory.UNCOVERED
        if (localTests.isNotEmpty() && primary.any { it in localTests }) return CoverageCategory.LOCAL
        if (!hasBaseline) return CoverageCategory.COVERED
        return if (baseline.isEmpty()) CoverageCategory.FEATURE_ONLY else CoverageCategory.COVERED
    }

    /** Whether there is anything to render — CI coverage, non-stale local runs, or both. */
    fun hasAnyData(project: Project): Boolean =
        CoverageDataService.getInstance(project).hasData() || LocalCoverageService.getInstance(project).hasActiveData()

    fun clearCoverageHighlighters(editor: Editor) {
        cancelPending(editor)
        removeCoverageHighlighters(editor)
    }

    private fun removeCoverageHighlighters(editor: Editor) {
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
    val modificationStamp: Long,
    val hasBaseline: Boolean,
    val filePath: String,
    val ciGeneration: Long,
    val localGeneration: Long,
)

