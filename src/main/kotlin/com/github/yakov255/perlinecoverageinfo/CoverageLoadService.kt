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
import java.util.concurrent.atomic.AtomicBoolean
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

    /** True while a daemon load runs; taken with compareAndSet so two callers never both start one. */
    private val loading = AtomicBoolean(false)

    /** A load was requested while another one ran; [finishLoad] starts it afterwards. */
    private val pendingReload = AtomicBoolean(false)

    /** Last user-facing load error, or null when the last attempt succeeded. Read by [CoverageUiTestBridge]. */
    @Volatile
    var lastError: String? = null
        private set

    /** Retry timer for `not_ready` answers and for retryable daemon errors. */
    private val covdRetryAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)

    private val retryPolicy = RetryPolicy()
    private val retryState = RetryState()

    /** Growing wait between asks while the daemon answers `not_ready` for the same reason. */
    private val notReadyBackoff = NotReadyBackoff()
    private val refreshState = CoverageRefreshState()

    private data class CoverageFileStamp(val path: String, val size: Long, val modified: Long) {
        constructor(path: String) : this(File(path).absolutePath, File(path).length(), File(path).lastModified())
    }

    private data class AppliedCoverage(
        val gitRoot: String,
        val head: String?,
        val baselineRef: String?,
        val commit: String,
        val primary: CoverageFileStamp,
        val baselineCommit: String?,
        val baseline: CoverageFileStamp?,
        val stale: Boolean,
    )

    private var lastApplied: AppliedCoverage? = null
    private var lastAppliedGeneration = -1L

    /** A balloon is shown once per retry cycle; the next poll stays silent. */
    @Volatile
    private var exhaustedNotified = false

    /** Automatic retries scheduled for the current HEAD and settings. */
    val retryAttempt: Int get() = retryState.attempt

    /** Automatic retries allowed before the balloon. */
    val retryMax: Int get() = retryPolicy.maxAttempts

    fun isLoadInProgress(): Boolean = loading.get()

    /**
     * True while the user keeps a specific artifact pinned. Automatic loads (poller, retry
     * alarm, pending reload) must not reach the daemon or replace the data then; a manual
     * "Load Coverage from GitLab" / Fetch lifts the pin via [clearPin].
     */
    fun isPinned(): Boolean = CoverageUserSelectionService.getInstance(project).pinnedCommitHash != null

    /** Removes the pin and cancels a scheduled automatic retry that was waiting for it. */
    fun clearPin() {
        val selection = CoverageUserSelectionService.getInstance(project)
        if (selection.pinnedCommitHash != null) {
            log.info("Coverage: pin ${selection.pinnedCommitHash?.take(8)} cleared")
            selection.pinnedCommitHash = null
        }
    }

    /** "Clear Coverage Data": drop the pin, any scheduled retry and a queued reload. */
    fun onCleared() {
        clearPin()
        covdRetryAlarm.cancelAllRequests()
        pendingReload.set(false)
        refreshState.clear()
        lastApplied = null
    }

    private fun setPin(commit: String) {
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = commit
        covdRetryAlarm.cancelAllRequests()
    }

    /** True when HEAD is no longer the commit the request was sent for. */
    private fun headMoved(requestHead: String?): Boolean {
        if (requestHead == null) return false
        val now = currentHead() ?: return false
        return now != requestHead
    }

    /**
     * The active block (`AUTH` or `NON_RETRYABLE:<code>`), or null. A block is tied to the
     * HEAD and settings it was created for: a change lifts it here.
     */
    fun blockedReason(): String? = if (isBlocked()) retryState.blocked else null

    /**
     * True while an automatic load must not reach the daemon: an auth block or a
     * deterministic error for this HEAD and these settings is still active.
     */
    fun isBlocked(): Boolean {
        if (retryState.blocked == null) return false
        if (retryState.isBlockedFor(currentHead(), settingsFingerprint())) return true
        log.info("Coverage: block ${retryState.blocked} cleared — HEAD or settings changed")
        resetFailureState()
        return false
    }

    /** HEAD moved: the block and the retry budget belong to the old commit. */
    fun onHeadChanged(head: String? = null) {
        if (retryState.blocked == RetryPolicy.AUTH_BLOCK) {
            // The token problem outlives the commit: only new settings lift it.
            log.info("Coverage: HEAD changed${head?.let { " to ${it.take(8)}" } ?: ""}, the token block stays")
            return
        }
        val hadState = retryState.blocked != null || retryState.attempt > 0
        resetFailureState()
        covdRetryAlarm.cancelAllRequests()
        if (hadState) {
            log.info("Coverage: failure state reset after HEAD change${head?.let { " to ${it.take(8)}" } ?: ""}")
        }
    }

    /** Settings were applied: clear the block and the counter, then the caller loads again. */
    fun onSettingsApplied() {
        refreshState.clear()
        resetFailureState()
        covdRetryAlarm.cancelAllRequests()
        log.info("Coverage: failure state reset after settings change")
    }

    /** Counter, block, backoff and the "already notified" mark fall together. */
    private fun resetFailureState() {
        retryState.reset()
        notReadyBackoff.reset()
        exhaustedNotified = false
    }

    /** Test seam: cancels a scheduled retry so it cannot fire into a later test. */
    internal fun cancelRetriesForTest() {
        covdRetryAlarm.cancelAllRequests()
    }

    internal fun hasScheduledRetryForTest(): Boolean = !covdRetryAlarm.isEmpty

    /** Test seam: how many `not_ready` answers the backoff has seen for the current reason. */
    internal fun notReadyBackoffStepForTest(): Int = notReadyBackoff.currentStep()

    /** Called off the EDT. Idle ticks inspect local refs only, never GitLab or covd. */
    internal fun pollForUpdates() {
        if (!covdRetryAlarm.isEmpty || (exhaustedNotified && retryAttempt >= retryMax)) return
        val root = findGitRoot() ?: return
        val context = refreshContext(root) ?: return
        if (refreshState.shouldPoll(context, CoverageDataService.getInstance(project).coverageCommitHash)) {
            CoveragePerfLog.event("poll.refresh")
            loadFromGitLab(showErrors = false)
        }
    }

    private fun refreshContext(root: File): CoverageRefreshContext? {
        val revision = GitCommand.run(root, "rev-parse", "HEAD", "--abbrev-ref", "HEAD")?.lines() ?: return null
        val head = revision.firstOrNull() ?: return null
        val branch = revision.getOrNull(1)?.takeUnless { it == "HEAD" }
        val coverageBranch = CoverageApiSettings.getInstance().coverageBranch.trim()
        val patterns = listOfNotNull(
            branch?.let { "refs/remotes/origin/$it" },
            coverageBranch.takeIf { it.isNotEmpty() }?.let { "refs/remotes/origin/$it" },
            coverageBranch.takeIf { it.isNotEmpty() }?.let { "refs/heads/$it" },
        )
        // Missing refs and detached HEAD are normal, so do not run failing rev-parse probes.
        val refs = if (patterns.isEmpty()) emptyMap() else {
            GitCommand.runRaw(root, "for-each-ref", "--format=%(refname) %(objectname)", *patterns.toTypedArray())
                .orEmpty().lineSequence().mapNotNull { line ->
                    val parts = line.split(' ', limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
        }
        val remote = branch?.let { refs["refs/remotes/origin/$it"] }
        val baseline = refs["refs/remotes/origin/$coverageBranch"] ?: refs["refs/heads/$coverageBranch"]
        return CoverageRefreshContext(root.absolutePath, head, branch, remote, baseline, settingsFingerprint())
    }

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
     *
     * @param manual the user asked explicitly (Find Action): bypass an active block and
     *   restart the retry budget.
     */
    fun loadOfflineFirst(manual: Boolean = false) {
        CoveragePerfLog.span("load.offlineFirst") { loadOfflineFirstInner(manual) }
    }

    private fun loadOfflineFirstInner(manual: Boolean) {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: offline-first load skipped — gutter visibility is off")
            return
        }
        if (loading.get()) {
            pendingReload.set(true)
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
            reader?.use {
                val alreadyLoaded = dataService.coverageCommitHash == pinned && dataService.hasData()
                log.info("Coverage: offline-first pinned — commit ${pinned.take(8)}, alreadyLoaded=$alreadyLoaded")
                if (!alreadyLoaded) {
                    if (root != null) {
                        dataService.setCoverage(pinned, root, stale = false, reader = reader)
                        computeAndStoreWarningContext(root)
                    } else {
                        dataService.setCov4Reader(reader)
                    }
                    ApplicationManager.getApplication().invokeLater {
                        CoverageHighlighter.applyToOpenEditors(project)
                    }
                }
                // The user pinned this artifact deliberately: no daemon call, no refresh.
                // Only a manual load (which lifts the pin first) or Clear gets out of it.
                return
            }
            log.info("Coverage: pinned commit ${pinned.take(8)} not found in cache, clearing pin")
            selectionService.pinnedCommitHash = null
        }

        val gitRoot = findGitRoot()
        if (gitRoot != null) {
            val component = detectComponent(gitRoot)
            if (component != null) {
                val cachedCommit = cache.findCachedCommit(getRecentCommits(gitRoot, 200), component)
                if (cachedCommit != null) {
                    val reader = cache.get(cachedCommit, component, gitRoot)
                    reader?.use {
                        val alreadyLoaded = dataService.coverageCommitHash == cachedCommit && dataService.hasData()
                        log.info("Coverage: offline-first hit — commit ${cachedCommit.take(8)} (component=$component), alreadyLoaded=$alreadyLoaded")
                        if (!alreadyLoaded) {
                            showCached(cachedCommit, gitRoot, reader)
                        }
                        loadFromGitLab(showErrors = false, bypassBlock = manual)
                        return
                    }
                }

                val latestArtifact = cache.listArtifacts().firstOrNull { it.component == component }
                if (latestArtifact != null) {
                    val reader = cache.get(latestArtifact.commitHash, latestArtifact.component, gitRoot)
                    reader?.use {
                        val alreadyLoaded = dataService.coverageCommitHash == latestArtifact.commitHash && dataService.hasData()
                        log.info("Coverage: offline-first fallback — commit ${latestArtifact.commitHash.take(8)} (component=$component), alreadyLoaded=$alreadyLoaded")
                        if (!alreadyLoaded) {
                            showCached(latestArtifact.commitHash, gitRoot, reader)
                        }
                        loadFromGitLab(showErrors = false, bypassBlock = manual)
                        return
                    }
                }
            }
        }

        log.info("Coverage: no cached artifacts found, asking the daemon")
        loadFromGitLab(showErrors = false, bypassBlock = manual)
    }

    /** Shows a cached reader immediately, marked stale until the daemon confirms it. */
    private fun showCached(commit: String, gitRoot: File, reader: Cov4Reader) {
        val dataService = CoverageDataService.getInstance(project)
        dataService.setCoverage(commit, gitRoot, stale = true, reader = reader)
        computeAndStoreWarningContext(gitRoot)
        ApplicationManager.getApplication().invokeLater {
            CoverageHighlighter.applyToOpenEditors(project)
        }
    }

    /**
     * Asks the covd daemon to ensure coverage for the current HEAD.
     *
     * @param showErrors show a dialog for daemon-side errors; silent callers only log.
     * @param onComplete called on the EDT when the load finishes, successfully or not.
     * @param bypassBlock the user asked explicitly: ignore an active block and restart the
     *   retry budget.
     */
    fun loadFromGitLab(
        showErrors: Boolean = true,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        bypassBlock: Boolean = false,
    ) {
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

        if (bypassBlock || showErrors) {
            // A manual load is the user's explicit request: no block, fresh retry budget.
            refreshState.clear()
            resetFailureState()
            covdRetryAlarm.cancelAllRequests()
            log.info("Coverage: manual load bypasses the failure block")
        } else if (isPinned()) {
            log.info("Coverage: automatic load skipped — a pinned artifact is shown")
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        } else if (isBlocked()) {
            log.info("Coverage: load skipped — blocked by ${retryState.blocked}")
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }

        if (!loading.compareAndSet(false, true)) {
            pendingReload.set(true)
            log.info("Coverage: load already in progress, will reload after completion")
            onComplete?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
            return
        }
        lastError = null

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                loadViaCovd(showErrors, onComplete, onError, bypassBlock || showErrors)
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

    private fun loadViaCovd(
        showErrors: Boolean,
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?,
        manual: Boolean,
    ) {
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

        val entry = ComponentConfig.allEntries().firstOrNull { it.coverageKey == componentName }
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

        // HEAD at send time: an answer for another commit must not pass as fresh.
        val context = refreshContext(gitRoot)
        val requestHead = context?.head ?: currentHead()
        val pinnedAtStart = isPinned()
        val data = CoverageDataService.getInstance(project)
        if (!manual && context != null && data.hasData() && !data.isStale && refreshState.isWithinCooldown(context)) {
            log.info("Coverage: automatic refresh skipped — unchanged refs within the refresh cooldown")
            finishLoad(onComplete)
            return
        }

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
            handleDaemonError(
                showErrors = showErrors,
                onComplete = onComplete,
                onError = onError,
                code = "CONNECTION",
                retryable = true,
                retryAfterMs = 0,
                message = message,
                title = "Coverage Daemon Unavailable",
                requestHead = requestHead,
            )
            return
        }

        when (response.state) {
            "ready" -> {
                notReadyBackoff.reset()
                applyCovdCoverage(
                    response, gitRoot, componentName, showErrors, onComplete, onError, requestHead, pinnedAtStart, context,
                )
            }
            "not_ready" -> {
                if (headMoved(requestHead)) {
                    log.info("Coverage: not_ready answer is for an older HEAD — reloading for the new one")
                    pendingReload.set(true)
                    finishLoad(onComplete)
                    return
                }
                // The daemon waits for CI jobs: back off per reason instead of asking every second.
                val delay = notReadyBackoff.nextDelay(response.reason, response.retryAfterMs)
                val waitForPipeline = context?.let { refreshState.onNotReady(it, response.reason) } ?: true
                log.info(
                    if (waitForPipeline) {
                        "Coverage: not ready (${response.reason}), retrying in $delay ms " +
                            "(step ${notReadyBackoff.currentStep()})"
                    } else {
                        "Coverage: no pipeline for an unpushed HEAD — waiting for its local origin ref to change"
                    },
                )
                lastError = null
                CoveragePerfLog.event(
                    "covd.notReady",
                    "reason" to (response.reason ?: "-"),
                    "retryMs" to if (waitForPipeline) delay else 0,
                    "step" to notReadyBackoff.currentStep(),
                )
                covdRetryAlarm.cancelAllRequests()
                if (waitForPipeline) covdRetryAlarm.addRequest({ loadFromGitLab(showErrors = false) }, delay)
                finishLoad(onComplete)
            }
            else -> {
                val message = describeError(response)
                log.warn("Coverage: covd error ${response.code}/${response.cause}: $message")
                CoveragePerfLog.event("covd.error", "code" to (response.code ?: "-"), "cause" to (response.cause ?: "-"))
                handleDaemonError(
                    showErrors = showErrors,
                    onComplete = onComplete,
                    onError = onError,
                    code = response.code,
                    retryable = response.retryable,
                    retryAfterMs = response.retryAfterMs,
                    message = message,
                    title = errorTitle(response.code),
                    requestHead = requestHead,
                )
            }
        }
    }

    /**
     * Applies the failure contract to one daemon error: retry, block or stop, always recording
     * `lastError` and calling the caller's callbacks. The retry itself is silent; only the
     * exhausted budget or a token problem reaches the user.
     */
    private fun handleDaemonError(
        showErrors: Boolean,
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?,
        code: String?,
        retryable: Boolean,
        retryAfterMs: Long,
        message: String,
        title: String,
        requestHead: String?,
    ) {
        if (headMoved(requestHead)) {
            // The error belongs to a commit nobody looks at any more: no block, no retry
            // budget for the new HEAD, just load again for it.
            log.info("Coverage: ${code ?: "error"} answer is for an older HEAD — ignored, reloading for the new one")
            pendingReload.set(true)
            finishLoad(onComplete)
            return
        }
        lastError = message
        if (showErrors) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, message, title)
            }
        }
        onError?.let { callback -> ApplicationManager.getApplication().invokeLater { callback(message) } }

        val decision = retryPolicy.decide("error", code, retryable, retryAfterMs, retryState.attempt)
        when (decision.verdict) {
            RetryPolicy.Verdict.RETRY -> {
                retryState.onRetryScheduled()
                log.info(
                    "Coverage: ${code ?: "error"} is retryable — retry " +
                        "${retryState.attempt}/${retryPolicy.maxAttempts} in ${decision.delayMs} ms",
                )
                CoveragePerfLog.event(
                    "covd.retry",
                    "attempt" to retryState.attempt,
                    "max" to retryPolicy.maxAttempts,
                    "delayMs" to decision.delayMs,
                    "code" to (code ?: "-"),
                )
                covdRetryAlarm.cancelAllRequests()
                covdRetryAlarm.addRequest({ loadFromGitLab(showErrors = false) }, decision.delayMs)
            }
            RetryPolicy.Verdict.BLOCK_AUTH -> {
                val reason = decision.blocked ?: RetryPolicy.AUTH_BLOCK
                retryState.block(reason, currentHead(), settingsFingerprint())
                CoveragePerfLog.event("covd.blocked", "reason" to reason, "code" to (code ?: "-"))
                log.warn("Coverage: blocked ($reason) — no retries until the settings change")
                CoverageNotifier.getInstance(project).notifyAuthInvalid()
            }
            RetryPolicy.Verdict.STOP_UNTIL_CHANGE -> {
                val reason = decision.blocked ?: (RetryPolicy.NON_RETRYABLE_PREFIX + code)
                retryState.block(reason, currentHead(), settingsFingerprint())
                CoveragePerfLog.event("covd.blocked", "reason" to reason, "code" to (code ?: "-"))
                log.warn("Coverage: blocked ($reason) — the poller waits for a HEAD or settings change")
            }
            RetryPolicy.Verdict.NONE -> {
                if (retryable) {
                    if (!exhaustedNotified) {
                        exhaustedNotified = true
                        log.warn(
                            "Coverage: retries exhausted (${retryState.attempt}/${retryPolicy.maxAttempts}) " +
                                "— showing the notification",
                        )
                        CoverageNotifier.getInstance(project).notifyRetryExhausted(message)
                    } else {
                        log.info("Coverage: retries still exhausted — notification already shown")
                    }
                }
            }
        }
        finishLoad(onComplete)
    }

    /** Applies a `ready` answer: the daemon already wrote the COV4 files. */
    private fun applyCovdCoverage(
        response: CovdClient.EnsureResponse,
        gitRoot: File,
        componentName: String,
        showErrors: Boolean,
        onComplete: (() -> Unit)?,
        onError: ((String) -> Unit)?,
        requestHead: String?,
        pinnedAtStart: Boolean,
        context: CoverageRefreshContext?,
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

        if (!pinnedAtStart && isPinned()) {
            // The user pinned an artifact while the daemon worked: the pin wins.
            log.info("Coverage: answer for ${commit.take(8)} dropped — an artifact was pinned meanwhile")
            finishLoad(onComplete)
            return
        }

        val cache = CoverageCacheService.getInstance(project)
        val dataService = CoverageDataService.getInstance(project)

        val headMoved = headMoved(requestHead)
        val wasStale = dataService.isStale
        val previousCommit = dataService.coverageCommitHash
        val alreadyLoaded = dataService.coverageCommitHash == commit && dataService.hasData()

        // An answer for an older HEAD is shown, but marked stale until the reload for the new one.
        val stale = response.stale || headMoved
        // Pushing the same HEAD or changing credentials does not change editor coverage.
        val identity = AppliedCoverage(
            gitRoot.absolutePath, requestHead, context?.baselineHead,
            commit, CoverageFileStamp(cov4Path), response.baselineCommit,
            response.baselineCov4Path?.let(::CoverageFileStamp), stale,
        )
        if (!response.replaced && identity == lastApplied && dataService.coverageGeneration == lastAppliedGeneration) {
            if (!headMoved) {
                lastError = null
                resetFailureState()
                covdRetryAlarm.cancelAllRequests()
                context?.let { refreshState.onReady(it, stale, response.baselineState == "not_ready") }
            } else {
                pendingReload.set(true)
            }
            log.info("Coverage: unchanged daemon answer — keeping readers and editor highlights")
            finishLoad(onComplete)
            return
        }
        if (response.replaced) GitLabCoordinator.getInstance().evictSharedReaders(commit, componentName)
        val reader = if (!alreadyLoaded || response.replaced || lastApplied?.primary != identity.primary) {
            cache.get(commit, componentName, gitRoot) ?: Cov4Reader(File(cov4Path))
        } else {
            null
        }
        reader.use { dataService.setCoverage(commit, gitRoot, stale, it) }

        val baselineCommit = response.baselineCommit
        val baselinePath = response.baselineCov4Path
        if (baselineCommit != null && baselinePath != null) {
            if (dataService.baselineCommitHash != baselineCommit || !dataService.hasBaseline() ||
                response.replaced || lastApplied?.baseline != identity.baseline
            ) {
                Cov4Reader(File(baselinePath)).use {
                    dataService.setBaselineCov4Reader(it, baselineCommit)
                }
            }
        } else {
            dataService.clearBaseline()
        }
        computeAndStoreWarningContext(gitRoot)

        if (headMoved) {
            log.info("Coverage: answer for ${commit.take(8)} arrived after HEAD moved — shown as stale, reloading")
            pendingReload.set(true)
        } else {
            lastError = null
            resetFailureState()
            covdRetryAlarm.cancelAllRequests()
            context?.let { refreshState.onReady(it, stale, response.baselineState == "not_ready") }
        }

        lastApplied = identity
        lastAppliedGeneration = dataService.coverageGeneration

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
            if (!headMoved && wasStale && previousCommit != commit) {
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
        setPin(commitHash)

        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val gitRoot = findGitRoot()
            val component = if (gitRoot != null) detectComponent(gitRoot) ?: "raketa" else "raketa"
            val reader = cache.get(commitHash, component, gitRoot)
            if (reader == null) {
                log.warn("Coverage: loadFromCache — no .cov4 found for commit $commitHash (component=$component)")
                onComplete?.let { ApplicationManager.getApplication().invokeLater(it) }
                return@executeOnPooledThread
            }
            reader.use {
                if (gitRoot != null) {
                    dataService.setCoverage(commitHash, gitRoot, stale = false, reader = reader)
                    computeAndStoreWarningContext(gitRoot)
                } else {
                    dataService.setCov4Reader(reader)
                }
                log.info("Coverage: loaded from cache for commit ${commitHash.take(8)} (component=$component, ${reader.allFilePaths.size} files)")
            }
            lastError = null
            resetFailureState()

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

                    // The imported file is what the user wants to look at: pin it first so no
                    // poller tick replaces it, then show it.
                    setPin(commit)
                    val cache = CoverageCacheService.getInstance(project)
                    val dataService = CoverageDataService.getInstance(project)
                    val reader = cache.get(commit, component, gitRoot) ?: Cov4Reader(File(cov4Path))
                    reader.use {
                        if (gitRoot != null) {
                            dataService.setCoverage(commit, gitRoot, stale = false, reader = reader)
                            computeAndStoreWarningContext(gitRoot)
                        } else {
                            dataService.setCov4Reader(reader)
                        }
                    }
                    lastError = null
                    resetFailureState()
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
        loading.set(false)
        if (pendingReload.compareAndSet(true, false)) {
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

    /** HEAD of the repository the current failure state belongs to. */
    private fun currentHead(): String? {
        val root = CoverageDataService.getInstance(project).gitRoot ?: findGitRoot() ?: return null
        return GitCommand.run(root, "rev-parse", "HEAD")
    }

    /**
     * Fingerprint of the settings that affect a load: a change means an old block is stale.
     * The token contributes to the hash but is never logged or stored anywhere else.
     */
    private fun settingsFingerprint(): String {
        val settings = CoverageApiSettings.getInstance()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(
            listOf(
                settings.enabled,
                settings.gitlabDomain,
                settings.gitlabProjectId,
                settings.coverageBranch,
                settings.bearerToken,
            ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
        )
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeAndStoreWarningContext(gitRoot: File) {
        val dataService = CoverageDataService.getInstance(project)
        val headHash = GitCommand.run(gitRoot, "rev-parse", "HEAD")
        val coverageBranch = CoverageApiSettings.getInstance().coverageBranch.trim()
        val mergeBase = if (coverageBranch.isNotEmpty()) {
            GitCommand.run(gitRoot, "merge-base", "--end-of-options", "HEAD", coverageBranch)
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
            return entry.coverageKey
        }
        log.info("Coverage: no component detected for project at $basePath")
        return null
    }

    companion object {
        fun getInstance(project: Project): CoverageLoadService = project.service()
    }
}
