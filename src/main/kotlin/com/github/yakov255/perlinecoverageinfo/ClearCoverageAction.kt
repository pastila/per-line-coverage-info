package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.EditorFactory

class ClearCoverageAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        CoverageDataService.getInstance(project).clear()

        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project == project) {
                CoverageHighlighter.clearCoverageHighlighters(editor)
            }
        }
    }
}
