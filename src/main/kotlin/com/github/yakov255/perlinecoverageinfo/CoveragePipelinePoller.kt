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

    /** SHA of the successful pipeline a load was last triggered for (don't reload it in a loop). */
    @Volatile
    private var triggeredLoadSha: String? = null

    /**
     * Starts the polling loop. Called after coverage loads and when the remote-tracking
     * ref of the current branch moves (push / fetch, see [CoverageHeadTracker]).
     *
     * Every [POLL_INTERVAL_MS] it compares the remote-tracking ref `origin/<branch>` —
     * read locally, without network access — with the loaded coverage and asks GitLab
     * for the pipeline only when they differ. The loop stops itself once there is
     * nothing to wait for: coverage of the remote commit is loaded, the branch is not
     * pushed, or the pipeline of the remote commit finished without success.
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

        // The remote-tracking ref is updated by push and fetch; reading it costs no network
        // request. CoverageHeadTracker restarts polling when it moves.
        val remoteSha = CoverageResolver.runGitCommand(gitRoot, "rev-parse", "--verify", "--quiet", "refs/remotes/origin/$branch")
        if (remoteSha == null) {
            log.info("Coverage: pipeline poll — '$branch' has no origin/$branch ref, stopping until it is pushed")
            stop()
            return
        }

        val dataService = CoverageDataService.getInstance(project)
        val loadedSha = dataService.coverageCommitHash

        if (remoteSha == loadedSha) {
            log.info("Coverage: pipeline poll — coverage of origin/$branch (${remoteSha.take(8)}) is loaded, stopping")
            stop()
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
                if (latestPipeline.sha == triggeredLoadSha) {
                    // Already loaded, but it gave no coverage of this commit (e.g. the pipeline
                    // has no coverage job for the component and the load fell back to master).
                    log.info("Coverage: pipeline poll — pipeline ${latestPipeline.id} (${latestPipeline.sha.take(8)}) already loaded, stopping")
                    stop()
                    return
                }
                triggeredLoadSha = latestPipeline.sha
                log.info("Coverage: pipeline poll — pipeline ${latestPipeline.id} (${latestPipeline.sha.take(8)}) succeeded, newer than loaded coverage")
                triggerLoad()
            }
            in TERMINAL_FAILURE_STATUSES -> {
                if (latestPipeline.sha != reportedFailureSha) {
                    reportedFailureSha = latestPipeline.sha
                    log.info("Coverage: pipeline poll — pipeline ${latestPipeline.id} (${latestPipeline.sha.take(8)}) is ${latestPipeline.status}")
                }
                if (latestPipeline.sha == remoteSha) {
                    // The pipeline of the remote commit is final; a new one comes with a new push.
                    log.info("Coverage: pipeline poll — nothing to wait for on '$branch', stopping")
                    stop()
                } else {
                    scheduleNext()
                }
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
