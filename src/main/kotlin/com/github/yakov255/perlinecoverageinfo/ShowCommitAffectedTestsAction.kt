package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.vcs.log.VcsLogDataKeys
import java.io.File

/** Loaded only with Git4Idea; works for one commit and arbitrary multi-selection. */
class ShowCommitAffectedTestsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selected = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits.orEmpty()
        e.presentation.isEnabledAndVisible = e.project != null && selected.isNotEmpty() &&
            selected.map { it.root }.distinct().size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = e.getData(VcsLogDataKeys.VCS_LOG_COMMIT_SELECTION)?.commits?.toList().orEmpty()
        val root = selected.map { it.root }.distinct().singleOrNull() ?: return
        val commits = selected.map { it.hash.asString() }
        val lifetime = CoveragePluginDisposable.getInstance(project)
        val window = ToolWindowManager.getInstance(project).getToolWindow("Coverage Tests") ?: return
        window.activate {
            if (project.isDisposed || lifetime.isDisposed) return@activate
            val content = window.contentManager.contents.firstOrNull { it.component is AffectedTestsPanel } ?: return@activate
            window.contentManager.setSelectedContent(content)
            (content.component as AffectedTestsPanel).showCommits(File(root.path), commits)
        }
    }
}
