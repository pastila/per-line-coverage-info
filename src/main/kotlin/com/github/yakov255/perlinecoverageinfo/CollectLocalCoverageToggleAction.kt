package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware

/** Toggles [CoverageApiSettings.collectLocalCoverage] — see [LocalCoverageRunExtension]. */
class CollectLocalCoverageToggleAction : ToggleAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = CoverageApiSettings.getInstance().collectLocalCoverage

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        CoverageApiSettings.getInstance().collectLocalCoverage = state
    }
}
