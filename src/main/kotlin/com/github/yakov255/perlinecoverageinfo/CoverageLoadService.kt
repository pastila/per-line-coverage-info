package com.github.yakov255.perlinecoverageinfo

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.util.Alarm
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Project-level entry point for coverage loading.
 *
 * All pipeline work happens in the covd daemon: this service validates the settings, sends
 * one `ensure`, attaches the returned `.cov4` files to [CoverageDataService] and reports
 * `not_ready`/`error` answers. Cached artifacts are shown immediately on startup, before the
 * daemon answers.
 */
@Service(Service.Level.PROJECT)
class CoverageLoadService(private val project: Project) {

    private val log = CoverageLog.get(CoverageLoadService::class.java)

    @Volatile
    private var loading = false

    @Volatile
    private var pendingReload = false

    /** Last user-facing load error, or null when the last attempt succeeded. Read by [CoverageUiTestBridge]. */
    @Volatile
    var lastError: String? = null
        private set

    /** Retry timer for `not_ready` answers from the covd daemon. */
    private val covdRetryAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    fun isLoadInProgress(): Boolean = loading

    /** Validates that the plugin is enabled and GitLab settings are configured. */
    fun validateSettings(): String? {
        val settings = CoverageApiSettings.getInstance()
        if (!settings.enabled) return "Coverage plugin is disabled. Enable it in Settings > Tools > GitLab Coverage."
        return when {
            settings.gitlabDomain.isBlank() -> "GitLab domain is not configured.\nPlease set it in Settings > Tools > GitLab Coverage."
            settings.bearerToken.isBlank() -> "GitLab access token is not configured.\nPlease set it in Settings > Tools > GitLab Coverage."
            settings.gitlabProjectId <= 0 -> "GitLab project is not selected.\nPlease select a project in Settings > Tools > GitLab Coverage."
            else -> null
        }
    }

    /**
     * Offline-first: show whatever the daemon already cached, then ask it for fresh coverage.
     * Repeated on every HEAD change and at startup.
     */
    fun loadOfflineFirst() {
        CoveragePerfLog.span("load.offlineFirst") { loadOfflineFirstInner() }
    }

    private fun loadOfflineFirstInner() {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: offline-first load skipped — gutter visibility is off")
            return
        }
        if (loading) {
            pendingReload = true
            log.info("Coverage: load already in progress, will reload after completion")
            return
        }

        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)
        val selectionService = CoverageUserSelectionService.getInstance(project)

        val pinned = selectionService.pinnedCommitHash
        if (pinned != null) {
            val root = findGitRoot()
            val component = if (root != null) detectComponent(root) ?: "raketa" else "raketa"
            val reader = cache.get(pinned, component, root)
            if (reader != null) {
                val alreadyLoaded = dataService.coverageCommitHash == pinned && dataService.hasData()
                log.info("Coverage: offline-first pinned — commit ${pinned.take(8)}, alreadyLoaded=$alreadyLoaded")
                if (!alreadyLoaded) {
                    if (root != null) {
                        dataService.setCoverageContext(pinned, root, stale = false)
                        computeAndStoreWarningContext(root)
                    }
                    dataService.setCov4Reader(reader)
                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                    }
                }
                // The user pinned this artifact deliberately: do not refresh it.
                return
            }
            log.info("Coverage: pinned commit ${pinned.take(8)} not found in cache, clearing pin")
            selectionService.pinnedCommitHash = null
        }

        val gitRoot = findGitRoot()
        if (gitRoot != null) {
            val component = detectComponent(gitRoot)
            if (component != null) {
                val cachedCommit = cache.findCachedCommit(getRecentCommits(gitRoot, 200))
                if (cachedCommit != null) {
                    val reader = cache.get(cachedCommit, component, gitRoot)
                    if (reader != null) {
                        val alreadyLoaded = dataService.coverageCommitHash == cachedCommit && dataService.hasData()
                        log.info("Coverage: offline-first hit — commit ${cachedCommit.take(8)} (component=$component), alreadyLoaded=$alreadyLoaded")
                        if (!alreadyLoaded) {
                            showCached(cachedCommit, gitRoot, reader)
                        }
                        loadFromGitLab(showErrors = false)
                        return
                    }
                }

                val latestArtifact = cache.listArtifacts().firstOrNull { it.component == component }
                if (latestArtifact != null) {
                    val reader = cache.get(latestArtifact.commitHash, latestArtifact.component, gitRoot)
                    if (reader != null) {
                        val alreadyLoaded = dataService.coverageCommitHash == latestArtifact.commitHash && dataService.hasData()
                        log.info("Coverage: offline-first fallback — commit ${latestArtifact.commitHash.take(8)} (component=$component), alreadyLoaded=$alreadyLoaded")
                        if (!alreadyLoaded) {
                            showCached(latestArtifact.commitHash, gitRoot, reader)
                        }
                        loadFromGitLab(showErrors = false)
                        return
                    }
                }
            }
        }

        log.info("Coverage: no cached artifacts found, asking the daemon")
        loadFromGitLab(showErrors = false)
    }

    /** Shows a cached reader immediately, marked stale until the daemon confirms it. */
    private fun showCached(commit: String, gitRoot: File, reader: Cov4Reader) {
        val dataService = CoverageDataService.getInstance(project)
        dataService.setCoverageContext(commit, gitRoot, stale = true)
        computeAndStoreWarningContext(gitRoot)
        dataService.setCov4Reader(reader)
        ApplicationManager.getApplication().invokeLater {
            CoverageHighlighter.applyToOpenEditors(project)
        }
    }

    /**
     * Asks the covd daemon to ensure coverage for the current HEAD.
     *
     * @param showErrors show a dialog for daemon-side errors; silent callers only log.
     * @param onComplete called on the EDT when the load finishes, successfully or not.
     */
    fun loadFromGitLab(showErrors: Boolean = true, onComplete: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        val validationError = validateSettings()
        if (validationError != null) {
            if (showErrors) {
                Messages.showErrorDialog(project, validationError, "Configuration Error")
            } else {
                log.info("Coverage auto-load skipped: $validationError")
            }
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }

        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: load skipped — gutter visibility is off")
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }

        if (loading) {
            pendingReload = true
            log.info("Coverage: load already in progress, will reload after completion")
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }
        loading = true
        lastError = null

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                loadViaCovd(showErrors, onComplete, onError)
            } catch (ex: Throwable) {
                log.warn("Coverage: unexpected error while loading coverage: ${ex.message}", ex)
                if (showErrors) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to load coverage:\n${ex.message}", "Coverage Error")
                    }
                }
                finishLoad(onComplete)
            }
        }
    }

    private fun loadViaCovd(showErrors: Boolean, onComplete: (() -> Unit)?, onError: ((String) -> Unit)?) {
        val settings = CoverageApiSettings.getInstance()
        val gitRoot = findGitRoot()
        val componentName = gitRoot?.let { detectComponent(it) }
        if (gitRoot == null || componentName == null) {
            val message = "Coverage is not available for this project: open the repository root or a recognized component directory."
            log.warn(message)
            if (showErrors) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, message, "Coverage Error")
                }
            }
            onError?.let { callback -> ApplicationManager.getApplication().invokeLater { callback(message) } }
            finishLoad(onComplete)
            return
        }

        val entry = ComponentConfig.allEntries().firstOrNull { it.serviceDir == componentName }
        if (entry == null) {
            val message = "Unknown coverage component '$componentName'."
            if (showErrors) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, message, "Coverage Error")
                }
            }
            onError?.let { callback -> ApplicationManager.getApplication().invokeLater { callback(message) } }
            finishLoad(onComplete)
            return
        }

        val request = CovdClient.EnsureRequest(
            gitRoot = gitRoot.absolutePath,
            component = componentName,
            pathMode = if (componentName == "raketa") "raketa" else "",
            jobPrefixes = listOf(entry.coverageJobPrefix, entry.jobPrefix),
            settings = CovdClient.Settings(
                baseUrl = settings.gitlabBaseUrl,
                projectId = settings.gitlabProjectId.toString(),
                coverageBranch = settings.coverageBranch.trim(),
                token = settings.bearerToken,
            ),
        )

        var unavailableReason: String? = null
        val response = CoveragePerfLog.span("covd.ensure", "component" to componentName) {
            try {
                CovdClient.ensure(request)
            } catch (e: CovdClient.Unavailable) {
                unavailableReason = e.message
                null
            }
        }

        if (response == null) {
            val message = "Coverage daemon (covd) is unavailable. The plugin needs it to load coverage."
            log.warn("Coverage: covd unavailable — $unavailableReason")
            lastError = message
            if (showErrors) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, message, "Coverage Daemon Unavailable")
                }
            }
            onError?.let { callback -> ApplicationManager.getApplication().invokeLater { callback(message) } }
            finishLoad(onComplete)
            return
        }

        when (response.state) {
            "ready" -> applyCovdCoverage(response, gitRoot, componentName, showErrors, onComplete, onError)
            "not_ready" -> {
                val delay = response.retryAfterMs.coerceIn(1_000, 60_000)
                log.info("Coverage: not ready (${response.reason}), retrying in $delay ms")
                lastError = null
                CoveragePerfLog.event("covd.notReady", "reason" to (response.reason ?: "-"), "retryMs" to delay)
                covdRetryAlarm.cancelAllRequests()
                covdRetryAlarm.addRequest({ loadFromGitLab(showErrors = false) }, delay)
                finishLoad(onComplete)
            }
            else -> {
                val message = describeError(response)
                log.warn("Coverage: covd error ${response.code}/${response.cause}: $message")
                lastError = message
                CoveragePerfLog.event("covd.error", "code" to (response.code ?: "-"), "cause" to (response.cause ?: "-"))
                if (showErrors) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, message, errorTitle(response.code))
                    }
                }
                onError?.let { callback -> ApplicationManager.getApplication().invokeLater { callback(message) } }
                finishLoad(onComplete)
            }
        }
    }

    /** Applies a `ready` answer: the daemon already wrote the COV4 files. */
    private fun applyCovdCoverage(
        response: CovdClient.EnsureResponse,
        gitRoot: File,
        componentName: String,
        showErrors: Boolean,
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?,
    ) {
        val commit = response.commit
        val cov4Path = response.cov4Path
        if (commit == null || cov4Path == null) {
            val message = "The coverage daemon returned a ready state without a coverage file."
            log.warn(message)
            lastError = message
            if (showErrors) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, message, "Coverage Error")
                }
            }
            onError?.let { callback -> ApplicationManager.getApplication().invokeLater { callback(message) } }
            finishLoad(onComplete)
            return
        }

        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)

        val wasStale = dataService.isStale
        val previousCommit = dataService.coverageCommitHash
        val alreadyLoaded = dataService.coverageCommitHash == commit && dataService.hasData()

        dataService.setCoverageContext(commit, gitRoot, stale = response.stale)
        computeAndStoreWarningContext(gitRoot)

        if (!alreadyLoaded || response.replaced) {
            val reader = cache.get(commit, componentName, gitRoot) ?: Cov4Reader(File(cov4Path))
            dataService.setCov4Reader(reader)
        }

        val baselineCommit = response.baselineCommit
        val baselinePath = response.baselineCov4Path
        if (baselineCommit != null && baselinePath != null) {
            if (dataService.baselineCommitHash != baselineCommit || response.replaced) {
                dataService.setBaselineCov4Reader(Cov4Reader(File(baselinePath)), baselineCommit)
            }
        } else {
            dataService.clearBaseline()
        }

        lastError = null

        CoveragePerfLog.event(
            "covd.ready",
            "commit" to commit.take(8),
            "cached" to response.cached,
            "replaced" to response.replaced,
            "stale" to response.stale,
            "files" to (response.stats?.files ?: 0),
            "lines" to (response.stats?.lines ?: 0),
            "covered" to (response.stats?.covered ?: 0),
            "baseline" to (baselineCommit?.take(8) ?: "-"),
        )
        log.info(
            "Coverage: ready — commit ${commit.take(8)}, component=$componentName, " +
                "cached=${response.cached}, stale=${response.stale}, files=${response.stats?.files}",
        )

        ApplicationManager.getApplication().invokeLater {
            CoverageHighlighter.applyToOpenEditors(project)
            if (wasStale && previousCommit != commit) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Coverage Notifications")
                    .createNotification(
                        "Coverage updated",
                        "Fresh coverage from commit ${commit.take(8)} (was ${previousCommit?.take(8) ?: "unknown"}).",
                        NotificationType.INFORMATION,
                    )
                    .notify(project)
            }
            onComplete?.invoke()
        }
        finishLoad(null)
    }

    private fun describeError(response: CovdClient.EnsureResponse): String = when (response.code) {
        "AUTH_INVALID" -> "GitLab token expired or invalid. Update it in Settings → Tools → GitLab Coverage."
        "ACCESS_DENIED" -> "Access denied. Check your GitLab token permissions and the project id."
        "PROJECT_SETUP" -> "GitLab settings are incomplete: check the domain, project and coverage branch."
        "NO_COMPONENT_JOBS" -> "This pipeline has no coverage jobs for the selected component."
        "NO_DATA" -> "No coverage data is available for the current commit."
        "GIT" -> "Could not read the local git repository."
        "CONNECTION" -> "Cannot connect to GitLab: check the network, proxy and CA settings."
        "GITLAB_API" -> "GitLab API error. Try again later."
        else -> response.hint ?: response.message ?: "Coverage load failed (${response.code ?: "unknown"})."
    }

    private fun errorTitle(code: String?): String = when (code) {
        "AUTH_INVALID", "ACCESS_DENIED", "PROJECT_SETUP", "NO_COMPONENT_JOBS", "NO_DATA", "GIT" -> "No Coverage Data"
        "CONNECTION", "GITLAB_API" -> "Connection Error"
        else -> "Coverage Error"
    }

    /** Loads coverage from a locally cached `.cov4` chosen by the user. */
    fun loadFromCache(commitHash: String, onComplete: (() -> Unit)? = null) {
        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)
        val gitRoot = findGitRoot()
        val component = if (gitRoot != null) detectComponent(gitRoot) ?: "raketa" else "raketa"

        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = commitHash

        ApplicationManager.getApplication().executeOnPooledThread {
            val reader = cache.get(commitHash, component, gitRoot)
            if (reader == null) {
                log.warn("Coverage: loadFromCache — no .cov4 found for commit $commitHash (component=$component)")
                onComplete?.let { ApplicationManager.getApplication().invokeLater(it) }
                return@executeOnPooledThread
            }
            if (gitRoot != null) {
                dataService.setCoverageContext(commitHash, gitRoot, stale = false)
                computeAndStoreWarningContext(gitRoot)
            }
            dataService.setCov4Reader(reader)
            log.info("Coverage: loaded from cache for commit ${commitHash.take(8)} (component=$component, ${reader.allFilePaths.size} files)")
            lastError = null

            ApplicationManager.getApplication().invokeLater {
                CoverageHighlighter.applyToOpenEditors(project)
                onComplete?.invoke()
            }
        }
    }

    /**
     * Imports a local `.covt`/`.covt.gz` file through the daemon, which writes it into the
     * cache like a downloaded artifact, then activates the resulting COV4.
     */
    fun loadFromLocalFile(file: File, onComplete: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Local Coverage File", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Importing ${file.name}…"
                    val gitRoot = findGitRootFromFile(file.parentFile) ?: findGitRoot()
                    val component = if (gitRoot != null) detectComponent(gitRoot) ?: "raketa" else "raketa"
                    val projectId = CoverageApiSettings.getInstance().gitlabProjectId.toString()

                    val response = CovdClient.import(
                        CovdClient.ImportRequest(
                            covtPath = file.absolutePath,
                            projectId = projectId,
                            component = component,
                            pathMode = if (component == "raketa") "raketa" else "",
                            gitRoot = gitRoot?.absolutePath.orEmpty(),
                        )
                    )
                    val commit = response.commit
                    val cov4Path = response.cov4Path
                    if (response.state != "ready" || commit == null || cov4Path == null) {
                        throw IllegalStateException(
                            response.message ?: "the coverage daemon rejected the file (${response.code})",
                        )
                    }

                    val cache = CoverageCacheService.getInstance(project)
                    val dataService = CoverageDataService.getInstance(project)
                                if (gitRoot != null) {
                        dataService.setCoverageContext(commit, gitRoot, stale = false)
                        computeAndStoreWarningContext(gitRoot)
                    }
                    val reader = cache.get(commit, component, gitRoot) ?: Cov4Reader(File(cov4Path))
                    dataService.setCov4Reader(reader)
                    lastError = null
                    log.info(
                        "Coverage: imported ${file.name} as commit ${commit.take(8)} " +
                            "(${response.stats?.files} files, component=$component)",
                    )

                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                        onComplete?.invoke()
                    }
                } catch (ex: Exception) {
                    log.warn("Coverage: failed to load local file ${file.name}", ex)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to load coverage file:\n${ex.message}", "Coverage Error")
                        onComplete?.invoke()
                    }
                }
            }
        })
    }

    private fun finishLoad(onComplete: (() -> Unit)?) {
        loading = false
        if (pendingReload) {
            pendingReload = false
            log.info("Coverage: pending reload detected, starting new load")
            ApplicationManager.getApplication().invokeLater {
                loadFromGitLab(showErrors = false)
            }
        }
        onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
        if (CoverageGutterVisibilityService.getInstance(project).visible) {
            CoveragePipelinePoller.getInstance(project).start()
        }
    }

    // --- local git helpers --------------------------------------------------------

    private fun computeAndStoreWarningContext(gitRoot: File) {
        val dataService = CoverageDataService.getInstance(project)
        val headHash = GitCommand.run(gitRoot, "rev-parse", "HEAD")
        val coverageBranch = CoverageApiSettings.getInstance().coverageBranch.trim()
        val mergeBase = if (coverageBranch.isNotEmpty()) {
            GitCommand.run(gitRoot, "merge-base", "HEAD", coverageBranch)
                ?: GitCommand.run(gitRoot, "merge-base", "HEAD", "origin/$coverageBranch")
        } else {
            null
        }

        val primaryBehindBy = if (headHash != null && dataService.coverageCommitHash != null && headHash != dataService.coverageCommitHash) {
            countBehind(gitRoot, dataService.coverageCommitHash!!, headHash)
        } else {
            null
        }
        val baselineBehindBy = if (mergeBase != null && dataService.baselineCommitHash != null && mergeBase != dataService.baselineCommitHash) {
            countBehind(gitRoot, dataService.baselineCommitHash!!, mergeBase)
        } else {
            null
        }
        dataService.setWarningContext(headHash, mergeBase, primaryBehindBy, baselineBehindBy)
    }

    private fun countBehind(gitRoot: File, actual: String, expected: String): Int? =
        GitCommand.run(gitRoot, "rev-list", "--count", "$actual..$expected")?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    private fun findGitRootFromFile(dir: File?): File? {
        var current = dir
        while (current != null) {
            if (File(current, ".git").exists()) return current
            current = current.parentFile
        }
        return null
    }

    private fun getRecentCommits(gitRoot: File, count: Int): List<String> {
        val output = GitCommand.run(gitRoot, "log", "--format=%H", "-n", count.toString()) ?: return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    private fun findGitRoot(): File? {
        val basePath = project.basePath ?: return null
        val root = GitCommand.run(File(basePath), "rev-parse", "--show-toplevel") ?: return null
        return File(root)
    }

    /** Detects the component for the current project, or null if unknown. */
    private fun detectComponent(gitRoot: File): String? {
        val basePath = project.basePath ?: return null
        val entry = ComponentConfig.detectComponent(basePath, gitRoot)
        if (entry != null) {
            log.info("Coverage: detected component '${entry.serviceDir}'")
            return entry.serviceDir
        }
        log.info("Coverage: no component detected for project at $basePath")
        return null
    }

    companion object {
        fun getInstance(project: Project): CoverageLoadService = project.service()
    }
}
