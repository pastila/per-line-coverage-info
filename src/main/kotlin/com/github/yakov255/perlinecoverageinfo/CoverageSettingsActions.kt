package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.ProjectManager

/**
 * The shared "settings were applied" path.
 *
 * Settings → Tools → GitLab Coverage and the UI-test bridge command `settings.apply` both end
 * here: every open project clears its failure block and retry counter, then loads again. This
 * is what makes a new token work without an IDE restart.
 */
internal object CoverageSettingsActions {

    private val log = CoverageLog.get(CoverageSettingsActions::class.java)

    fun afterSettingsApplied() {
        invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                log.info("CoverageSettingsActions: reloading project ${project.name}")
                ApplicationManager.getApplication().executeOnPooledThread {
                    if (project.isDisposed) return@executeOnPooledThread
                    LocalCoverageService.getInstance(project).restartWatching()
                    val loadService = CoverageLoadService.getInstance(project)
                    loadService.onSettingsApplied()
                    val error = loadService.validateSettings()
                    if (error == null) {
                        loadService.loadOfflineFirst()
                    } else {
                        log.info("CoverageSettingsActions: auto-load skipped: $error")
                    }
                }
            }
        }
    }
}
