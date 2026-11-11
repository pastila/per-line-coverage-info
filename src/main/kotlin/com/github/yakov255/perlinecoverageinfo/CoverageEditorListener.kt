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
 * Installs a DocumentListener on every file-backed editor, also before coverage is loaded: the
 * coverage check is cheap and happens per change, so an editor restored before the first load
 * still re-highlights once the data arrives and the user edits it. One listener per editor, and
 * [editorReleased] disposes it.
 */
class CoverageEditorListener : EditorFactoryListener {

    companion object {
        private val COVERAGE_DOC_DISPOSABLE_KEY = Key.create<Disposable>("COVERAGE_DOC_DISPOSABLE")

        /** Test seam: true when [editor] already has the coverage document listener. */
        internal fun isListening(editor: Editor): Boolean =
            editor.getUserData(COVERAGE_DOC_DISPOSABLE_KEY) != null
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        CoverageHighlighter.applyToEditor(editor, project)
        installDocumentListener(editor, project)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val disposable = editor.getUserData(COVERAGE_DOC_DISPOSABLE_KEY) ?: return
        editor.putUserData(COVERAGE_DOC_DISPOSABLE_KEY, null)
        Disposer.dispose(disposable)
    }

    /**
     * Installs the re-highlight listener unless this editor already has one, so a second
     * [editorCreated] for the same editor cannot subscribe twice.
     */
    private fun installDocumentListener(editor: Editor, project: Project) {
        if (editor.getUserData(COVERAGE_DOC_DISPOSABLE_KEY) != null) return
        if (FileDocumentManager.getInstance().getFile(editor.document) == null) return

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
        if (project.isDisposed) return
        // No coverage yet: the edit only marks the document for the next change.
        if (!CoverageDataService.getInstance(project).hasData()) return

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
