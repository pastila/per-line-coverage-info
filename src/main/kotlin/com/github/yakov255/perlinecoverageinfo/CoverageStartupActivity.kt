package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Triggers offline-first coverage loading after the IDE has fully started.
 * Implements [DumbAware] so it runs immediately on project open without
 * waiting for indexing to complete — coverage loading doesn't need indices.
 *
 * On first run (when no token is configured), shows a dialog prompting for
 * GitLab domain and access token.
 */
class CoverageStartupActivity : ProjectActivity, DumbAware {

    private val log = CoverageLog.get(CoverageStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        try {
            promptForTokenIfNeeded(project)

            // Local runs don't depend on GitLab settings or gutter visibility.
            LocalCoverageService.getInstance(project).startWatching()

            val loadService = CoverageLoadService.getInstance(project)

            val settingsError = loadService.validateSettings()
            if (settingsError != null) {
                log.info("Coverage auto-load skipped: $settingsError")
            } else if (!CoverageGutterVisibilityService.getInstance(project).visible) {
                log.info("Coverage auto-load skipped: gutter visibility is off")
            } else {
                loadService.loadOfflineFirst()
            }
        } catch (ex: Exception) {
            log.warn("Coverage: unexpected error in startup activity", ex)
        }
    }

    private fun promptForTokenIfNeeded(project: Project) {
        val settings = CoverageApiSettings.getInstance()
        if (settings.bearerToken.isBlank() && !settings.tokenPrompted) {
            var token: String? = null
            ApplicationManager.getApplication().invokeAndWait {
                val dialog = GitLabTokenDialog(project)
                if (dialog.showAndGet()) {
                    token = dialog.token
                }
            }
            if (token != null) {
                settings.bearerToken = token
                settings.enabled = true
                log.info("Coverage: GitLab token configured via first-run dialog")
            } else {
                log.info("Coverage: first-run token dialog was cancelled")
            }
            settings.tokenPrompted = true
        }
    }
}
