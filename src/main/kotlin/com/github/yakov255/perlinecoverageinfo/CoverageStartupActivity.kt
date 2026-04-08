package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Triggers offline-first coverage loading after the IDE has fully started.
 * Runs in the background so it has no impact on IDE startup time.
 */
class CoverageStartupActivity : ProjectActivity {

    private val log = CoverageLog.get(CoverageStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        val loadService = CoverageLoadService.getInstance(project)
        if (loadService.validateSettings() != null) {
            log.info("Coverage: settings not configured, skipping auto-load on startup")
            return
        }

        log.info("Coverage: starting auto-load on IDE startup")
        loadService.loadOfflineFirst()
    }
}
