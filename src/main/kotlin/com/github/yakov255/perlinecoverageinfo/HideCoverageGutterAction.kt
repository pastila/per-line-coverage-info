package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class HideCoverageGutterAction : AnAction(), DumbAware {

    private val log = CoverageLog.get(HideCoverageGutterAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        log.info("HideCoverageGutterAction: hiding coverage gutter, stopping all activity")
        CoverageGutterVisibilityService.getInstance(project).visible = false
        CoveragePipelinePoller.getInstance(project).stop()
        CoverageHighlighter.clearAllEditors(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            CoverageDataService.getInstance(project).hasData() &&
            CoverageGutterVisibilityService.getInstance(project).visible
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
