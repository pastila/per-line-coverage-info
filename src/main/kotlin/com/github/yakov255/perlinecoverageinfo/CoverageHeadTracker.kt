package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Listens for any Git repository change (HEAD move, branch switch, pull, rebase, etc.)
 * and triggers offline-first coverage reload when the HEAD revision changes.
 *
 * Replaces [CoverageBranchListener] which only fired on branch switch operations.
 * Uses a 2-second debounce to avoid redundant reloads during rapid changes
 * (e.g. interactive rebase touching many commits).
 */
class CoverageHeadTracker(private val project: Project) : GitRepositoryChangeListener, Disposable {

    private val log = CoverageLog.get(CoverageHeadTracker::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    @Volatile
    private var lastKnownRevision: String? = null

    override fun repositoryChanged(repository: GitRepository) {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: HEAD change ignored — gutter visibility is off")
            return
        }

        val currentRevision = repository.currentRevision ?: return
        val previous = lastKnownRevision

        if (previous == currentRevision) return

        lastKnownRevision = currentRevision
        log.info("Coverage: HEAD changed ${previous?.take(8) ?: "null"} → ${currentRevision.take(8)}, scheduling reload")

        CoveragePipelinePoller.getInstance(project).stop()

        alarm.cancelAllRequests()
        alarm.addRequest({
            if (!project.isDisposed) {
                val dataService = CoverageDataService.getInstance(project)
                val revision = lastKnownRevision ?: return@addRequest

                if (dataService.coverageCommitHash == revision && dataService.hasData() && !dataService.isStale) {
                    log.info("Coverage: HEAD unchanged after debounce — commit $revision already loaded, skipping reload")
                    return@addRequest
                }

                val loadService = CoverageLoadService.getInstance(project)
                if (loadService.validateSettings() == null) {
                    loadService.loadOfflineFirst()
                }
            }
        }, DEBOUNCE_MS)
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }

    companion object {
        private const val DEBOUNCE_MS = 2000
    }
}
