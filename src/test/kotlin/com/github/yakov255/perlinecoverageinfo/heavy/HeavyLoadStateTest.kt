package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.ClearCoverageAction
import com.github.yakov255.perlinecoverageinfo.Cov4Reader
import com.github.yakov255.perlinecoverageinfo.Cov4TestWriter
import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageGutterVisibilityService
import com.github.yakov255.perlinecoverageinfo.CoverageLoadService
import com.github.yakov255.perlinecoverageinfo.CoverageNotifier
import com.github.yakov255.perlinecoverageinfo.CoveragePipelinePoller
import com.github.yakov255.perlinecoverageinfo.CoverageUserSelectionService
import com.github.yakov255.perlinecoverageinfo.LoadCoverageAction
import com.github.yakov255.perlinecoverageinfo.covd.CovdTestBase
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
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
            CoveragePipelinePoller::class.java.getDeclaredMethod("onPoll").apply { isAccessible = true }
                .invoke(poller)
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
