package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.jetbrains.php.lang.PhpLanguage
import java.io.File

class LoadCoverageAction : AnAction() {

    private val log = Logger.getInstance(LoadCoverageAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        if (e.place == ActionPlaces.EDITOR_GUTTER_POPUP) {
            val project = e.project ?: run { e.presentation.isEnabledAndVisible = false; return }
            val psiFile = e.getData(CommonDataKeys.PSI_FILE)
            val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            val isPhp = psiFile?.language == PhpLanguage.INSTANCE

            val basePath = project.basePath
            val projectVf = if (basePath != null) LocalFileSystem.getInstance().findFileByIoFile(File(basePath)) else null
            val relativePath = if (virtualFile != null && projectVf != null) VfsUtil.getRelativePath(virtualFile, projectVf) else null
            val hasCoverageForFile = relativePath != null && CoverageDataService.getInstance(project).getCoverage(relativePath) != null

            e.presentation.isEnabledAndVisible = isPhp && !hasCoverageForFile
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
        val result = try {
            apiClient.fetchCoverageForFile(relativePath)
        } catch (ex: CoverageApiException) {
            log.warn("Coverage error", ex)
            val title = when (ex.kind) {
                CoverageErrorKind.NETWORK       -> "API Connection Error"
                CoverageErrorKind.API_RESPONSE  -> "API Error"
                CoverageErrorKind.PARSE         -> "API Response Error"
                CoverageErrorKind.GIT           -> "Git Error"
                CoverageErrorKind.NO_DATA       -> "No Coverage Data"
                CoverageErrorKind.PROJECT_SETUP -> "Project Configuration Error"
            }
            Messages.showErrorDialog(project, ex.userMessage, title)
            return
        } catch (ex: Exception) {
            log.warn("Unexpected coverage error", ex)
            Messages.showErrorDialog(
                project,
                "An unexpected error occurred while loading coverage data:\n${ex.message}",
                "Coverage Error"
            )
            return
        }

        if (result.coverageData.isEmpty()) {
            Messages.showInfoMessage(project, "No coverage data found for this file at the current commit.", "No Coverage Data")
            return
        }

        val dataService = CoverageDataService.getInstance(project)
        dataService.setCoverageContext(result.commitHash, result.gitRoot)
        dataService.setCoverage(relativePath, result.coverageData[relativePath] ?: return)

        // Apply annotations to all currently open editors
        CoverageHighlighter.applyToOpenEditors(project)
    }
}
