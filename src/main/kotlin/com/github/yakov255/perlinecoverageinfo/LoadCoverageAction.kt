package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class LoadCoverageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val loadService = CoverageLoadService.getInstance(project)
        // Manual trigger: use offline-first (show cached immediately, then fetch fresh)
        // but fall back to loadFromGitLab with errors if settings are invalid
        val validationError = loadService.validateSettings()
        if (validationError != null) {
            loadService.loadFromGitLab(showErrors = true)
            return
        }
        loadService.loadOfflineFirst()
    }
}
