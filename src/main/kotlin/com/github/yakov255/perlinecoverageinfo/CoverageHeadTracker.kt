package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for any Git repository change (HEAD move, branch switch, pull, rebase, etc.)
 * and triggers offline-first coverage reload when the HEAD revision changes.
 * When only the remote-tracking ref of the current branch moves (push, fetch), it
 * restarts [CoveragePipelinePoller], which stops itself while nothing is pending.
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

    /** Last seen hash of `origin/<current branch>` per repository root; "" = branch not pushed. */
    private val lastKnownRemote = ConcurrentHashMap<String, String>()

    override fun repositoryChanged(repository: GitRepository) {
        val currentRevision = repository.currentRevision ?: return

        // Local runs belong to the commit they were collected on. Flag them before the visibility
        // gate, so a hidden gutter cannot bring stale local coverage back when it is shown again.
        LocalCoverageService.getInstance(project).onHeadChanged(currentRevision)

        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: HEAD change ignored — gutter visibility is off")
            return
        }

        val previous = lastKnownRevision

        if (previous == currentRevision) {
            if (remoteRefMoved(repository)) {
                log.info("Coverage: origin/${repository.currentBranchName} moved, resuming pipeline polling")
                CoveragePipelinePoller.getInstance(project).start()
            }
            return
        }
        remoteRefMoved(repository)

        lastKnownRevision = currentRevision

        if (previous == null) {
            log.info("Coverage: initial HEAD = ${currentRevision.take(8)} — skipped (CoverageStartupActivity handles initial load)")
            return
        }

        log.info("Coverage: HEAD changed ${previous.take(8)} → ${currentRevision.take(8)}, scheduling reload")

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

    /** Records the hash of `origin/<current branch>`; true when it changed since the last event. */
    private fun remoteRefMoved(repository: GitRepository): Boolean {
        val branch = repository.currentBranchName ?: return false
        val branches = repository.branches
        val hash = branches.remoteBranches.firstOrNull { it.name == "origin/$branch" }
            ?.let { branches.getHash(it)?.asString() } ?: ""
        val key = repository.root.path
        val previous = lastKnownRemote.put(key, hash)
        return previous != null && previous != hash && hash.isNotEmpty()
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }

    companion object {
        private const val DEBOUNCE_MS = 2000
    }
}
