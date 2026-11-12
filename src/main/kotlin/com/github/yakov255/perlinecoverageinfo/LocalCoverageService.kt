package com.github.yakov255.perlinecoverageinfo

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Holds coverage from local Behat runs and merges it over CI coverage (see [LocalCoverageMerge]).
 *
 * Runs come from `.covt` files — loaded manually ([loadInBackground]) or picked up automatically
 * from [CoverageApiSettings.localCoverageDir] under the git root via the IDE file watcher.
 */
@Service(Service.Level.PROJECT)
class LocalCoverageService(private val project: Project) : Disposable {

    private val log = CoverageLog.get(LocalCoverageService::class.java)

    private val layer = LocalCoverageLayer()
    private val version = java.util.concurrent.atomic.AtomicLong()
    val coverageGeneration: Long get() = version.get()
    private var generation = 0L
    private var disposed = false

    @Volatile
    private var gitRoot: File? = null

    /** HEAD the local runs are compared against; runs of another commit are stale. */
    @Volatile
    private var headCommit: String? = null

    private var watchRequest: LocalFileSystem.WatchRequest? = null
    private var connection: MessageBusConnection? = null

    /** Stamp of the last loaded version of each watched file. */
    private val loaded = HashMap<String, FileStamp>()

    /** Whether any local run is loaded, stale ones included (they can still be restored). */
    fun hasData(): Boolean = !layer.isEmpty()

    /** Whether any run is merged over CI coverage right now. */
    fun hasActiveData(): Boolean = layer.activeRuns(headCommit).isNotEmpty()

    /** Runs collected on another commit — excluded from the merge until [restoreStale]. */
    fun staleRunCount(): Int = layer.staleRuns(headCommit).size

    /** Git root of the project, resolved on first use. */
    fun gitRootOrNull(): File? = gitRoot ?: findGitRoot()?.also { gitRoot = it }

    /** Tests executed locally on the current HEAD — they supersede their CI coverage. */
    fun tests(): Set<String> = layer.activeTests(headCommit)

    /** Number of loaded local runs, stale ones included. */
    fun runCount(): Int = layer.runs().size

    /** Number of distinct files touched by the runs of the current HEAD. */
    fun fileCount(): Int = layer.activeFileCount(headCommit)

    /** Absolute path of the directory watched for `.covt` files, or null outside a git repo. */
    fun watchedDirectory(): String? = gitRootOrNull()?.let { watchDir(it).path }

    /**
     * Local coverage for a file, mapped onto [currentContent]. Each run is mapped from its own
     * snapshot, so coverage stays aligned while the file is edited after the run.
     */
    fun getMappedCoverage(absolutePath: String, currentContent: String): Map<Int, List<String>>? {
        return layer.withActiveRuns(headCommit) { runs ->
            if (runs.isEmpty()) return@withActiveRuns null
            val root = gitRoot ?: return@withActiveRuns null
            val relativePath = toGitRelative(absolutePath, root) ?: return@withActiveRuns null

            val perRun = runs.mapNotNull { run ->
                CoverageCancellation.check()
                val lines = run.coverage[relativePath] ?: return@mapNotNull null
                val snapshot = run.snapshots[relativePath] ?: return@mapNotNull lines
                val mapping = CoverageLineMapper.computeMappingFromContent(snapshot, currentContent)
                    ?: return@mapNotNull lines
                CoverageLineMapper.mapCoverage(lines, mapping)
            }
            LocalCoverageMerge.unionRuns(perRun)
        }
    }

    /** Loads a `.covt` / `.covt.gz` chosen by the user, with a progress indicator. */
    fun loadInBackground(file: File) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading local coverage", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Reading ${file.name}…"
                try {
                    load(file)
                } catch (ex: Exception) {
                    log.warn("LocalCoverage: failed to load ${file.path}", ex)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to parse coverage file:\n${ex.message}", "Coverage Error")
                    }
                }
            }
        })
    }

    /**
     * Called on every HEAD move. Runs collected on another commit stop being merged: their file
     * snapshots and the CI coverage they supersede belong to the previous checkout. They are kept
     * and can be brought back with [restoreStale] (the button in the Covering Line toolbar).
     */
    fun onHeadChanged(revision: String?) {
        if (revision == null || revision == headCommit) return
        val previouslyActive = layer.activeRuns(headCommit).size
        headCommit = revision
        version.incrementAndGet()
        val stale = layer.staleRuns(revision).size
        if (stale == 0) return
        log.info("LocalCoverage: HEAD is now ${revision.take(8)} — $stale run(s) marked stale (was $previouslyActive active)")
        refreshEditors()
        notify(
            "Local coverage is stale",
            "$stale local run(s) were collected on another commit and are no longer merged over CI coverage. " +
                "Restore them from the Covering Line toolbar, or run the scenarios again.",
            NotificationType.WARNING,
        )
    }

    /** Re-stamps stale runs onto the current HEAD, putting them back into the merge. */
    fun restoreStale() {
        val restored = layer.staleRuns(headCommit).size
        if (restored == 0) return
        log.info("LocalCoverage: restoring $restored stale run(s) onto ${headCommit?.take(8)}")
        layer.restampTo(headCommit)
        version.incrementAndGet()
        refreshEditors()
    }

    fun clear() {
        log.info("LocalCoverage: clearing ${layer.runs().size} run(s)")
        synchronized(this) {
            generation++
            layer.clear()
            version.incrementAndGet()
        }
        refreshEditors()
    }

    /** Drops the highlighters of every editor and re-applies them from the current data. */
    private fun refreshEditors() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            for (editor in EditorFactory.getInstance().allEditors) {
                if (editor.project == project) CoverageHighlighter.clearCoverageHighlighters(editor)
            }
            CoverageHighlighter.applyToOpenEditors(project)
        }
    }

    /**
     * Starts watching the local coverage directory through the IDE file watcher.
     * Only files written after this call are loaded: older ones come from earlier sessions and
     * the file contents their line numbers refer to are unknown.
     */
    fun startWatching() {
        synchronized(this) {
            if (watchRequest != null) return
            val root = findGitRoot() ?: run {
                log.info("LocalCoverage: no git root for ${project.basePath}, not watching")
                return
            }
            gitRoot = root
            if (connection == null) {
                connection = project.messageBus.connect(this).also {
                    it.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                        override fun after(events: List<VFileEvent>) = onVfsEvents(events)
                    })
                }
            }
            val dir = watchDir(root)
            watchRequest = LocalFileSystem.getInstance().addRootToWatch(dir.path, false)
            loadVfsChildren(dir.parentFile)
            loadVfsChildren(dir)
            log.info("LocalCoverage: watching $dir")
        }
    }

    /** Re-registers the watch after [CoverageApiSettings.localCoverageDir] changed. */
    fun restartWatching() {
        synchronized(this) {
            watchRequest?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
            watchRequest = null
        }
        startWatching()
    }

    override fun dispose() {
        synchronized(loaded) { loaded.clear() }
        synchronized(this) {
            disposed = true
            generation++
            layer.clear()
            version.incrementAndGet()
            watchRequest?.let { LocalFileSystem.getInstance().removeWatchedRoot(it) }
            watchRequest = null
        }
    }

    private fun onVfsEvents(events: List<VFileEvent>) {
        val root = gitRoot ?: return
        val dir = watchDir(root).path
        for (event in events) {
            val path = event.path
            if (path == dir && event is VFileCreateEvent) {
                // The directory appeared (Behat creates it) — load it so events for its files arrive.
                // Listeners run inside a write action, where a synchronous refresh is not allowed.
                ApplicationManager.getApplication().executeOnPooledThread { loadVfsChildren(File(dir)) }
                continue
            }
            // Behat writes the report in place, so creation and content change cover every run.
            val touchesFile = event is VFileCreateEvent || event is VFileContentChangeEvent
            if (touchesFile && File(path).parent == dir && isCovt(path)) {
                awaitStableThenLoad(File(path), previous = null)
            }
        }
    }

    /**
     * The watcher can report a file while it is still being written, so a file is loaded only
     * once its size and mtime stay the same for [SETTLE_MS]. Several events for the same write
     * collapse into one load through [loaded].
     */
    private fun awaitStableThenLoad(file: File, previous: FileStamp?) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(Runnable {
            if (project.isDisposed) return@Runnable
            val stamp = FileStamp.of(file) ?: return@Runnable
            if (stamp != previous) {
                awaitStableThenLoad(file, stamp)
                return@Runnable
            }
            synchronized(loaded) {
                if (loaded[file.path] == stamp) return@Runnable
                loaded[file.path] = stamp
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    load(file)
                } catch (ex: Exception) {
                    log.warn("LocalCoverage: failed to load ${file.path}", ex)
                    notify("Local coverage not loaded", "${file.name}: ${ex.message}", NotificationType.WARNING)
                }
            }
        }, SETTLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun loadVfsChildren(dir: File?) {
        if (dir == null) return
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)?.children
    }

    private fun load(file: File) {
        val loadGeneration = synchronized(this) {
            if (disposed || project.isDisposed) return
            generation
        }
        val root = gitRoot ?: findGitRoot()?.also { gitRoot = it }
            ?: throw CoverageApiException("Project is not inside a git repository", kind = CoverageErrorKind.GIT)

        val covt = CoveragePathNormalizer.normalizeRaketaRun(
            BinaryCoverageParser.parsePossiblyGzippedCovt(file.readBytes()), root,
        )

        if (covt.tests.isEmpty()) {
            log.info("LocalCoverage: ${file.name} contains no executed tests, skipping")
            return
        }

        val coverage = LinkedHashMap<String, Map<Int, List<String>>>(covt.coverage.size)
        var skipped = 0
        for ((path, lines) in covt.coverage) {
            val relativePath = LocalCoveragePaths.normalize(path) { File(root, it).isFile }
            if (relativePath == null) {
                skipped++
                continue
            }
            coverage[relativePath] = lines
        }
        if (skipped > 0) {
            log.warn("LocalCoverage: $skipped file(s) from ${file.name} not found under $root")
        }

        // Keep source text on disk and read one file at a time. The run executed the on-disk
        // working tree, not unsaved editor text; future lookups use this immutable snapshot.
        val run = LocalCoverageArchive.store(covt.tests.toSet(), coverage, commit = null) { path ->
            File(root, path).readText()
        }
        val head = resolveHead(root) ?: headCommit
        synchronized(this) {
            if (disposed || project.isDisposed || generation != loadGeneration) {
                run.close()
                return
            }
            headCommit = head
            layer.add(run.restampedTo(head))
            version.incrementAndGet()
        }
        log.info("LocalCoverage: loaded ${file.path} — ${covt.tests.size} test(s), ${coverage.size} file(s) at ${head?.take(8) ?: "unknown commit"}; layer now has ${layer.runs().size} run(s)")

        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) CoverageHighlighter.applyToOpenEditors(project)
        }
        notify(
            "Local coverage loaded",
            "${covt.tests.size} scenario(s), ${coverage.size} file(s) from ${file.name}. " +
                "Locally run scenarios replace their CI coverage.",
            NotificationType.INFORMATION,
        )
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Coverage Notifications")
            .createNotification(title, content, type)
            .notify(project)
    }

    private fun watchDir(root: File): File = File(root, CoverageApiSettings.getInstance().localCoverageDir)

    private fun isCovt(path: String): Boolean = path.endsWith(".covt") || path.endsWith(".covt.gz")

    private fun resolveHead(root: File): String? =
        GitCommand.run(root, "rev-parse", "HEAD")?.trim()?.takeIf { it.isNotEmpty() }

    private fun findGitRoot(): File? {
        val basePath = project.basePath ?: return null
        val path = GitCommand.run(File(basePath), "rev-parse", "--show-toplevel") ?: return null
        return File(path.trim())
    }

    private fun toGitRelative(absolutePath: String, root: File): String? = try {
        Paths.get(root.path).relativize(Paths.get(absolutePath)).toString()
            .replace(File.separatorChar, '/')
            .takeUnless { it.startsWith("..") }
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class FileStamp(val modified: Long, val size: Long) {
        companion object {
            fun of(file: File): FileStamp? = if (file.isFile) FileStamp(file.lastModified(), file.length()) else null
        }
    }

    companion object {
        private const val SETTLE_MS = 500L

        fun getInstance(project: Project): LocalCoverageService = project.service()
    }
}
