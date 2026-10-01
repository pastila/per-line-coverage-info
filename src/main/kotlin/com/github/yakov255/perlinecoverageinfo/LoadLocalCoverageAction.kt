package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.DumbAware
import java.io.File

/**
 * Action to load coverage from a local .covt or .covt.gz file.
 * The run is merged over CI coverage (see [LocalCoverageService]); no GitLab needed.
 */
class LoadLocalCoverageAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("Select Coverage File")
            .withDescription("Choose a .covt or .covt.gz binary coverage file")
            .withFileFilter { vf ->
                vf.name.endsWith(".covt") || vf.name.endsWith(".covt.gz")
            }

        val virtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return
        LocalCoverageService.getInstance(project).loadInBackground(File(virtualFile.path))
    }
}
