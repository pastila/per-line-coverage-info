package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.Cov4TestWriter
import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageGutterVisibilityService
import com.github.yakov255.perlinecoverageinfo.CoverageLoadService
import com.github.yakov255.perlinecoverageinfo.CoveragePipelinePoller
import com.github.yakov255.perlinecoverageinfo.CoverageUserSelectionService
import com.github.yakov255.perlinecoverageinfo.covd.CovdTestBase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * `not_ready` backoff inside a real IDE (review item M5).
 *
 * The daemon is a fake behind a shell wrapper, so the test can answer `not_ready` and `ready`
 * on demand. It checks that the wait grows per answer for the same reason, starts over for a
 * new reason, and is reset by a successful load — without waiting the real 5 s ladder.
 */
class HeavyNotReadyBackoffTest : BasePlatformTestCase() {

    private lateinit var cacheDir: File
    private lateinit var repo: File
    private lateinit var daemon: FakeDaemon
    private var savedSettings: CoverageApiSettings.State? = null

    override fun setUp() {
        super.setUp()
        cacheDir = Files.createTempDirectory("heavy-notready-").toFile()
        daemon = FakeDaemon().also { it.start() }
        val script = File(cacheDir, "fake-covd.sh")
        script.writeText(
            """
            #!/bin/sh
            printf '{"covdReady":{"port":${daemon.port},"token":"t","instanceId":"fake-notready"}}\n'
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
    }

    override fun tearDown() {
        try {
            loadService().cancelRetriesForTest()
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

    fun testBackoffGrowsForTheSameReasonAndStartsOverOnReady() {
        daemon.ensureBody = """{"state":"not_ready","reason":"jobs_running","retryAfterMs":0}"""

        loadAndWait()
        assertEquals("the first not_ready schedules one answer back", 1, loadService().notReadyBackoffStepForTest())

        loadAndWait()
        assertEquals("the same reason keeps growing the backoff", 2, loadService().notReadyBackoffStepForTest())

        daemon.ensureBody = """{"state":"not_ready","reason":"no_pipeline_yet","retryAfterMs":0}"""
        loadAndWait()
        assertEquals("a different reason starts the ladder again", 1, loadService().notReadyBackoffStepForTest())

        val head = CovdTestBase.git(repo, "rev-parse", "HEAD")
        daemon.ensureBody = ready(head)
        loadAndWait()
        assertEquals("a ready answer resets the backoff", 0, loadService().notReadyBackoffStepForTest())
    }

    // --- helpers ---------------------------------------------------------------------

    private fun loadService() = CoverageLoadService.getInstance(project)

    private fun loadAndWait() {
        loadService().loadFromGitLab(showErrors = false)
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (!loadService().isLoadInProgress()) return
            Thread.sleep(20)
        }
        fail("the load did not finish")
    }

    private fun ready(commit: String): String {
        val cov4 = File(cacheDir, "head.cov4")
        Cov4TestWriter.write(mapOf("src/Head.php" to mapOf(1 to listOf("app/raketa/E2E/A.feature:1"))), cov4)
        return """{"state":"ready","commit":"$commit","cov4Path":"${cov4.absolutePath}","cached":false,"stats":{"files":1,"lines":1,"covered":1}}"""
    }

    private fun prepareRepo(): File {
        val root = File(project.basePath!!).apply { mkdirs() }
        if (!File(root, ".git").exists()) {
            CovdTestBase.git(root, "init", "-q", "-b", CovdTestBase.BRANCH)
            CovdTestBase.git(root, "config", "user.email", "coverage" + "@" + "test.invalid")
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

    /** Minimal covd API: ping, and an `ensure` answer the test can change. */
    private class FakeDaemon {

        @Volatile var ensureBody = """{"state":"not_ready","reason":"jobs_running","retryAfterMs":0}"""
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
                        respond(exchange, """{"protocol":1,"build":"fake","instanceId":"fake-notready"}""")
                    "POST /v1/ensure" -> {
                        ensureCount.incrementAndGet()
                        respond(exchange, ensureBody)
                    }
                    "GET /v1/artifacts" -> respond(exchange, "[]")
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
}
