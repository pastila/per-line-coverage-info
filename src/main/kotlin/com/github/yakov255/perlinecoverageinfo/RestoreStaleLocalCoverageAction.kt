package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * Brings back local runs that were collected on another commit.
 *
 * After a checkout such runs stop being merged (see [LocalCoverageService.onHeadChanged]); this
 * action is the way back for the case where the checkout did not really change the covered code.
 * Visible only while there is something to restore.
 */
class RestoreStaleLocalCoverageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val stale = if (project == null) 0 else LocalCoverageService.getInstance(project).staleRunCount()
        e.presentation.isEnabledAndVisible = stale > 0
        if (stale > 0) {
            e.presentation.text = "Restore Stale Local Coverage ($stale)"
            e.presentation.description =
                "$stale local run(s) were collected on another commit and are not merged. " +
                    "Restore them onto the current HEAD."
            e.presentation.icon = AllIcons.General.Warning
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        LocalCoverageService.getInstance(project).restoreStale()
    }
}
