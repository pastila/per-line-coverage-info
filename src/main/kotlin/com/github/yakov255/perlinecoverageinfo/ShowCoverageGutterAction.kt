package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class ShowCoverageGutterAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        CoverageHighlighter.applyToOpenEditors(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Show only when coverage data exists but highlights are currently hidden
        e.presentation.isEnabledAndVisible = project != null &&
            CoverageDataService.getInstance(project).hasData()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
