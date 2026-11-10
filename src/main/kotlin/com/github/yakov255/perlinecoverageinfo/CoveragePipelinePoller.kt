package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Periodic refresh tick.
 *
 * The covd daemon resolves and memorizes the pipeline itself, so polling is just "ask again
 * in 30 seconds": `ensure` answers from its TTL cache and only downloads when something new
 * appeared. This class therefore contains no GitLab logic at all.
 */
@Service(Service.Level.PROJECT)
class CoveragePipelinePoller(private val project: Project) : Disposable {

    private val log = CoverageLog.get(CoveragePipelinePoller::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var started = false

    fun start() {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: poll skipped — gutter visibility off")
            return
        }
        if (started) return
        started = true
        scheduleNext()
    }

    fun stop() {
        alarm.cancelAllRequests()
        started = false
    }

    override fun dispose() = stop()

    private fun scheduleNext() {
        if (!started || project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ onPoll() }, POLL_INTERVAL_MS)
    }

    private fun onPoll() {
        if (project.isDisposed || !started) return

        val loadService = CoverageLoadService.getInstance(project)
        if (!CoverageGutterVisibilityService.getInstance(project).visible || !CoverageApiSettings.getInstance().enabled) {
            stop()
            return
        }
        if (loadService.isLoadInProgress()) {
            scheduleNext()
            return
        }
        if (loadService.isBlocked()) {
            // The token is invalid or the error is deterministic for this HEAD: waiting is
            // cheaper and quieter than asking GitLab again every 30 seconds.
            log.info("Coverage: poll skipped — load is blocked (${loadService.blockedReason()})")
            scheduleNext()
            return
        }

        CoveragePerfLog.event("poll.tick")
        loadService.loadFromGitLab(showErrors = false)
        scheduleNext()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L

        fun getInstance(project: Project): CoveragePipelinePoller = project.service()
    }
}
