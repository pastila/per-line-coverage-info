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
        val settings = CoverageApiSettings.getInstance()
        if (!settings.enabled) {
            log.info("CoverageSettingsActions: plugin not enabled, skipping auto-load")
            return
        }
        if (settings.gitlabDomain.isBlank() || settings.bearerToken.isBlank() || settings.gitlabProjectId <= 0) {
            log.info("CoverageSettingsActions: settings not configured, skipping auto-load")
            return
        }

        invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                log.info("CoverageSettingsActions: reloading project ${project.name}")
                ApplicationManager.getApplication().executeOnPooledThread {
                    val loadService = CoverageLoadService.getInstance(project)
                    loadService.onSettingsApplied()
                    loadService.loadOfflineFirst()
                }
            }
        }
    }
}
