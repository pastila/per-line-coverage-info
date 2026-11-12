package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Checks local git refs every 30 seconds so external checkouts and push/fetch are noticed.
 * The load service asks covd only while waiting for coverage or after those refs change.
 */
@Service(Service.Level.PROJECT)
class CoveragePipelinePoller(private val project: Project) : Disposable {

    private val log = CoverageLog.get(CoveragePipelinePoller::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var started = false
    @Volatile
    private var disposed = false

    fun start() {
        if (disposed || project.isDisposed) return
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

    override fun dispose() {
        disposed = true
        stop()
    }

    private fun scheduleNext() {
        if (disposed || !started || project.isDisposed) return
        alarm.cancelAllRequests()
        alarm.addRequest({ onPoll() }, POLL_INTERVAL_MS)
    }

    private fun onPoll() {
        if (disposed || project.isDisposed || !started) return

        val loadService = CoverageLoadService.getInstance(project)
        if (!CoverageGutterVisibilityService.getInstance(project).visible || !CoverageApiSettings.getInstance().enabled) {
            stop()
            return
        }
        if (loadService.isLoadInProgress()) {
            scheduleNext()
            return
        }
        if (CoverageHeadMonitor.getInstance(project).checkHeadFromPoller()) {
            scheduleNext()
            return
        }
        if (disposed || project.isDisposed) return
        if (loadService.isPinned()) {
            log.info("Coverage: poll skipped — a pinned artifact is shown")
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

        loadService.pollForUpdates()
        scheduleNext()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 30_000L

        fun getInstance(project: Project): CoveragePipelinePoller = project.service()
    }
}
