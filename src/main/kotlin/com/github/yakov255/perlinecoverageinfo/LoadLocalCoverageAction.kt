package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAware
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Action to load coverage from a local .covt or .covt.gz file.
 * Useful for locally generated coverage data (no GitLab needed).
 */
class LoadLocalCoverageAction : AnAction(), DumbAware {

    private val log = CoverageLog.get(LoadLocalCoverageAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Select Coverage File")
            .withDescription("Choose a .covt or .covt.gz binary coverage file")
            .withFileFilter { vf ->
                vf.name.endsWith(".covt") || vf.name.endsWith(".covt.gz")
            }

        val virtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return
        val file = File(virtualFile.path)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val bytes = if (file.name.endsWith(".covt.gz")) {
                    GZIPInputStream(file.inputStream()).use { it.readBytes() }
                } else {
                    file.readBytes()
                }

                val coverage = BinaryCoverageParser.parseCovtBytes(bytes)
                log.info("Coverage: parsed ${coverage.size} files from local file ${file.name}")

                val gitRoot = findGitRoot(file.parentFile)
                val dataService = CoverageDataService.getInstance(project)
                if (gitRoot != null) {
                    dataService.setCoverageContext("local", gitRoot)
                }
                dataService.setCoverageAll(coverage)

                ApplicationManager.getApplication().invokeLater {
                    CoverageHighlighter.applyToOpenEditors(project)
                }
            } catch (ex: Exception) {
                log.warn("Failed to load local coverage file", ex)
                ApplicationManager.getApplication().invokeLater {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        "Failed to parse coverage file:\n${ex.message}",
                        "Coverage Error"
                    )
                }
            }
        }
    }

    private fun findGitRoot(dir: File?): File? {
        var current = dir
        while (current != null) {
            if (File(current, ".git").exists()) return current
            current = current.parentFile
        }
        return null
    }
}
