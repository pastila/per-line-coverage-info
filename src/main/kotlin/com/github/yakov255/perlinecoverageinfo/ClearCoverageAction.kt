package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.DumbAware

class ClearCoverageAction : AnAction(), DumbAware {

    private val log = CoverageLog.get(ClearCoverageAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        log.info("ClearCoverageAction: clearing coverage for project ${project.name}")
        CoverageDataService.getInstance(project).clear()

        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project == project) {
                CoverageHighlighter.clearCoverageHighlighters(editor)
            }
        }
    }
}
