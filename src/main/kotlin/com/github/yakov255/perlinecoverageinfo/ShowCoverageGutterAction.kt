package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class ShowCoverageGutterAction : AnAction(), DumbAware {

    private val log = CoverageLog.get(ShowCoverageGutterAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        log.info("ShowCoverageGutterAction: showing coverage gutter")
        CoverageGutterVisibilityService.getInstance(project).visible = true
        CoverageHighlighter.applyToOpenEditors(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Show only when coverage data exists and highlights are currently hidden
        e.presentation.isEnabledAndVisible = project != null &&
            CoverageDataService.getInstance(project).hasData() &&
            !CoverageGutterVisibilityService.getInstance(project).visible
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
