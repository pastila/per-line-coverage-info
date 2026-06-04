package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware

class LoadCoverageAction : AnAction(), DumbAware {

    private val log = CoverageLog.get(LoadCoverageAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        log.info("LoadCoverageAction: manual load triggered")
        val loadService = CoverageLoadService.getInstance(project)
        // Manual trigger: use offline-first (show cached immediately, then fetch fresh)
        // but fall back to loadFromGitLab with errors if settings are invalid
        val validationError = loadService.validateSettings()
        if (validationError != null) {
            log.warn("LoadCoverageAction: invalid settings: $validationError")
            loadService.loadFromGitLab(showErrors = true)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            loadService.loadOfflineFirst()
        }
    }
}
