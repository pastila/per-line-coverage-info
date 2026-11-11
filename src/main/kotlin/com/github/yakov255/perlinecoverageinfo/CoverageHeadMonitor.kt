package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.io.File

/**
 * HEAD bookkeeping for one project: which repository the coverage belongs to, the last revision
 * seen, and the debounced reload after a change.
 *
 * [CoverageHeadTracker] (the Git4Idea listener) reports repository changes here and
 * [CoveragePipelinePoller] asks [checkHeadFromPoller] on every tick. One project-level instance
 * means both entry points share one "last revision", so a change caught by the poller is not
 * reloaded again when Git4Idea reports the same change a moment later.
 */
@Service(Service.Level.PROJECT)
class CoverageHeadMonitor(private val project: Project) {

    private val log = CoverageLog.get(CoverageHeadMonitor::class.java)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, project)
    private val revisions = HeadRevisionTracker()

    /**
     * Test seam: replaces the debounced reload and is called right away instead.
     * Production code never sets it.
     */
    @Volatile
    internal var reloadForTest: (() -> Unit)? = null

    /** Called by [CoverageHeadTracker] for every repository change Git4Idea reports. */
    fun onRepositoryChanged(repository: GitRepository) {
        if (!CoverageGutterVisibilityService.getInstance(project).visible) {
            log.info("Coverage: HEAD change ignored — gutter visibility is off")
            return
        }

        val coverageRoot = coverageGitRoot()
        val repositoryRoot = File(repository.root.path)
        if (!CoverageRepositoryFilter.accepts(repositoryRoot, coverageRoot)) {
            log.info("Coverage: HEAD change in ${repositoryRoot.path} ignored — coverage belongs to ${coverageRoot?.path}")
            return
        }

        val currentRevision = repository.currentRevision
        val previousRevision = revisions.lastKnown
        when (revisions.observe(currentRevision)) {
            HeadChange.NO_HEAD, HeadChange.UNCHANGED -> return
            HeadChange.FIRST -> {
                log.info("Coverage: initial HEAD = ${currentRevision?.take(8)} — skipped (CoverageStartupActivity handles the initial load)")
                return
            }
            HeadChange.CHANGED -> Unit
        }

        log.info("Coverage: HEAD changed ${previousRevision?.take(8)} → ${currentRevision?.take(8)}, scheduling reload")
        onHeadMoved(currentRevision)
    }

    /**
     * Poller hook: reads HEAD from git and compares it with the last revision this monitor saw.
     *
     * Git4Idea can notice an external checkout 12-14 seconds late in a linked worktree, so the
     * poller is the faster path for a commit made outside the IDE. Runs `git rev-parse`, so the
     * caller must be on a pooled thread; the poller tick already is.
     *
     * @return true when the HEAD moved, which means the caller must not start its own load.
     */
    fun checkHeadFromPoller(): Boolean {
        if (project.isDisposed) return false
        if (!CoverageGutterVisibilityService.getInstance(project).visible) return false

        val root = coverageGitRoot() ?: return false
        val head = GitCommand.run(root, "rev-parse", "HEAD") ?: return false

        val previousRevision = revisions.lastKnown
        if (revisions.observe(head) != HeadChange.CHANGED) return false

        log.info("Coverage: poller noticed HEAD ${previousRevision?.take(8)} → ${head.take(8)} before Git4Idea reported it")
        onHeadMoved(head)
        return true
    }

    private fun onHeadMoved(head: String?) {
        // A block or a retry budget belongs to the old commit.
        CoverageLoadService.getInstance(project).onHeadChanged(head)

        CoveragePipelinePoller.getInstance(project).stop()
        scheduleReload()
    }

    private fun scheduleReload() {
        val testReload = reloadForTest
        if (testReload != null) {
            testReload()
            return
        }

        alarm.cancelAllRequests()
        alarm.addRequest({
            if (project.isDisposed) return@addRequest
            val dataService = CoverageDataService.getInstance(project)
            val revision = revisions.lastKnown ?: return@addRequest

            if (dataService.coverageCommitHash == revision && dataService.hasData() && !dataService.isStale) {
                log.info("Coverage: HEAD unchanged after debounce — commit $revision already loaded, skipping reload")
                return@addRequest
            }

            val loadService = CoverageLoadService.getInstance(project)
            if (loadService.validateSettings() == null) {
                loadService.loadOfflineFirst()
            }
        }, DEBOUNCE_MS)
    }

    /**
     * The repository the coverage belongs to: the git root of the loaded coverage, else the
     * project's own repository — the deepest one that contains the project base path, so a
     * submodule project does not follow the outer repository.
     */
    private fun coverageGitRoot(): File? {
        CoverageDataService.getInstance(project).gitRoot?.let { return it }
        val basePath = project.basePath ?: return null
        return GitRepositoryManager.getInstance(project).repositories
            .map { File(it.root.path) }
            .filter { repositoryRoot -> contains(basePath, repositoryRoot) }
            .maxByOrNull { it.path.length }
    }

    private fun contains(path: String, root: File): Boolean {
        val rootPath = root.path.trimEnd(File.separatorChar)
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    companion object {
        private const val DEBOUNCE_MS = 2000L

        fun getInstance(project: Project): CoverageHeadMonitor = project.service()
    }
}
