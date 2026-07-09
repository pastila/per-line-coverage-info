package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

class ShowCoverageGutterAction : AnAction(), DumbAware {

    private val log = CoverageLog.get(ShowCoverageGutterAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        log.info("ShowCoverageGutterAction: showing coverage gutter, resuming activity")
        CoverageGutterVisibilityService.getInstance(project).visible = true
        CoverageHighlighter.applyToOpenEditors(project)
        val loadService = CoverageLoadService.getInstance(project)
        if (loadService.validateSettings() == null) {
            loadService.loadOfflineFirst()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Show when the gutter is hidden, regardless of whether coverage data is
        // currently loaded: loading is suppressed while hidden, so hasData() may
        // be false even though the user just wants to turn the feature back on.
        e.presentation.isEnabledAndVisible = project != null &&
            !CoverageGutterVisibilityService.getInstance(project).visible
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
