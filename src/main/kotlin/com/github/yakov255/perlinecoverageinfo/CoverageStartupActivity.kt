package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.management.ManagementFactory

/**
 * Triggers offline-first coverage loading after the IDE has fully started.
 * Implements [DumbAware] so it runs immediately on project open without
 * waiting for indexing to complete — coverage loading doesn't need indices.
 *
 * On first run (when no token is configured), shows a dialog prompting for
 * GitLab domain and access token. The dialog runs on the EDT through
 * [withContext], so it does not block the coroutine thread, and an application-wide guard
 * shows it only once per IDE run even when several projects open together.
 */
class CoverageStartupActivity : ProjectActivity, DumbAware {

    private val log = CoverageLog.get(CoverageStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        try {
            CoveragePerfLog.event(
                "plugin.start",
                "project" to project.name,
                "jvm" to System.getProperty("java.version"),
                "xmxMB" to (Runtime.getRuntime().maxMemory() / 1_048_576),
                "jvmArgs" to ManagementFactory.getRuntimeMXBean().inputArguments
                    .filter { it.startsWith("-Xm") || it.startsWith("-XX:") }
                    .joinToString(","),
            )
            promptForTokenIfNeeded(project)

            val loadService = CoverageLoadService.getInstance(project)

            val settingsError = loadService.validateSettings()
            if (settingsError != null) {
                log.info("Coverage auto-load skipped: $settingsError")
            } else if (!CoverageGutterVisibilityService.getInstance(project).visible) {
                log.info("Coverage auto-load skipped: gutter visibility is off")
            } else {
                // git, the cache index and the COV4 reader are blocking: never on the EDT.
                withContext(Dispatchers.IO) { loadService.loadOfflineFirst() }
            }
        } catch (ex: Exception) {
            log.warn("Coverage: unexpected error in startup activity", ex)
        }
    }

    /**
     * Shows the first-run token dialog when the token is missing.
     *
     * The dialog is dispatched to the EDT instead of blocking the coroutine thread with
     * `invokeAndWait`. [TokenPromptGuard] claims the prompt before it is shown, so a second
     * project starting at the same moment only logs and continues.
     */
    internal suspend fun promptForTokenIfNeeded(project: Project) {
        val settings = CoverageApiSettings.getInstance()
        if (settings.bearerToken.isNotBlank() || settings.tokenPrompted) return

        if (!TokenPromptGuard.getInstance().tryClaim()) {
            log.info("Coverage: first-run token dialog already claimed by another project — not asking again")
            return
        }

        val token = if (ApplicationManager.getApplication().isDispatchThread) {
            showTokenPrompt(project)
        } else {
            // Dispatching to the EDT suspends the coroutine instead of blocking its thread.
            withContext(Dispatchers.EDT) { showTokenPrompt(project) }
        }

        settings.tokenPrompted = true
        if (token.isNullOrBlank()) {
            log.info("Coverage: first-run token dialog was cancelled or empty")
            return
        }
        settings.bearerToken = token
        settings.enabled = true
        log.info("Coverage: GitLab token configured via first-run dialog")
    }

    /** Shows the modal dialog on the EDT; [tokenPromptForTest] replaces it in a test. */
    private fun showTokenPrompt(project: Project): String? {
        val testPrompt = tokenPromptForTest
        if (testPrompt != null) return testPrompt(project)
        val dialog = GitLabTokenDialog(project)
        return if (dialog.showAndGet()) dialog.token else null
    }

    companion object {
        /**
         * Test seam: replaces the modal dialog. It runs on the EDT and returns the token the user
         * would have typed, or null for "cancelled". Production code never sets it.
         */
        @Volatile
        internal var tokenPromptForTest: ((Project) -> String?)? = null
        /** Test seam: restores the one-shot dialog guard and the dialog replacement. */
        internal fun resetForTest() {
            tokenPromptForTest = null
            TokenPromptGuard.getInstance().resetForTest()
        }
    }
}
