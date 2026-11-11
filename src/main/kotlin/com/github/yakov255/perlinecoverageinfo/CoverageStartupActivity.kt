package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.lang.management.ManagementFactory

/**
 * Triggers offline-first coverage loading after the IDE has fully started.
 * Implements [DumbAware] so it runs immediately on project open without
 * waiting for indexing to complete — coverage loading doesn't need indices.
 *
 * On first run (when no token is configured), shows a dialog prompting for the GitLab access
 * token. The dialog is posted to the EDT with `invokeLater` (nothing blocks), and an
 * application-wide guard shows it only once per IDE run even when several projects open together.
 *
 * **No coroutine suspension here, on purpose.** The IDE installs coroutine debug probes, and the
 * plugin is compiled with Kotlin 2.3 (debug metadata version 2) while the platform's stdlib
 * (2025.2) checks for version 1: resuming any suspending lambda of this plugin throws
 * `Debug metadata version mismatch` and the activity dies silently. Plain callbacks and pooled
 * threads are used instead; `NoCoroutineSuspensionTest` guards the rule.
 */
class CoverageStartupActivity : ProjectActivity, DumbAware {

    private val log = CoverageLog.get(CoverageStartupActivity::class.java)

    // A suspend signature is required by ProjectActivity, but the body never suspends.
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
            promptForTokenIfNeeded(project) { startLoad(project) }
        } catch (ex: Exception) {
            log.warn("Coverage: unexpected error in startup activity", ex)
        }
    }

    /**
     * Starts the first load on a pooled thread: git, the cache index and the COV4 reader are
     * blocking and must never run on the EDT.
     */
    private fun startLoad(project: Project) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            try {
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
                log.warn("Coverage: unexpected error in the first load", ex)
            }
        }
    }

    /**
     * Shows the first-run token dialog when the token is missing, then calls [onDone] (also when
     * no dialog is needed or another project already claimed it).
     *
     * [TokenPromptGuard] claims the prompt before it is shown, so a second project starting at the
     * same moment only logs and continues. The dialog runs on the EDT without blocking the caller.
     */
    internal fun promptForTokenIfNeeded(project: Project, onDone: () -> Unit = {}) {
        val settings = CoverageApiSettings.getInstance()
        if (settings.bearerToken.isNotBlank() || settings.tokenPrompted) {
            onDone()
            return
        }

        val app = ApplicationManager.getApplication()
        // A modal dialog cannot be shown in the IDE's unit-test mode; tests that exercise the
        // prompt install tokenPromptForTest, every other test must not be disturbed by it.
        if (app.isUnitTestMode && tokenPromptForTest == null) {
            onDone()
            return
        }

        if (!TokenPromptGuard.getInstance().tryClaim()) {
            log.info("Coverage: first-run token dialog already claimed by another project — not asking again")
            onDone()
            return
        }

        val show = {
            try {
                if (!project.isDisposed) applyPromptResult(settings, showTokenPrompt(project))
            } catch (ex: Exception) {
                // This runs on the EDT: a failing dialog must not surface as an IDE error.
                log.warn("Coverage: the first-run token dialog failed", ex)
            } finally {
                onDone()
            }
        }
        if (app.isDispatchThread) show() else app.invokeLater(show, ModalityState.nonModal())
    }

    private fun applyPromptResult(settings: CoverageApiSettings, token: String?) {
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
