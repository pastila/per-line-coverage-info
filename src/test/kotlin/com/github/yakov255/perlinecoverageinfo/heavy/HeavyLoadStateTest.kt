package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.ClearCoverageAction
import com.github.yakov255.perlinecoverageinfo.Cov4Reader
import com.github.yakov255.perlinecoverageinfo.Cov4TestWriter
import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageGutterVisibilityService
import com.github.yakov255.perlinecoverageinfo.CoverageHeadMonitor
import com.github.yakov255.perlinecoverageinfo.CoverageLoadService
import com.github.yakov255.perlinecoverageinfo.CoverageNotifier
import com.github.yakov255.perlinecoverageinfo.CoveragePipelinePoller
import com.github.yakov255.perlinecoverageinfo.CoverageUserSelectionService
import com.github.yakov255.perlinecoverageinfo.LoadCoverageAction
import com.github.yakov255.perlinecoverageinfo.covd.CovdTestBase
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Load-state contract (package 2) inside a real IDE: a stale answer after a HEAD change, the pin
 * versus automatic loads, "Clear Coverage Data", and the atomic [CoverageDataService] snapshot.
 *
 * The daemon is a fake behind a shell wrapper (like `HeavyCovdFailureTest`), so no `covd`
 * binary and no GitLab are needed: each test scripts the `ensure`/`import` answers.
 */
class HeavyLoadStateTest : BasePlatformTestCase() {

    private lateinit var cacheDir: File
    private lateinit var repo: File
    private lateinit var daemon: FakeDaemon
    private var savedSettings: CoverageApiSettings.State? = null

    override fun setUp() {
        super.setUp()
        cacheDir = Files.createTempDirectory("heavy-loadstate-").toFile()
        daemon = FakeDaemon().also { it.start() }
        val script = File(cacheDir, "fake-covd.sh")
        script.writeText(
            """
            #!/bin/sh
            printf '{"covdReady":{"port":${daemon.port},"token":"t","instanceId":"fake-loadstate"}}\n'
            exec sleep 3600
            """.trimIndent() + "\n",
        )
        script.setExecutable(true)
        CovdClient.resetForTest()
        CovdClient.binaryOverride = script
        CovdClient.cacheRootOverride = cacheDir
        CoverageCacheService.cacheRootOverride = cacheDir

        repo = prepareRepo()

        val settings = CoverageApiSettings.getInstance()
        savedSettings = settings.state.copy()
        settings.gitlabDomain = "http://127.0.0.1:1"
        settings.bearerToken = "token"
        settings.gitlabProjectId = 1
        settings.gitlabProjectName = "group/project"
        settings.coverageBranch = ""
        settings.enabled = true
        CoverageGutterVisibilityService.getInstance(project).visible = true
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = null
        CoverageDataService.getInstance(project).clear()
        loadService().onSettingsApplied()
        CoverageNotifier.getInstance(project).clearForTest()
        // Light fixtures reuse project services; seed this test's HEAD without queuing a reload.
        CoverageDataService.getInstance(project).setCoverageContext(head(), repo)
        val monitor = CoverageHeadMonitor.getInstance(project)
        monitor.reloadForTest = {}
        try {
            monitor.checkHeadFromPoller()
        } finally {
            monitor.reloadForTest = null
        }
        CoverageDataService.getInstance(project).clear()
    }

    override fun tearDown() {
        try {
            loadService().cancelRetriesForTest()
            daemon.releaseAll()
            CoveragePipelinePoller.getInstance(project).stop()
            CoverageUserSelectionService.getInstance(project).pinnedCommitHash = null
            CoverageDataService.getInstance(project).clear()
            savedSettings?.let { CoverageApiSettings.getInstance().loadState(it) }
            CoverageCacheService.cacheRootOverride = null
            CovdClient.resetForTest()
            daemon.stop()
            cacheDir.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    // --- H4: stale answer ------------------------------------------------------------

    fun testAnswerForOldHeadIsStaleAndTheNewHeadLoadsFresh() {
        val oldHead = head()
        val cov4Old = cov4("old.cov4", "src/Old.php")
        val cov4New = cov4("new.cov4", "src/New.php")
        val newHeadHolder = CompletableFuture<String>()
        // 1st ensure: held until HEAD moved, answers for the old commit.
        // 2nd ensure: held too, so the "stale" interval is observable; answers for the new one.
        daemon.ensureScript = { n ->
            if (n == 1) ready(oldHead, cov4Old) else ready(newHeadHolder.get(5, TimeUnit.SECONDS), cov4New)
        }
        daemon.holdEnsure(1)
        daemon.holdEnsure(2)

        loadService().loadFromGitLab(showErrors = false)
        waitUntil("the first ensure must reach the daemon") { daemon.ensureCount.get() >= 1 }

        CovdTestBase.git(repo, "commit", "--allow-empty", "-q", "-m", "next")
        val newHead = head()
        newHeadHolder.complete(newHead)
        daemon.release(1)

        val data = CoverageDataService.getInstance(project)
        waitUntil("the old answer must be shown") { data.coverageCommitHash == oldHead }
        assertTrue("an answer for an older HEAD is marked stale", data.isStale)
        waitUntil("a reload for the new HEAD must start by itself") { daemon.ensureCount.get() >= 2 }
        assertTrue("still stale until the new answer arrives", data.isStale)
        assertNull("no failure state for the old answer", loadService().blockedReason())

        daemon.release(2)
        waitUntil("the new HEAD must end up fresh") {
            data.coverageCommitHash == newHead && !data.isStale
        }
        assertTrue(data.allFiles().contains("src/New.php"))
    }

    // --- H3: pin ---------------------------------------------------------------------

    fun testPinBlocksPollerTickAndRetry() {
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = "pinned1234"

        pollerTick()
        loadService().loadFromGitLab(showErrors = false) // what the retry alarm and pending reload call
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        Thread.sleep(300)

        assertEquals("a pinned artifact must not reach the daemon", 0, daemon.ensureCount.get())
        assertFalse(loadService().isLoadInProgress())
        assertTrue(loadService().isPinned())
    }

    fun testLocalImportPinsTheImportedEntryAndPollerLeavesItAlone() {
        val cov4 = cov4("imported.cov4", "src/Imported.php")
        daemon.importBody =
            """{"state":"ready","commit":"$IMPORT_COMMIT","cov4Path":"${cov4.absolutePath}","stats":{"files":1,"lines":1,"covered":1}}"""
        val covt = File(repo, "local.covt").apply { writeBytes(byteArrayOf(1)) }

        loadService().loadFromLocalFile(covt)

        val data = CoverageDataService.getInstance(project)
        waitUntil("the import must be shown") { data.coverageCommitHash == IMPORT_COMMIT }
        assertEquals(IMPORT_COMMIT, CoverageUserSelectionService.getInstance(project).pinnedCommitHash)
        assertFalse(data.isStale)

        pollerTick()
        Thread.sleep(300)
        assertEquals("the poller must not replace an imported file", 0, daemon.ensureCount.get())
        assertEquals(IMPORT_COMMIT, data.coverageCommitHash)
    }

    fun testLoadCoverageActionLiftsThePinAndLoads() {
        val head = head()
        val cov4 = cov4("head.cov4", "src/Head.php")
        daemon.ensureScript = { ready(head, cov4) }
        val data = CoverageDataService.getInstance(project)
        data.setCoverage("pinned1234", repo, false, Cov4Reader(cov4("pinned.cov4", "src/Pinned.php")))
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = "pinned1234"

        perform(LoadCoverageAction())

        waitUntil("the manual load must replace the pinned data") { data.coverageCommitHash == head && !data.isStale }
        assertNull(CoverageUserSelectionService.getInstance(project).pinnedCommitHash)
        assertTrue(daemon.ensureCount.get() >= 1)
    }

    // --- M16: Clear ------------------------------------------------------------------

    fun testClearStopsPollerLiftsPinAndStaysClearedUntilALoad() {
        val head = head()
        val cov4 = cov4("head.cov4", "src/Head.php")
        daemon.ensureScript = { ready(head, cov4) }
        val data = CoverageDataService.getInstance(project)
        data.setCoverage("pinned1234", repo, false, Cov4Reader(cov4))
        CoverageUserSelectionService.getInstance(project).pinnedCommitHash = "pinned1234"
        CoveragePipelinePoller.getInstance(project).start()

        perform(ClearCoverageAction())

        assertFalse(data.hasData())
        assertNull(CoverageUserSelectionService.getInstance(project).pinnedCommitHash)
        assertFalse("the poller must be stopped", pollerStarted())
        Thread.sleep(300)
        assertEquals("nothing may bring the coverage back by itself", 0, daemon.ensureCount.get())
        assertFalse(data.hasData())

        // The next HEAD change / settings change / manual load goes through loadOfflineFirst.
        loadService().loadOfflineFirst()
        waitUntil("an explicit load must resume") { data.hasData() && data.coverageCommitHash == head }
    }

    // --- H5: atomic data service -----------------------------------------------------

    fun testSnapshotNeverMixesCommitAndReaderAndGenerationLosesNothing() {
        val data = CoverageDataService.getInstance(project)
        val writers = 4
        val rounds = 200
        val readers = (0 until writers).map { w ->
            Cov4Reader(cov4("w$w.cov4", "src/W$w.php"))
        }
        val before = data.coverageGeneration
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val mismatches = CopyOnWriteArrayList<String>()
        val clears = AtomicLong(0)

        val observer = Thread {
            while (!stop.get()) {
                val s = data.snapshot()
                val commit = s.commit
                val primary = s.primary
                if (commit != null && primary != null) {
                    val w = commit.removePrefix("c").toInt()
                    if (!primary.allFilePaths.contains("src/W$w.php")) {
                        mismatches += "commit $commit with reader of ${primary.allFilePaths}"
                    }
                }
            }
        }.apply { start() }

        val pool = Executors.newFixedThreadPool(writers)
        val gate = CountDownLatch(1)
        val done = (0 until writers).map { w ->
            pool.submit {
                gate.await()
                repeat(rounds) { i ->
                    if (i % 50 == 49) {
                        data.clear()
                        clears.incrementAndGet()
                    } else {
                        data.setCoverage("c$w", repo, false, readers[w])
                    }
                }
            }
        }
        gate.countDown()
        done.forEach { it.get(60, TimeUnit.SECONDS) }
        stop.set(true)
        observer.join(5_000)
        pool.shutdownNow()

        assertTrue(mismatches.toString(), mismatches.isEmpty())
        // Every clear() and every setCoverage with a reader publishes exactly one change.
        assertEquals(
            (writers * rounds).toLong(),
            data.coverageGeneration - before,
        )
    }

    // --- helpers ---------------------------------------------------------------------

    fun testReadyCoverageStopsIdleRequestsAndAutomaticLoadsRespectCooldown() {
        val head = head()
        CovdTestBase.git(repo, "update-ref", remoteRef(), head)
        daemon.ensureScript = { ready(head, cov4("idle.cov4", "src/Idle.php")) }
        loadAndWait()
        val count = daemon.ensureCount.get()
        val generation = CoverageDataService.getInstance(project).coverageGeneration

        repeat(3) { pollerTick() }
        val done = AtomicInteger()
        loadService().loadFromGitLab(showErrors = false, onComplete = { done.incrementAndGet() })
        waitUntil("automatic duplicate finishes") { done.get() == 1 && !loadService().isLoadInProgress() }

        assertEquals("idle ticks and duplicate refreshes must not reach covd", count, daemon.ensureCount.get())
        assertEquals(generation, CoverageDataService.getInstance(project).coverageGeneration)
    }

    fun testPushWithUnchangedHeadRefreshesOnceWithoutRepublishingIdenticalCoverage() {
        val head = head()
        val file = cov4("push.cov4", "src/Push.php")
        CovdTestBase.git(repo, "update-ref", "-d", remoteRef())
        daemon.ensureScript = { ready(head, file) }
        loadAndWait()
        val before = daemon.ensureCount.get()
        val data = CoverageDataService.getInstance(project)
        val generation = data.coverageGeneration
        val reader = data.snapshot().primary

        CovdTestBase.git(repo, "update-ref", remoteRef(), head)
        pollerTick()
        waitUntil("push is checked") { daemon.ensureCount.get() > before && !loadService().isLoadInProgress() }
        pollerTick()

        assertEquals(before + 1, daemon.ensureCount.get())
        assertEquals("same coverage does not publish another generation", generation, data.coverageGeneration)
        assertSame(reader, data.snapshot().primary)
    }

    fun testManualRefreshBypassesCooldownButUnchangedAnswerKeepsBothReaders() {
        val head = head()
        val file = cov4("primary.cov4", "src/Primary.php")
        val baseline = cov4("baseline.cov4", "src/Baseline.php")
        daemon.ensureScript = {
            ready(head, file).dropLast(1) +
                ",\"baselineCommit\":\"baseline\",\"baselineCov4Path\":\"${baseline.absolutePath}\"}"
        }
        loadAndWait()
        val data = CoverageDataService.getInstance(project)
        val snapshot = data.snapshot()
        val generation = data.coverageGeneration
        val before = daemon.ensureCount.get()

        loadAndWait()

        assertEquals("explicit refresh must still reach covd", before + 1, daemon.ensureCount.get())
        assertEquals(generation, data.coverageGeneration)
        assertSame(snapshot.primary, data.snapshot().primary)
        assertSame(snapshot.baseline, data.snapshot().baseline)
    }

    fun testReplacedReportForTheSameCommitIsApplied() {
        val head = head()
        val file = cov4("replace.cov4", "src/Before.php")
        daemon.ensureScript = { ready(head, file) }
        loadAndWait()
        val data = CoverageDataService.getInstance(project)
        val generation = data.coverageGeneration
        Cov4TestWriter.write(mapOf("src/After.php" to mapOf(1 to listOf("a.feature:1"))), file)
        daemon.ensureScript = { ready(head, file).dropLast(1) + ",\"replaced\":true}" }

        loadAndWait()

        assertTrue(data.coverageGeneration > generation)
        assertEquals(setOf("src/After.php"), data.allFiles())
    }

    fun testManualNotReadyDoesNotLetThePreviousSuccessSuppressItsRetry() {
        val head = head()
        val file = cov4("rerun.cov4", "src/Rerun.php")
        daemon.ensureScript = { ready(head, file) }
        loadAndWait()
        daemon.ensureScript = { """{"state":"not_ready","reason":"jobs_running","retryAfterMs":60000}""" }
        loadAndWait()
        loadService().cancelRetriesForTest()
        val before = daemon.ensureCount.get()
        daemon.ensureScript = { ready(head, file) }
        val done = AtomicInteger()

        // Fire the automatic retry explicitly instead of waiting a minute for the alarm.
        loadService().loadFromGitLab(showErrors = false, onComplete = { done.incrementAndGet() })
        waitUntil("the retry completes") { done.get() == 1 && !loadService().isLoadInProgress() }

        assertEquals(before + 1, daemon.ensureCount.get())
    }

    fun testFetchOfBaselineRefreshesWithoutChangingPrimary() {
        val head = head()
        val primary = cov4("fetch-primary.cov4", "src/Primary.php")
        val baseline = cov4("fetch-baseline.cov4", "src/Baseline.php")
        val settings = CoverageApiSettings.getInstance()
        settings.coverageBranch = "coverage-base"
        CovdTestBase.git(repo, "update-ref", remoteRef(), head)
        CovdTestBase.git(repo, "update-ref", "refs/remotes/origin/coverage-base", head)
        daemon.ensureScript = { ready(head, primary) }
        loadAndWait()
        val count = daemon.ensureCount.get()

        // A new baseline commit arrives via fetch; the feature HEAD and origin ref stay put.
        val fetched = CovdTestBase.git(repo, "commit-tree", "HEAD^{tree}", "-p", "HEAD", "-m", "baseline fetched")
        CovdTestBase.git(repo, "update-ref", "refs/remotes/origin/coverage-base", fetched)
        daemon.ensureScript = {
            ready(head, primary).dropLast(1) +
                ",\"baselineCommit\":\"$fetched\",\"baselineCov4Path\":\"${baseline.absolutePath}\"}"
        }
        pollerTick()
        val data = CoverageDataService.getInstance(project)
        waitUntil("fetched baseline is applied") {
            data.baselineCommitHash == fetched && !loadService().isLoadInProgress()
        }
        pollerTick()
        assertEquals(count + 1, daemon.ensureCount.get())
        assertEquals(head, data.coverageCommitHash)
        assertNotNull(data.getBaselineCoverage("src/Baseline.php"))
    }

    fun testStaleCoverageKeepsPollingUntilPushedCommitIsReady() {
        val head = head()
        val file = cov4("stale-poll.cov4", "src/Stale.php")
        CovdTestBase.git(repo, "update-ref", remoteRef(), head)
        daemon.ensureScript = { ready("older", file).dropLast(1) + ",\"stale\":true}" }
        loadAndWait()
        val count = daemon.ensureCount.get()
        assertTrue(CoverageDataService.getInstance(project).isStale)

        daemon.ensureScript = { ready(head, file) }
        pollerTick()
        val data = CoverageDataService.getInstance(project)
        waitUntil("pushed coverage becomes fresh") {
            data.coverageCommitHash == head && !data.isStale && !loadService().isLoadInProgress()
        }
        pollerTick()
        assertEquals(count + 1, daemon.ensureCount.get())
    }

    fun testIdenticalWarningContextAndAbsentBaselineDoNotPublishChanges() {
        val data = CoverageDataService.getInstance(project)
        data.setWarningContext("head", "base", 2, 3)
        val generation = data.coverageGeneration
        data.setWarningContext("head", "base", 2, 3)
        data.clearBaseline()
        assertEquals(generation, data.coverageGeneration)
        data.setWarningContext("head", "base", 1, 3)
        assertEquals(generation + 1, data.coverageGeneration)
    }

    fun testUnpublishedHeadWithNoPipelineWaitsForPushWithoutSchedulingRetries() {
        val head = head()
        val file = cov4("first-push.cov4", "src/FirstPush.php")
        CovdTestBase.git(repo, "update-ref", "-d", remoteRef())
        daemon.ensureScript = { """{"state":"not_ready","reason":"no_pipeline_yet","retryAfterMs":0}""" }
        loadAndWait()
        assertFalse("an unpublished HEAD has no pipeline to wait for yet", loadService().hasScheduledRetryForTest())
        val count = daemon.ensureCount.get()
        pollerTick()
        assertEquals(count, daemon.ensureCount.get())

        CovdTestBase.git(repo, "update-ref", remoteRef(), head)
        daemon.ensureScript = { ready(head, file) }
        pollerTick()
        waitUntil("the first push resumes coverage collection") {
            CoverageDataService.getInstance(project).hasData() && !loadService().isLoadInProgress()
        }
        assertEquals(count + 1, daemon.ensureCount.get())
    }

    private fun remoteRef() = "refs/remotes/origin/" + CovdTestBase.git(repo, "rev-parse", "--abbrev-ref", "HEAD")

    fun testPluginUnloadCancelsRetriesWithoutClosingTheProject() {
        val owner = Disposer.newDisposable("coverage-unload")
        val service = CoverageLoadService(project)
        Disposer.register(owner, service)
        try {
            daemon.ensureScript = { """{"state":"not_ready","reason":"jobs_running","retryAfterMs":60000}""" }
            service.loadFromGitLab(showErrors = false)
            waitUntil("a retry is scheduled") { service.hasScheduledRetryForTest() && !service.isLoadInProgress() }
            val alarm = CoverageLoadService::class.java.getDeclaredField("covdRetryAlarm").apply { isAccessible = true }
                .get(service) as com.intellij.openapi.Disposable
            val count = daemon.ensureCount.get()

            Disposer.dispose(owner)

            assertFalse("updating the plugin leaves the project open", project.isDisposed)
            assertTrue("the timer belongs to the plugin service, not the project", Disposer.isDisposed(alarm))
            assertFalse(service.hasScheduledRetryForTest())
            val callbacks = AtomicInteger()
            // Simulate an old timer that already started before cancellation.
            service.loadFromGitLab(showErrors = false, onComplete = { callbacks.incrementAndGet() })
            service.loadOfflineFirst()
            service.pollForUpdates()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(count, daemon.ensureCount.get())
            assertEquals(0, callbacks.get())
        } finally {
            if (!Disposer.isDisposed(owner)) Disposer.dispose(owner)
        }
    }

    fun testDaemonAnswerAfterPluginUnloadCannotPublishOrRestartThePoller() {
        val owner = Disposer.newDisposable("coverage-inflight-unload")
        val service = CoverageLoadService(project)
        Disposer.register(owner, service)
        try {
            val file = cov4("unloaded.cov4", "src/Unloaded.php")
            val head = head()
            daemon.ensureScript = { ready(head, file) }
            daemon.holdEnsure(1)
            val callbacks = AtomicInteger()
            val data = CoverageDataService.getInstance(project)
            val generation = data.coverageGeneration
            service.loadFromGitLab(showErrors = false, onComplete = { callbacks.incrementAndGet() })
            waitUntil("the request is in flight") { daemon.ensureCount.get() == 1 }

            Disposer.dispose(owner)
            daemon.release(1)
            waitUntil("the old worker finishes") { !service.isLoadInProgress() }

            assertEquals(generation, data.coverageGeneration)
            assertFalse(data.hasData())
            assertFalse(pollerStarted())
            assertEquals(0, callbacks.get())
        } finally {
            daemon.releaseAll()
            if (!Disposer.isDisposed(owner)) Disposer.dispose(owner)
        }
    }

    fun testQueuedUiCompletionIsDroppedWhenThePluginUnloads() {
        val owner = Disposer.newDisposable("coverage-ui-unload")
        val service = CoverageLoadService(project)
        Disposer.register(owner, service)
        try {
            val file = cov4("queued-ui.cov4", "src/Queued.php")
            val head = head()
            daemon.ensureScript = { ready(head, file) }
            val callbacks = AtomicInteger()
            service.loadFromGitLab(showErrors = false, onComplete = { callbacks.incrementAndGet() })
            // Keep the EDT occupied until the worker has queued its UI completion.
            val deadline = System.currentTimeMillis() + 20_000
            while (service.isLoadInProgress() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertFalse("the worker must finish", service.isLoadInProgress())
            assertEquals(0, callbacks.get())

            Disposer.dispose(owner)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            assertEquals("a queued old-version UI callback must not run", 0, callbacks.get())
        } finally {
            if (!Disposer.isDisposed(owner)) Disposer.dispose(owner)
        }
    }

    private fun loadAndWait() {
        val done = AtomicInteger()
        loadService().loadFromGitLab(showErrors = false, bypassBlock = true, onComplete = { done.incrementAndGet() })
        waitUntil("explicit load completes") { done.get() == 1 && !loadService().isLoadInProgress() }
    }

    private fun loadService() = CoverageLoadService.getInstance(project)

    private fun head(): String = CovdTestBase.git(repo, "rev-parse", "HEAD")

    private fun cov4(name: String, path: String): File {
        val file = File(cacheDir, name)
        Cov4TestWriter.write(mapOf(path to mapOf(1 to listOf("app/raketa/E2E/A.feature:1"))), file)
        return file
    }

    private fun ready(commit: String, cov4: File): String =
        """{"state":"ready","commit":"$commit","cov4Path":"${cov4.absolutePath}","cached":false,"stats":{"files":1,"lines":1,"covered":1}}"""

    private fun perform(action: AnAction) {
        val event = TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))
        action.actionPerformed(event)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun pollerStarted(): Boolean =
        CoveragePipelinePoller::class.java.getDeclaredField("started").apply { isAccessible = true }
            .getBoolean(CoveragePipelinePoller.getInstance(project))

    /** Runs the poller's tick once, as the 30 s alarm would. */
    private fun pollerTick() {
        val poller = CoveragePipelinePoller.getInstance(project)
        try {
            CoveragePipelinePoller::class.java.getDeclaredField("started").apply { isAccessible = true }
                .setBoolean(poller, true)
            ApplicationManager.getApplication().executeOnPooledThread(java.util.concurrent.Callable {
                CoveragePipelinePoller::class.java.getDeclaredMethod("onPoll").apply { isAccessible = true }
                    .invoke(poller)
            }).get(10, TimeUnit.SECONDS)
        } finally {
            poller.stop()
        }
    }

    private fun prepareRepo(): File {
        val root = File(project.basePath!!).apply { mkdirs() }
        if (!File(root, ".git").exists()) {
            CovdTestBase.git(root, "init", "-q", "-b", CovdTestBase.BRANCH)
            CovdTestBase.git(root, "config", "user.email", "heavy@example.invalid")
            CovdTestBase.git(root, "config", "user.name", "Heavy Test")
            CovdTestBase.git(root, "config", "commit.gpgsign", "false")
        }
        for (suite in listOf("E2E", "FeatureTest")) {
            File(root, "app/raketa/features/$suite").mkdirs()
            File(root, "app/raketa/features/$suite/.gitkeep").writeText("")
        }
        CovdTestBase.git(root, "add", "app")
        CovdTestBase.git(root, "commit", "--allow-empty", "-q", "-m", "heavy ${System.nanoTime()}")
        return root
    }

    private fun waitUntil(message: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(20)
        }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(message, condition())
    }

    /**
     * Fake daemon. `ensureScript(n)` returns the body for the n-th ensure (1-based);
     * [holdEnsure] makes that call wait until [release].
     */
    private class FakeDaemon {

        @Volatile
        var ensureScript: (Int) -> String = { """{"state":"error","code":"NO_DATA","retryable":false}""" }

        @Volatile
        var importBody: String = """{"state":"error","code":"NO_DATA"}"""

        val ensureCount = AtomicInteger(0)
        private val gates = java.util.concurrent.ConcurrentHashMap<Int, CountDownLatch>()

        private val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "heavy-fake-covd").apply { isDaemon = true }
        }
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange -> handle(exchange) }
                this.executor = executor
            }

        val port: Int get() = server.address.port

        fun start() = server.start()

        fun holdEnsure(n: Int) {
            gates[n] = CountDownLatch(1)
        }

        fun release(n: Int) {
            gates[n]?.countDown()
        }

        fun releaseAll() = gates.values.forEach { it.countDown() }

        fun stop() {
            releaseAll()
            server.stop(0)
            executor.shutdownNow()
        }

        private fun handle(exchange: HttpExchange) {
            try {
                when ("${exchange.requestMethod} ${exchange.requestURI.path}") {
                    "GET /v1/ping" ->
                        respond(exchange, """{"protocol":1,"build":"fake","instanceId":"fake-loadstate"}""")
                    "POST /v1/ensure" -> {
                        val n = ensureCount.incrementAndGet()
                        gates[n]?.await(30, TimeUnit.SECONDS)
                        respond(exchange, ensureScript(n))
                    }
                    "POST /v1/import" -> respond(exchange, importBody)
                    else -> respond(exchange, """{"code":"NOT_FOUND"}""", 404)
                }
            } catch (_: Throwable) {
                // The client may have hung up.
            } finally {
                exchange.close()
            }
        }

        private fun respond(exchange: HttpExchange, body: String, status: Int = 200) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        private const val IMPORT_COMMIT = "1111222233334444555566667777888899990000aaaabbbbccccddddeeeeffff"
    }
}
