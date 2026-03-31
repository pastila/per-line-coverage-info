package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

/**
 * Listens for new editors being opened and applies coverage highlights
 * if coverage data is loaded for that file.
 */
class CoverageEditorListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return
        CoverageHighlighter.applyToEditor(editor, project)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        // Highlighters are cleaned up automatically when the editor is disposed
    }
}
