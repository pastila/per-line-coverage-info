package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class LoadCoverageAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val settings = CoverageApiSettings.getInstance()
        val apiUrl = settings.apiUrl
        val bearerToken = settings.bearerToken

        if (apiUrl.isBlank()) {
            Messages.showErrorDialog(project, "API URL is not configured. Please set it in Settings > Tools > Coverage API.", "Configuration Error")
            return
        }

        val apiClient = CoverageApiClient(apiUrl, bearerToken, project)
        val coverageMap = apiClient.fetchCoverage()

        if (coverageMap == null || coverageMap.isEmpty()) {
            Messages.showErrorDialog(project, "Failed to fetch coverage data from API. Check your settings and API availability.", "API Error")
            return
        }

        val dataService = CoverageDataService.getInstance(project)
        dataService.clear()

        for ((filePath, lines) in coverageMap) {
            dataService.setCoverage(filePath, lines)
        }

        // Apply annotations to all currently open editors
        CoverageHighlighter.applyToOpenEditors(project)

        Messages.showInfoMessage(
            project,
            "Coverage loaded: ${coverageMap.size} files.",
            "Coverage Loaded"
        )
    }
}
