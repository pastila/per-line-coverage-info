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
        try {
            val loadService = CoverageLoadService.getInstance(project)

            val settingsError = loadService.validateSettings()
            if (settingsError != null) {
                log.info("Coverage auto-load skipped: $settingsError")
                return
            }

            loadService.loadOfflineFirst()
        } catch (ex: Exception) {
            log.warn("Coverage: unexpected error in startup activity", ex)
        }
    }
}
