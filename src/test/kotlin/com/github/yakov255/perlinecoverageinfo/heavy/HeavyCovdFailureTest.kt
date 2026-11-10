package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.github.yakov255.perlinecoverageinfo.Cov4TestWriter
import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageGutterVisibilityService
import com.github.yakov255.perlinecoverageinfo.CoverageLoadService
import com.github.yakov255.perlinecoverageinfo.CoverageNotifier
import com.github.yakov255.perlinecoverageinfo.CoveragePipelinePoller
import com.github.yakov255.perlinecoverageinfo.CoverageSettingsActions
import com.github.yakov255.perlinecoverageinfo.RetryPolicy
import com.github.yakov255.perlinecoverageinfo.covd.CovdTestBase
import com.github.yakov255.perlinecoverageinfo.covd.FakeGitLab
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Failure-handling contract inside a real IDE process.
 *
 * The token and deterministic-error scenarios run the **real** `covd` against [FakeGitLab], so
 * the `AUTH_INVALID` / `NO_COMPONENT_JOBS` codes are the daemon's own. The retry ladder uses a
 * fake daemon behind a shell wrapper (the same shape as `CovdFakeDaemonContractTest`), because
 * waiting for covd's internal backoff would make the test slow and flaky.
 */
class HeavyCovdFailureTest : BasePlatformTestCase() {

    private var daemonReady = false
    private lateinit var gitlab: FakeGitLab
    private lateinit var cacheDir: File
    private lateinit var repo: File
    private lateinit var head: String
    private var savedSettings: CoverageApiSettings.State? = null
    private var savedDelays: String? = null
    private val fakeDaemons = CopyOnWriteArrayList<FakeDaemon>()

    override fun setUp() {
        super.setUp()
        val binary = CovdTestBase.findBinary()
        if (binary == null) {
            System.err.println("HeavyCovdFailureTest: skipped, no covd binary (set COVD_DIST)")
            return
        }
        daemonReady = true

        // The ladder is read when CoverageLoadService is created, so set it before any access.
        savedDelays = System.getProperty(RetryPolicy.DELAYS_PROPERTY)
        System.setProperty(RetryPolicy.DELAYS_PROPERTY, "100,150,200")

        cacheDir = Files.createTempDirectory("heavy-failure-cache-").toFile()
        gitlab = FakeGitLab().apply {
            defaultShard = resource("shard1.covt")
        }
        CovdClient.resetForTest()
        CovdClient.binaryOverride = binary
        CovdClient.cacheRootOverride = cacheDir
        CoverageCacheService.cacheRootOverride = cacheDir

        repo = prepareRepo()
        head = CovdTestBase.git(repo, "rev-parse", "HEAD")

        val settings = CoverageApiSettings.getInstance()
        savedSettings = settings.state.copy()
        settings.gitlabDomain = gitlab.baseUrl
        settings.bearerToken = "dummy"
        settings.gitlabProjectId = PROJECT_ID.toLong()
        settings.gitlabProjectName = "group/project"
        settings.coverageBranch = ""
        settings.enabled = true
        CoverageGutterVisibilityService.getInstance(project).visible = true
        CoverageDataService.getInstance(project).clear()
        loadService().onSettingsApplied()
        CoverageNotifier.getInstance(project).clearForTest()
    }

    override fun tearDown() {
        try {
            if (daemonReady) {
                loadService().cancelRetriesForTest()
                CoverageDataService.getInstance(project).clear()
                savedSettings?.let { CoverageApiSettings.getInstance().loadState(it) }
                CoverageCacheService.cacheRootOverride = null
                CovdClient.resetForTest()
                gitlab.close()
                fakeDaemons.forEach { it.stop() }
                fakeDaemons.clear()
                cacheDir.deleteRecursively()
            }
        } finally {
            if (savedDelays == null) {
                System.clearProperty(RetryPolicy.DELAYS_PROPERTY)
            } else {
                System.setProperty(RetryPolicy.DELAYS_PROPERTY, savedDelays!!)
            }
            super.tearDown()
        }
    }

    // --- token problems --------------------------------------------------------------

    fun testAuthInvalidBlocksWithoutRetriesAndANewTokenLoadsAgain() {
        if (!daemonReady) return
        readyPipeline(id = 100, sha = head)
        gitlab.unauthorized = true

        val first = loadAndWait()

        assertNotNull("AUTH_INVALID must reach the user callback", first.error)
        assertTrue(first.error!!, first.error!!.contains("token expired or invalid"))
        assertEquals("AUTH", loadService().blockedReason())
        assertEquals("no retry after an auth error", 0, loadService().retryAttempt)
        assertEquals("exactly one GitLab call", 1, gitlab.requests.size)

        val authNotification = notifier().notifications().single()
        assertTrue(authNotification.title, authNotification.title.contains("token expired or invalid"))
        assertTrue(authNotification.actions.contains(CoverageNotifier.ACTION_UPDATE_TOKEN))
        assertTrue(authNotification.actions.none { it == CoverageNotifier.ACTION_RETRY })

        // The bridge/poller path is silent and must not touch GitLab while blocked.
        val blocked = loadAndWait()
        assertNull(blocked.error)
        assertEquals("a blocked automatic load makes no request", 1, gitlab.requests.size)

        // A new token through the shared Apply path clears the block and loads again.
        gitlab.reset()
        readyPipeline(id = 101, sha = head)
        CoverageApiSettings.getInstance().bearerToken = "new-token"
        CoverageSettingsActions.afterSettingsApplied()

        waitUntil("the new token must load coverage") {
            !CoverageDataService.getInstance(project).isStale &&
                CoverageDataService.getInstance(project).coverageCommitHash == head
        }
        assertNull(loadService().blockedReason())
        assertEquals(0, loadService().retryAttempt)
        assertTrue(
            "GitLab must see the new token",
            gitlab.requests.any { it.auth?.contains("new-token") == true },
        )
    }

    // --- deterministic errors ---------------------------------------------------------

    fun testNoComponentJobsStopsThePollerUntilHeadChanges() {
        if (!daemonReady) return
        readyPipeline(id = 100, sha = head, jobName = "test:behat:coverage:another")
        val first = loadAndWait()

        assertNotNull(first.error)
        assertTrue(first.error!!, first.error!!.contains("no coverage jobs"))
        assertEquals("NON_RETRYABLE:NO_COMPONENT_JOBS", loadService().blockedReason())
        assertEquals("deterministic errors cost no retries", 0, loadService().retryAttempt)
        assertTrue("no balloon for a deterministic error", notifier().notifications().isEmpty())

        val calls = gitlab.pipelineListCalls
        pollerTick()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertEquals("the poller must not ask GitLab again for the same HEAD", calls, gitlab.pipelineListCalls)

        // HEAD moves: the block belongs to the old commit and must not survive it.
        CovdTestBase.git(repo, "commit", "--allow-empty", "-q", "-m", "next")
        val newHead = CovdTestBase.git(repo, "rev-parse", "HEAD")
        readyPipeline(id = 200, sha = newHead)
        loadService().onHeadChanged(newHead)
        assertNull("a new HEAD lifts the block", loadService().blockedReason())

        val second = loadAndWait()

        assertNull(second.error)
        assertNull(loadService().blockedReason())
        assertTrue(CoverageDataService.getInstance(project).hasData())
        assertEquals(newHead, CoverageDataService.getInstance(project).coverageCommitHash)
    }

    // --- retryable transport errors ---------------------------------------------------

    fun testRetryableErrorIsRetriedThreeTimesThenABalloonAndManualRetrySucceeds() {
        if (!daemonReady) return
        val fake = startFakeDaemon(
            """{"state":"error","code":"CONNECTION","retryable":true,"retryAfterMs":0,"message":"network is down"}""",
        )

        val first = loadAndWait()

        assertNotNull(first.error)
        assertEquals(3, loadService().retryMax)
        waitUntil("the initial attempt and three retries must run", 20_000) {
            fake.ensureCount.get() >= 4
        }
        waitUntil("the exhausted budget must show a balloon", 10_000) {
            notifier().notifications().isNotEmpty()
        }
        assertEquals(3, loadService().retryAttempt)

        val balloon = notifier().notifications().single()
        assertEquals("Coverage load failed", balloon.title)
        assertTrue(balloon.actions.contains(CoverageNotifier.ACTION_RETRY))
        assertTrue(balloon.actions.contains(CoverageNotifier.ACTION_SHOW_LOG))
        assertTrue(balloon.content, balloon.content.contains("Cannot connect to GitLab"))

        // "Retry now": the fake now answers with a real coverage file, so the load succeeds.
        val cov4 = File(cacheDir, "manual.cov4")
        Cov4TestWriter.write(
            mapOf("src/Foo.php" to mapOf(1 to listOf("app/raketa/E2E/A.feature:1"))),
            cov4,
        )
        fake.ensureBody =
            """{"state":"ready","commit":"$head","cov4Path":"${cov4.absolutePath}","cached":false,"stats":{"files":1,"lines":1,"covered":1}}"""
        val before = fake.ensureCount.get()

        loadService().loadFromGitLab(showErrors = false, bypassBlock = true)

        waitUntil("the manual retry must load coverage") {
            CoverageDataService.getInstance(project).hasData() && loadService().retryAttempt == 0
        }
        assertTrue(fake.ensureCount.get() > before)
        assertNull(loadService().lastError)
        assertNull(loadService().blockedReason())
        assertTrue(CoverageDataService.getInstance(project).hasData())
    }

    // --- helpers ---------------------------------------------------------------------

    private fun loadService() = CoverageLoadService.getInstance(project)

    private fun notifier() = CoverageNotifier.getInstance(project)

    private class LoadResult(val error: String?)

    private fun loadAndWait(): LoadResult {
        val done = CompletableFuture<Unit>()
        var error: String? = null
        loadService().loadFromGitLab(
            showErrors = false,
            onComplete = { done.complete(Unit) },
            onError = { error = it },
        )
        waitUntil("load did not finish") { done.isDone }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return LoadResult(error)
    }

    private fun readyPipeline(id: Int, sha: String, jobName: String = JOB_PREFIX) {
        gitlab.addReadyPipeline(id, sha, BRANCH, jobName)
    }

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

    /** The project is a git root so the plugin resolves the `raketa` component and pathMode. */
    private fun prepareRepo(): File {
        val root = File(project.basePath!!).apply { mkdirs() }
        if (!File(root, ".git").exists()) {
            CovdTestBase.git(root, "init", "-q", "-b", BRANCH)
            CovdTestBase.git(root, "config", "user.email", "heavy@example.com")
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
     * Fake daemon: a shell wrapper prints the handshake line and sleeps, while a JDK
     * [HttpServer] answers ping and ensure. The ensure body is mutable, so a test can move from
     * "still broken" to "works" without restarting the daemon.
     */
    private fun startFakeDaemon(initialEnsureBody: String): FakeDaemon {
        val daemon = FakeDaemon(initialEnsureBody)
        daemon.start()
        fakeDaemons += daemon

        val script = File(cacheDir, "fake-covd-${fakeDaemons.size}.sh")
        script.writeText(
            """
            #!/bin/sh
            printf '{"covdReady":{"port":${daemon.port},"token":"daemon-token","instanceId":"fake-failure"}}\n'
            exec sleep 3600
            """.trimIndent() + "\n",
        )
        script.setExecutable(true)

        CovdClient.resetForTest()
        CovdClient.binaryOverride = script
        CovdClient.cacheRootOverride = cacheDir
        return daemon
    }

    private class FakeDaemon(initialEnsureBody: String) {

        @Volatile
        var ensureBody: String = initialEnsureBody

        val ensureCount = AtomicInteger(0)

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

        fun stop() {
            server.stop(0)
            executor.shutdownNow()
        }

        private fun handle(exchange: HttpExchange) {
            try {
                when ("${exchange.requestMethod} ${exchange.requestURI.path}") {
                    "GET /v1/ping" ->
                        respond(exchange, 200, """{"protocol":1,"build":"fake","instanceId":"fake-failure"}""")
                    "POST /v1/ensure" -> {
                        ensureCount.incrementAndGet()
                        respond(exchange, 200, ensureBody)
                    }
                    else -> respond(exchange, 404, """{"code":"NOT_FOUND"}""")
                }
            } catch (_: Throwable) {
                // The client may have hung up; nothing to answer.
            } finally {
                exchange.close()
            }
        }

        private fun respond(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    /** The COVT fixture is embedded: the heavyTest IDE classpath has no test resources. */
    private fun resource(name: String): ByteArray = java.util.Base64.getDecoder().decode(
        when (name) {
            "shard1.covt" -> SHARD1
            else -> error("unknown fixture $name")
        },
    )

    companion object {
        private const val PROJECT_ID = "1"
        private const val BRANCH = CovdTestBase.BRANCH
        private const val JOB_PREFIX = "test:behat:coverage:raketa"
        private const val SHARD1 = "VFZPQwEAAAAAAAAAAwAAACMAAABjb3JlL3dlYi9hcHAvcmFrZXRhL0UyRS9BLmZlYXR1cmU6MSMAAABjb3JlL3dlYi9hcHAvcmFrZXRhL0UyRS9CLmZlYXR1cmU6MhcAAABjb3JlL3dlYi9zcmMvRm9vLnBocDoxMkVMSUYUAAAAY29yZS93ZWIvc3JjL0Zvby5waHACAAAAAgAAAAAAAAACAAAAAQAAAAEAAAADAAAACgAAAAAAAAALAAAAAQAAAAwAAAD/////RUxJRhgAAABjb3JlL3dlYi9zcmMvU3ViL0Jhei5waHABAAAAAQAAAAEAAAACAAAABQAAAAAAAAAGAAAA/////0VMSUYWAAAAY29yZS93ZWIvc3JjL0VtcHR5LnBocAAAAAACAAAAAQAAAP////8CAAAA/////0ZETkU="
    }
}
