package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.Alarm

/**
 * Listens for new editors being opened and applies coverage highlights.
 *
 * Also installs a DocumentListener on each editor that has coverage data.
 * On document changes, it debounces a re-highlight so that ComparisonManager
 * recomputes the old→new line mapping from cached old content vs current text.
 */
class CoverageEditorListener : EditorFactoryListener {

    companion object {
        private val COVERAGE_DOC_DISPOSABLE_KEY = Key.create<Disposable>("COVERAGE_DOC_DISPOSABLE")
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        CoverageHighlighter.applyToEditor(editor, project)
        installDocumentListener(editor, project)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val disposable = editor.getUserData(COVERAGE_DOC_DISPOSABLE_KEY)
        if (disposable != null) {
            Disposer.dispose(disposable)
            editor.putUserData(COVERAGE_DOC_DISPOSABLE_KEY, null)
        }
    }

    private fun installDocumentListener(editor: Editor, project: Project) {
        // Installed even without coverage: local runs can arrive while the editor is open,
        // and re-highlighting is a no-op until there is data.
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
        if (virtualFile == null) {
            return
        }
        val lineMappingService = LineMappingService.getInstance(project)
        lineMappingService.toRelativePath(virtualFile.path) ?: return

        val listener = CoverageDocumentListener(editor, project)
        val disposable = Disposer.newDisposable()
        editor.document.addDocumentListener(listener, disposable)
        Disposer.register(disposable, listener)
        editor.putUserData(COVERAGE_DOC_DISPOSABLE_KEY, disposable)
    }
}

/**
 * On document changes, debounces a re-highlight.
 * The line mapping is recomputed from scratch by ComparisonManager
 * (old content from git cache vs current document text) each time.
 */
private class CoverageDocumentListener(
    private val editor: Editor,
    private val project: Project
) : DocumentListener, Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)

    override fun documentChanged(event: DocumentEvent) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed && !editor.isDisposed) {
                CoverageHighlighter.applyToEditor(editor, project)
            }
        }, REHIGHLIGHT_DELAY_MS)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }

    companion object {
        private const val REHIGHLIGHT_DELAY_MS = 300
    }
}
