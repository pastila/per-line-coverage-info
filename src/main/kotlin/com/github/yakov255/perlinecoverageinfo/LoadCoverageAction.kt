package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.jetbrains.php.lang.PhpLanguage
import java.io.File

class LoadCoverageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (e.place == ActionPlaces.EDITOR_GUTTER_POPUP) {
            val project = e.project
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            val isPhp = psiFile?.language == PhpLanguage.INSTANCE
            val hasCoverage = project != null && CoverageDataService.getInstance(project).hasData()
            e.presentation.isEnabledAndVisible = isPhp && !hasCoverage
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val settings = CoverageApiSettings.getInstance()
        val apiUrl = settings.apiUrl
        val bearerToken = settings.bearerToken

        if (apiUrl.isBlank()) {
            Messages.showErrorDialog(project, "API URL is not configured. Please set it in Settings > Tools > Coverage API.", "Configuration Error")
            return
        }

        val basePath = project.basePath ?: return
        val projectVf = LocalFileSystem.getInstance().findFileByIoFile(File(basePath)) ?: return
        val relativePath = VfsUtil.getRelativePath(virtualFile, projectVf)
        if (relativePath == null) {
            Messages.showErrorDialog(project, "Could not determine relative path for the current file.", "Error")
            return
        }

        val apiClient = CoverageApiClient(apiUrl, bearerToken, project)
        val result = apiClient.fetchCoverageForFile(relativePath)

        if (result == null || result.coverageData.isEmpty()) {
            Messages.showErrorDialog(project, "Failed to fetch coverage data from API. Check your settings and API availability.", "API Error")
            return
        }

        val dataService = CoverageDataService.getInstance(project)
        dataService.setCoverageContext(result.commitHash, result.gitRoot)
        dataService.setCoverage(relativePath, result.coverageData[relativePath] ?: return)

        // Apply annotations to all currently open editors
        CoverageHighlighter.applyToOpenEditors(project)
    }
}
