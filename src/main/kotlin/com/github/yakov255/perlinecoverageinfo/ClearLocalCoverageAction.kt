package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/** Drops local runs and falls back to CI coverage only. */
class ClearLocalCoverageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && LocalCoverageService.getInstance(project).hasData()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LocalCoverageService.getInstance(project).clear()
    }
}
