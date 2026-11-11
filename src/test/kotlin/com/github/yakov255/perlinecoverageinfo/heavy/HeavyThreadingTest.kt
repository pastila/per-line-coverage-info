package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.ArtifactInfo
import com.github.yakov255.perlinecoverageinfo.Cov4Reader
import com.github.yakov255.perlinecoverageinfo.Cov4TestWriter
import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.CoverageArtifactsPanel
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageEditorListener
import com.github.yakov255.perlinecoverageinfo.CoverageGutterVisibilityService
import com.github.yakov255.perlinecoverageinfo.CoverageHeadMonitor
import com.github.yakov255.perlinecoverageinfo.CoverageHighlighter
import com.github.yakov255.perlinecoverageinfo.CoveragePipelinePoller
import com.github.yakov255.perlinecoverageinfo.CoverageStartupActivity
import com.github.yakov255.perlinecoverageinfo.ShowCoverageGutterAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Threading contract (package 3) inside a real IDE: the artifacts panel and the gutter action do
 * their disk and daemon work off the EDT, the first-run token dialog opens once per IDE run, and
 * the poller catches an external HEAD change that Git4Idea has not reported yet.
 *
 * No daemon and no GitLab are needed: the tests replace the slow parts through the documented
 * test seams of the panels, the action and the startup activity.
 */
class HeavyThreadingTest : BasePlatformTestCase() {

    private var savedSettings: CoverageApiSettings.State? = null
    private val tempDirs = mutableListOf<File>()
    private lateinit var cacheDir: File

    override fun setUp() {
        super.setUp()
        cacheDir = Files.createTempDirectory("heavy-threading-cache-").toFile()
        CoverageCacheService.cacheRootOverride = cacheDir
        val settings = CoverageApiSettings.getInstance()
        savedSettings = settings.state.copy()
        settings.gitlabDomain = "http://127.0.0.1:1"
        settings.bearerToken = "heavy-threading-token"
        settings.gitlabProjectId = 1
        settings.gitlabProjectName = "group/project"
        settings.coverageBranch = ""
        settings.enabled = true
        CoverageGutterVisibilityService.getInstance(project).visible = true
        CoverageDataService.getInstance(project).clear()
        CoverageStartupActivity.resetForTest()
    }

    override fun tearDown() {
        try {
            ShowCoverageGutterAction.backgroundRunnerForTest = null
            CoverageStartupActivity.resetForTest()
            CoverageHeadMonitor.getInstance(project).reloadForTest = null
            CoveragePipelinePoller.getInstance(project).stop()
            CoverageDataService.getInstance(project).clear()
            savedSettings?.let { CoverageApiSettings.getInstance().loadState(it) }
            CoverageCacheService.cacheRootOverride = null
            tempDirs.forEach { it.deleteRecursively() }
            tempDirs.clear()
            cacheDir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    // --- H6: the artifacts panel ------------------------------------------------------

    fun testArtifactsRefreshReadsOffEdtAndRendersOnEdt() {
        val panel = CoverageArtifactsPanel(project)
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val readOnEdt = AtomicBoolean(true)
        panel.artifactsLoader = {
            readOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
            readStarted.countDown()
            releaseRead.await(15, TimeUnit.SECONDS)
            listOf(artifact("aaaa1111"), artifact("bbbb2222"))
        }

        try {
            onEdt { panel.refreshData() }

            assertTrue("the listing must start on a pooled thread", readStarted.await(10, TimeUnit.SECONDS))
            assertFalse("the cache index must not be read on the EDT", readOnEdt.get())
            assertEquals("nothing may be rendered while the listing is still running", 0, panel.rowCountForTest())
            assertTrue("the empty-state card stays until the listing arrives", panel.showsEmptyStateForTest())

            releaseRead.countDown()

            waitUntil("the refreshed listing reaches the table on the EDT") {
                panel.rowCountForTest() == 2 && !panel.showsEmptyStateForTest()
            }
        } finally {
            releaseRead.countDown()
            Disposer.dispose(panel)
        }
    }

    fun testDeleteArtifactRunsOffEdtAndRefreshesTheTable() {
        val entry = artifact("cccc3333")
        val panel = CoverageArtifactsPanel(project)
        panel.artifactsLoader = { listOf(entry) }

        val deleteStarted = CountDownLatch(1)
        val releaseDelete = CountDownLatch(1)
        val deleteOnEdt = AtomicBoolean(true)

        try {
            onEdt { panel.refreshData() }
            waitUntil("the artifact is shown before the deletion") { panel.rowCountForTest() == 1 }

            panel.artifactDeleter = {
                deleteOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
                deleteStarted.countDown()
                releaseDelete.await(15, TimeUnit.SECONDS)
                true
            }
            panel.artifactsLoader = { emptyList() }

            onEdt { panel.deleteArtifactForTest(entry) }

            assertTrue("the daemon call must start on a pooled thread", deleteStarted.await(10, TimeUnit.SECONDS))
            assertFalse("CovdClient.deleteArtifact must not run on the EDT", deleteOnEdt.get())

            releaseDelete.countDown()

            waitUntil("the table is refreshed after the deletion") {
                panel.rowCountForTest() == 0 && panel.showsEmptyStateForTest()
            }
        } finally {
            releaseDelete.countDown()
            Disposer.dispose(panel)
        }
    }

    // --- H7: the gutter actions -------------------------------------------------------

    fun testShowGutterActionHandsTheLoadToABackgroundThread() {
        CoverageGutterVisibilityService.getInstance(project).visible = false
        var runnerProject: Any? = null
        var deferredLoad: (() -> Unit)? = null
        ShowCoverageGutterAction.backgroundRunnerForTest = { targetProject, load ->
            runnerProject = targetProject
            deferredLoad = load
        }

        val action = ShowCoverageGutterAction()
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

        onEdt { action.actionPerformed(event) }

        assertTrue("the gutter must be visible again", CoverageGutterVisibilityService.getInstance(project).visible)
        assertEquals("the load is dispatched for this project", project, runnerProject)
        assertNotNull("loadOfflineFirst must not run on the EDT", deferredLoad)
        onEdt { action.update(event) }
        assertFalse("the action is disabled while the gutter is visible", event.presentation.isEnabledAndVisible)
    }

    // --- M14: the first-run token dialog ----------------------------------------------

    fun testFirstRunTokenDialogOpensOnceForTwoProjectStarts() {
        val settings = CoverageApiSettings.getInstance()
        settings.bearerToken = ""
        settings.tokenPrompted = false
        CoverageStartupActivity.resetForTest()

        val prompts = AtomicInteger()
        val promptOnEdt = AtomicBoolean(false)
        CoverageStartupActivity.tokenPromptForTest = {
            prompts.incrementAndGet()
            promptOnEdt.set(ApplicationManager.getApplication().isDispatchThread)
            null
        }

        val activity = CoverageStartupActivity()
        val done = AtomicInteger()
        // Two projects starting together; the second one must not open another dialog.
        onEdt {
            activity.promptForTokenIfNeeded(project) { done.incrementAndGet() }
            activity.promptForTokenIfNeeded(project) { done.incrementAndGet() }
        }

        assertEquals("both starts must continue after the prompt", 2, done.get())

        assertEquals("the dialog must open once per IDE run", 1, prompts.get())
        assertTrue("the dialog must run on the EDT", promptOnEdt.get())
        assertTrue("the first-run prompt is remembered", settings.tokenPrompted)
    }

    // --- M10: the poller catches an external HEAD change ------------------------------

    fun testPollerCatchesExternalHeadChangeWithoutGit4IdeaEvent() {
        val repo = Files.createTempDirectory("heavy-head-").toFile()
        tempDirs += repo
        git(repo, "init", "-q")
        git(repo, "config", "user.email", "heavy@example.com")
        git(repo, "config", "user.name", "Heavy Test")
        File(repo, "README.md").writeText("initial\n")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", "initial")

        CoverageDataService.getInstance(project).setCoverageContext(git(repo, "rev-parse", "HEAD"), repo)

        val monitor = CoverageHeadMonitor.getInstance(project)
        val reloads = AtomicInteger()
        monitor.reloadForTest = { reloads.incrementAndGet() }

        assertFalse("the first observation only seeds the monitor", monitor.checkHeadFromPoller())
        assertEquals(0, reloads.get())

        git(repo, "commit", "-q", "--allow-empty", "-m", "checked out outside the IDE")

        assertTrue("the poller must notice the new HEAD", monitor.checkHeadFromPoller())
        assertEquals("the new HEAD is loaded once", 1, reloads.get())
        assertFalse("the same HEAD reported again is not a change", monitor.checkHeadFromPoller())
        assertEquals(1, reloads.get())
    }

    // --- M9: an editor restored before the first load ----------------------------------

    fun testEditorOpenedBeforeCoverageRehighlightsAfterALateLoad() {
        val root = File(project.basePath!!).apply { mkdirs() }
        val ioFile = File(root, "ThreadingSample.php").apply {
            writeText("<?php\n\$a = 1;\n\$b = 2;\n")
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: error("cannot create ${ioFile.path}")

        myFixture.openFileInEditor(virtualFile)
        val editor = myFixture.editor
        assertTrue(
            "the document listener must be installed even before coverage is loaded",
            CoverageEditorListener.isListening(editor),
        )

        val cov4 = Files.createTempFile("heavy-threading", ".cov4").toFile()
        tempDirs += cov4
        Cov4TestWriter.write(
            mapOf(
                "ThreadingSample.php" to mapOf(
                    1 to listOf("SampleTest::testBoot"),
                    2 to listOf("SampleTest::testBoot"),
                ),
            ),
            cov4,
        )
        val dataService = CoverageDataService.getInstance(project)
        dataService.setCoverageContext(FIXTURE_COMMIT, root)
        dataService.setCov4Reader(Cov4Reader(cov4))

        assertFalse(
            "coverage must not be highlighted before the document changes",
            hasCoverageHighlighter(editor),
        )

        onEdt { myFixture.type("\n") }

        waitUntil("the editor restored before the load re-highlights after an edit") {
            hasCoverageHighlighter(editor)
        }
    }

    // --- helpers ----------------------------------------------------------------------

    private fun hasCoverageHighlighter(editor: com.intellij.openapi.editor.Editor): Boolean =
        editor.markupModel.allHighlighters.any {
            it.getUserData(CoverageHighlighter.COVERAGE_HIGHLIGHTER_KEY) == true
        }

    private fun artifact(commitHash: String): ArtifactInfo = ArtifactInfo(
        commitHash = commitHash,
        pipelineId = 42,
        timestampMs = System.currentTimeMillis(),
        fileSizeBytes = 1_048_576,
        totalFiles = 10,
        totalLines = 100,
        coveredLines = 80,
        lastUsedMs = null,
        component = "raketa",
        branch = "master",
    )

    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder("git", *args)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals("git ${args.joinToString(" ")} failed:\n$output", 0, process.waitFor())
        return output
    }

    /** Runs [block] on the EDT; the test thread may be the EDT already. */
    private fun onEdt(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            block()
        } else {
            application.invokeAndWait(block)
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertTrue(message, condition())
    }

    companion object {
        private const val FIXTURE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
