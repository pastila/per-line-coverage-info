package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm

/**
 * Listens for new editors being opened and applies coverage highlights
 * if coverage data is loaded for that file.
 *
 * Also installs a DocumentListener on each editor to track changes
 * and update line mappings incrementally, so coverage stays aligned
 * with the current document and changed lines lose their coverage.
 */
class CoverageEditorListener : EditorFactoryListener {

    companion object {
        private val DOCUMENT_LISTENER_KEY = Key.create<CoverageDocumentListener>("COVERAGE_DOCUMENT_LISTENER")
        private const val REHIGHLIGHT_DELAY_MS = 300
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        CoverageHighlighter.applyToEditor(editor, project)
        installDocumentListener(editor, project)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val listener = editor.getUserData(DOCUMENT_LISTENER_KEY)
        if (listener != null) {
            editor.document.removeDocumentListener(listener)
            listener.dispose()
            editor.putUserData(DOCUMENT_LISTENER_KEY, null)
        }
    }

    private fun installDocumentListener(editor: Editor, project: Project) {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasData()) return

        // Only install if this file has coverage
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val lineMappingService = LineMappingService.getInstance(project)
        val relativePath = lineMappingService.toRelativePath(virtualFile.path) ?: return

        val listener = CoverageDocumentListener(editor, project, relativePath, lineMappingService)
        editor.document.addDocumentListener(listener)
        editor.putUserData(DOCUMENT_LISTENER_KEY, listener)
    }
}

/**
 * Tracks document changes and incrementally updates line mappings.
 * Uses a debounced alarm to avoid re-highlighting on every keystroke.
 */
private class CoverageDocumentListener(
    private val editor: Editor,
    private val project: Project,
    private val relativePath: String,
    private val lineMappingService: LineMappingService
) : DocumentListener {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)

    override fun documentChanged(event: DocumentEvent) {
        val document = event.document
        val oldFragment = event.oldFragment.toString()
        val newFragment = event.newFragment.toString()

        val oldLineCount = if (oldFragment.isEmpty()) 0 else oldFragment.count { it == '\n' }
        val newLineCount = if (newFragment.isEmpty()) 0 else newFragment.count { it == '\n' }

        val changeStartOffset = event.offset
        val changeStartLine = document.getLineNumber(changeStartOffset) // 0-based

        val currentMapping = lineMappingService.getMapping(relativePath)

        if (currentMapping != null) {
            // Get the content of the lines at the change site in the new document
            val linesAdded = newLineCount + 1 // number of lines affected in new content
            val linesRemoved = oldLineCount + 1 // number of lines affected in old content

            val changedLineContents = mutableListOf<String>()
            for (i in 0 until linesAdded) {
                val lineIdx = changeStartLine + i
                if (lineIdx < document.lineCount) {
                    val start = document.getLineStartOffset(lineIdx)
                    val end = document.getLineEndOffset(lineIdx)
                    changedLineContents.add(document.getText(com.intellij.openapi.util.TextRange(start, end)))
                }
            }

            val oldContentLines = lineMappingService.getOldContentLines(relativePath) ?: emptyList()

            val updatedMapping = CoverageLineMapper.updateMappingAfterChange(
                currentMapping,
                changeStartLine,
                linesRemoved,
                linesAdded,
                changedLineContents,
                oldContentLines
            )
            lineMappingService.setMapping(relativePath, updatedMapping)
        } else if (oldLineCount != newLineCount || oldFragment != newFragment) {
            // No mapping existed (file was unchanged), but now user is editing.
            // Force a full recomputation on next highlight.
            lineMappingService.invalidate(relativePath)
        }

        // Debounced re-highlight
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed && !editor.isDisposed) {
                CoverageHighlighter.applyToEditor(editor, project)
            }
        }, REHIGHLIGHT_DELAY_MS)
    }

    fun dispose() {
        alarm.cancelAllRequests()
    }

    companion object {
        private const val REHIGHLIGHT_DELAY_MS = 300
    }
}
