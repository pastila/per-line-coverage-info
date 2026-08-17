package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import java.io.File

@Service(Service.Level.PROJECT)
class CoveragePipelinePoller(private val project: Project) : Disposable {

    private val log = CoverageLog.get(CoveragePipelinePoller::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private val IN_FLIGHT_STATUSES = setOf("created", "waiting_for_resource", "preparing", "pending", "running")
        private val TERMINAL_FAILURE_STATUSES = setOf("failed", "canceled", "skipped")
        fun getInstance(project: Project): CoveragePipelinePoller = project.service()
    }

    @Volatile
    private var started = false

    @Volatile
    private var targetBranch: String? = null

    @Volatile
    private var polling = false

    private var consecutiveErrors = 0

    /** SHA of a failed pipeline we already notified about (don't re-notify). */
    private var reportedFailureSha: String? = null

    /** Last time the quiet "remote unchanged" state was logged (throttle debug noise). */
    private var lastQuietLogMs = 0L

    /**
     * Starts or restarts the polling loop. Called after coverage loads, HEAD changes,
     * or whenever the plugin should begin watching for pipeline updates.
     *
     * The polling loop runs continuously once started: every [POLL_INTERVAL_MS] it
     * checks whether the remote branch has a newer pipeline (vs loaded coverage) and
     * reacts accordingly. It never stops itself — only [stop] or [dispose] ends it.
     */
    fun start() {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: pipeline poll — gutter visibility off, won't start")
            return
        }
        if (started && targetBranch != null) return
        started = true
        ApplicationManager.getApplication().executeOnPooledThread { scheduleNext() }
    }

    fun stop() {
        alarm.cancelAllRequests()
        started = false
        targetBranch = null
        consecutiveErrors = 0
        reportedFailureSha = null
        log.info("Coverage: pipeline polling stopped")
    }

    override fun dispose() { stop() }

    /**
     * Schedules the next poll cycle. Called from [start] and at the end of each [onPoll].
     */
    private fun scheduleNext() {
        if (!started || project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ onPoll() }, POLL_INTERVAL_MS)
    }

    private fun onPoll() {
        if (project.isDisposed) { stop(); return }
        if (!started) return

        if (polling) {
            log.info("Coverage: pipeline poll — previous poll still in progress, skipping")
            scheduleNext()
            return
        }
        polling = true
        try {
            doPoll()
        } finally {
            polling = false
        }
    }

    private fun doPoll() {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: pipeline poll — gutter visibility off, stopping")
            stop()
            return
        }

        val settings = CoverageApiSettings.getInstance()
        if (!settings.enabled) {
            log.info("Coverage: pipeline poll — plugin disabled, stopping")
            stop()
            return
        }

        val gitRoot = findGitRoot()
        if (gitRoot == null) {
            log.warn("Coverage: pipeline poll — cannot find git root, retrying in ${POLL_INTERVAL_MS}ms")
            scheduleNext(); return
        }

        val branch = CoverageResolver.runGitCommand(gitRoot, "rev-parse", "--abbrev-ref", "HEAD")
        if (branch.isNullOrBlank() || branch == "HEAD") {
            log.info("Coverage: pipeline poll — detached HEAD, stopping")
            stop()
            return
        }

        targetBranch = branch

        // While a load is running for this project, don't fire extra requests.
        if (CoverageLoadService.getInstance(project).isLoadInProgress()) {
            log.info("Coverage: pipeline poll — load in progress, skipping this cycle")
            scheduleNext()
            return
        }

        val remoteSha = try {
            CoverageResolver.runGitCommand(gitRoot, "ls-remote", "origin", "refs/heads/$branch")
                ?.substringBefore('\t')
        } catch (e: Exception) {
            log.warn("Coverage: pipeline poll — git ls-remote failed: ${e.message}")
            null
        }

        if (remoteSha == null) {
            log.info("Coverage: pipeline poll — could not contact remote, retrying in ${POLL_INTERVAL_MS}ms")
            scheduleNext()
            return
        }

        val dataService = CoverageDataService.getInstance(project)
        val loadedSha = dataService.coverageCommitHash

        if (remoteSha == loadedSha) {
            // Quiet steady state (every poll cycle) — log at most once a minute.
            val now = System.currentTimeMillis()
            if (now - lastQuietLogMs > 60_000) {
                lastQuietLogMs = now
                log.debug("Coverage: pipeline poll — remote unchanged (${loadedSha?.take(8) ?: "none"}), coverage current")
            }
            scheduleNext()
            return
        }

        // Remote has a different commit — check if there's a pipeline
        val projectId = settings.gitlabProjectId
        val client = getOrCreateClient()

        val latestPipeline = try {
            client.listPipelines(projectId, branch, status = null, perPage = 1).firstOrNull()
        } catch (e: Exception) {
            consecutiveErrors++
            if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                log.warn("Coverage: pipeline poll — $MAX_CONSECUTIVE_ERRORS consecutive errors, stopping")
                stop()
            } else {
                log.warn("Coverage: pipeline poll — error ${consecutiveErrors}/$MAX_CONSECUTIVE_ERRORS: ${e.message}")
                scheduleNext()
            }
            return
        }

        consecutiveErrors = 0

        if (latestPipeline == null) {
            log.info("Coverage: pipeline poll — no pipeline found on '$branch', retrying in ${POLL_INTERVAL_MS}ms")
            scheduleNext()
            return
        }

        // A pipeline exists — decide what to do
        if (latestPipeline.sha == loadedSha) {
            // Pipeline matches already-loaded coverage (e.g. same SHA from older pipeline)
            scheduleNext()
            return
        }

        when (latestPipeline.status) {
            "success" -> {
                log.info("Coverage: pipeline poll — pipeline ${latestPipeline.id} (${latestPipeline.sha.take(8)}) succeeded, newer than loaded coverage")
                triggerLoad()
            }
            in TERMINAL_FAILURE_STATUSES -> {
                if (latestPipeline.sha != reportedFailureSha) {
                    reportedFailureSha = latestPipeline.sha
                    log.info("Coverage: pipeline poll — pipeline ${latestPipeline.id} (${latestPipeline.sha.take(8)}) is ${latestPipeline.status}")
                }
                scheduleNext()
            }
            in IN_FLIGHT_STATUSES -> {
                log.info("Coverage: pipeline poll — pipeline ${latestPipeline.id} (${latestPipeline.sha.take(8)}) is ${latestPipeline.status}")
                scheduleNext()
            }
            else -> {
                log.info("Coverage: pipeline poll — pipeline ${latestPipeline.id} status '${latestPipeline.status}' (unexpected), continuing")
                scheduleNext()
            }
        }
    }

    /**
     * Returns the shared, cached, rate-limited GitLab API from the app-level
     * [GitLabCoordinator]. Poller requests are automatically deduplicated and
     * rate-limited together with all other plugin traffic.
     */
    private fun getOrCreateClient(): GitLabApi = GitLabCoordinator.getInstance().api()

    private fun triggerLoad() {
        stop()
        CoverageLoadService.getInstance(project).loadFromGitLab(showErrors = false)
    }

    private fun findGitRoot(): File? {
        val basePath = project.basePath ?: return null
        val projectDir = File(basePath)
        val path = CoverageResolver.runGitCommand(projectDir, "rev-parse", "--show-toplevel") ?: return null
        return File(path)
    }
}
