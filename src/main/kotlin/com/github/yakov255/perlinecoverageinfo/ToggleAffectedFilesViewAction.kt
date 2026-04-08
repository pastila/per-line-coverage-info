package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * Toolbar toggle that switches the affected-files pane between tree and flat views.
 */
class ToggleAffectedFilesViewAction(
    private val isTreeView: () -> Boolean,
    private val toggle: (Boolean) -> Unit,
) : ToggleAction("Tree View", "Toggle between tree and flat file list", AllIcons.Actions.GroupByPackage) {

    override fun isSelected(e: AnActionEvent): Boolean = isTreeView()

    override fun setSelected(e: AnActionEvent, state: Boolean) = toggle(state)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
