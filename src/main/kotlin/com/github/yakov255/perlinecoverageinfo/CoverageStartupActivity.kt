package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Triggers offline-first coverage loading after the IDE has fully started.
 * Implements [DumbAware] so it runs immediately on project open without
 * waiting for indexing to complete — coverage loading doesn't need indices.
 */
class CoverageStartupActivity : ProjectActivity, DumbAware {

    private val log = CoverageLog.get(CoverageStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        log.warn("[STARTUP] CoverageStartupActivity.execute() called, project=${project.name}")
        try {
            val loadService = CoverageLoadService.getInstance(project)

            // One-time: auto-disable plugin if the git remote doesn't match the required repo.
            loadService.performRemoteUrlAutoCheck()

            val settingsError = loadService.validateSettings()
            if (settingsError != null) {
                log.warn("[STARTUP] Settings not configured, skipping auto-load: $settingsError")
                return
            }

            val settings = CoverageApiSettings.getInstance()
            log.warn("[STARTUP] Settings OK — domain=${settings.gitlabDomain}, projectId=${settings.gitlabProjectId}, projectName=${settings.gitlabProjectName}, branch=${settings.coverageBranch}, tokenBlank=${settings.bearerToken.isBlank()}")

            log.warn("[STARTUP] Calling loadOfflineFirst()")
            loadService.loadOfflineFirst()
            log.warn("[STARTUP] loadOfflineFirst() returned (background tasks may still be running)")
        } catch (ex: Exception) {
            log.warn("[STARTUP] Unexpected exception in CoverageStartupActivity", ex)
        }
    }
}
