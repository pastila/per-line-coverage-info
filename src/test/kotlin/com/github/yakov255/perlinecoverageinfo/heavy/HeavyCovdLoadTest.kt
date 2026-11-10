package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.CovdClient
import com.github.yakov255.perlinecoverageinfo.CoverageApiSettings
import com.github.yakov255.perlinecoverageinfo.CoverageCacheService
import com.github.yakov255.perlinecoverageinfo.CoverageDataService
import com.github.yakov255.perlinecoverageinfo.CoverageGutterVisibilityService
import com.github.yakov255.perlinecoverageinfo.CoverageLoadService
import com.github.yakov255.perlinecoverageinfo.covd.CovdTestBase
import com.github.yakov255.perlinecoverageinfo.covd.FakeGitLab
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * End-to-end tests of [CoverageLoadService] inside a real IDE process: the **real** `covd`
 * daemon, a [FakeGitLab], and a real git repository as the project root. They cover what the
 * unit-level covd tests cannot: that the plugin builds the request from its settings, applies
 * the answer to [CoverageDataService], and turns daemon errors into user-facing state.
 *
 * Skipped silently (with a stderr note) when no `covd` binary is found; see
 * [CovdTestBase.findBinary].
 */
class HeavyCovdLoadTest : BasePlatformTestCase() {

    private var daemonReady = false
    private lateinit var gitlab: FakeGitLab
    private lateinit var cacheDir: File
    private lateinit var repo: File
    private lateinit var head: String
    private var savedSettings: CoverageApiSettings.State? = null

    override fun setUp() {
        super.setUp()
        val binary = CovdTestBase.findBinary()
        if (binary == null) {
            System.err.println("HeavyCovdLoadTest: skipped, no covd binary (set COVD_DIST)")
            return
        }
        daemonReady = true

        cacheDir = Files.createTempDirectory("heavy-covd-cache-").toFile()
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
        settings.bearerToken = "heavy-test-token"
        settings.gitlabProjectId = PROJECT_ID.toLong()
        settings.gitlabProjectName = "group/project"
        settings.coverageBranch = ""
        settings.enabled = true
        CoverageGutterVisibilityService.getInstance(project).visible = true
        CoverageDataService.getInstance(project).clear()
        CoverageLoadService.getInstance(project).onSettingsApplied()
    }

    override fun tearDown() {
        try {
            if (daemonReady) {
                cancelRetryAlarm()
                CoverageDataService.getInstance(project).clear()
                savedSettings?.let { CoverageApiSettings.getInstance().loadState(it) }
                CoverageCacheService.cacheRootOverride = null
                CovdClient.resetForTest()
                gitlab.close()
                cacheDir.deleteRecursively()
            }
        } finally {
            super.tearDown()
        }
    }

    // --- scenarios -------------------------------------------------------------------

    fun testLoadFromGitLabAttachesCoverageFromTheDaemon() {
        if (!daemonReady) return
        readyPipeline(id = 100, sha = head)

        val result = loadAndWait()

        assertNull("no error expected, got ${result.error}", result.error)
        assertNull(loadService().lastError)
        val data = CoverageDataService.getInstance(project)
        assertTrue("coverage must be attached", data.hasData())
        assertEquals(head, data.coverageCommitHash)
        assertFalse("a fresh load must not be stale", data.isStale)
        assertEquals(repo.canonicalPath, data.gitRoot?.canonicalPath)
        assertFalse("no baseline without a coverage branch", data.hasBaseline())

        val lines = data.getCoverage("src/Foo.php")
        assertNotNull("raketa path mode must normalize core/web/src/Foo.php to src/Foo.php; files=${data.allFiles()}", lines)
        assertTrue("some line of Foo.php must have covering tests", lines!!.values.any { it.isNotEmpty() })

        // The plugin sent its settings to the daemon: GitLab saw the project and the token.
        assertTrue(gitlab.requests.any { "/projects/$PROJECT_ID/" in it.path })
        assertTrue("the GitLab token must reach GitLab", gitlab.requests.any { it.auth?.contains("heavy-test-token") == true })
        assertTrue("the cache file lives in the daemon cache", File(cacheDir, "$PROJECT_ID/$head-raketa.cov4").isFile)
    }

    fun testNotReadyIsNotAnErrorAndAnotherLoadSucceedsOnceTheJobFinishes() {
        if (!daemonReady) return
        gitlab.pipelines += FakeGitLab.Pipeline(100, head, BRANCH, status = "running")
        gitlab.jobs += FakeGitLab.Job(1000, 100, JOB_PREFIX, status = "running")

        val first = loadAndWait()

        assertNull("not_ready is not a user error", first.error)
        assertNull(loadService().lastError)
        assertFalse(CoverageDataService.getInstance(project).hasData())
        assertFalse("the load must be finished so the next one can start", loadService().isLoadInProgress())

        gitlab.reset()
        readyPipeline(id = 101, sha = head)
        val second = loadAndWait()

        assertNull(second.error)
        assertTrue(CoverageDataService.getInstance(project).hasData())
        assertEquals(head, CoverageDataService.getInstance(project).coverageCommitHash)
    }

    fun testExpiredTokenSurfacesAUserMessageAndKeepsOldData() {
        if (!daemonReady) return
        readyPipeline(id = 100, sha = head)
        loadAndWait()
        val data = CoverageDataService.getInstance(project)
        assertTrue(data.hasData())

        // A new commit forces a new daemon lookup; GitLab now rejects the token.
        CovdTestBase.git(repo, "commit", "--allow-empty", "-q", "-m", "next")
        gitlab.unauthorized = true
        val result = loadAndWait()

        assertNotNull("the error callback must fire", result.error)
        assertTrue(result.error!!, result.error!!.contains("token expired or invalid"))
        assertEquals(result.error, loadService().lastError)
        assertTrue("the previous coverage stays on screen", data.hasData())
        assertEquals(head, data.coverageCommitHash)
    }

    fun testAccessDeniedAndNoComponentJobsAreMappedToTheirMessages() {
        if (!daemonReady) return
        gitlab.notFound = true
        val denied = loadAndWait()
        assertTrue(denied.error!!, denied.error!!.contains("Access denied"))

        gitlab.reset()
        readyPipeline(id = 100, sha = head, jobName = "test:behat:coverage:somethingelse")
        // The previous 404 blocked automatic loads; new settings lift the block.
        loadService().onSettingsApplied()
        val noJobs = loadAndWait()
        assertTrue(noJobs.error!!, noJobs.error!!.contains("no coverage jobs"))
    }

    fun testUnavailableDaemonIsReportedAndLoadsNothing() {
        if (!daemonReady) return
        CovdClient.resetForTest()
        CovdClient.binaryOverride = File(cacheDir, "does-not-exist")
        CovdClient.cacheRootOverride = cacheDir

        val result = loadAndWait()

        assertNotNull(result.error)
        assertTrue(result.error!!, result.error!!.contains("daemon (covd) is unavailable"))
        assertEquals(result.error, loadService().lastError)
        assertFalse(CoverageDataService.getInstance(project).hasData())
        assertEquals("no GitLab call without a daemon", 0, gitlab.requests.size)
    }

    fun testDualCoverageAttachesTheBaselineAndDropsItWhenTheBranchIsCleared() {
        if (!daemonReady) return
        val masterSha = "b".repeat(40)
        readyPipeline(id = 100, sha = head)
        gitlab.addReadyPipeline(90, masterSha, "master", JOB_PREFIX, shard = resource("shard2.covt"))
        gitlab.mergeBase = masterSha
        CoverageApiSettings.getInstance().coverageBranch = "master"

        val dual = loadAndWait()

        assertNull(dual.error)
        val data = CoverageDataService.getInstance(project)
        assertTrue("baseline must be attached in dual mode", data.hasBaseline())
        assertEquals(masterSha, data.baselineCommitHash)
        assertEquals(head, data.coverageCommitHash)
        assertNotNull("baseline carries shard2's Foo.php", data.getBaselineCoverage("src/Foo.php"))

        // Without a coverage branch the daemon resolves no baseline and the plugin must drop it.
        CoverageApiSettings.getInstance().coverageBranch = ""
        val single = loadAndWait()

        assertNull(single.error)
        assertFalse("the baseline must be cleared in single mode", data.hasBaseline())
        assertNull(data.baselineCommitHash)
    }

    fun testOfflineFirstShowsTheCachedCommitAsStaleThenReplacesItWithTheFreshOne() {
        if (!daemonReady) return
        readyPipeline(id = 100, sha = head)
        loadAndWait()
        val data = CoverageDataService.getInstance(project)
        val older = head
        assertEquals(older, data.coverageCommitHash)

        // HEAD moves; its pipeline is ready but GitLab answers slowly, so the cached older
        // coverage must be visible (stale) while the daemon works.
        CovdTestBase.git(repo, "commit", "--allow-empty", "-q", "-m", "newer")
        val newer = CovdTestBase.git(repo, "rev-parse", "HEAD")
        data.clear()
        gitlab.addReadyPipeline(200, newer, BRANCH, JOB_PREFIX)
        gitlab.delayMs = 700

        loadService().loadOfflineFirst()

        assertTrue("the cached artifact must be shown immediately", data.hasData())
        assertTrue("and marked stale until the daemon answers", data.isStale)
        assertEquals(older, data.coverageCommitHash)

        waitUntil("the fresh commit must replace the stale one") {
            !data.isStale && data.coverageCommitHash == newer
        }
        assertNull(loadService().lastError)
        assertTrue(data.hasData())
        assertEquals("HEAD moved by one commit", 1, data.primaryBehindBy ?: 1)
    }

    fun testHiddenGutterSuppressesTheLoad() {
        if (!daemonReady) return
        readyPipeline(id = 100, sha = head)
        CoverageGutterVisibilityService.getInstance(project).visible = false

        val result = loadAndWait()

        assertNull(result.error)
        assertFalse(CoverageDataService.getInstance(project).hasData())
        assertEquals("a hidden gutter must not reach GitLab", 0, gitlab.requests.size)
    }

    fun testDisabledPluginReportsAConfigurationProblemWithoutTouchingTheDaemon() {
        if (!daemonReady) return
        readyPipeline(id = 100, sha = head)
        CoverageApiSettings.getInstance().enabled = false

        assertTrue(loadService().validateSettings()!!.contains("disabled"))
        loadService().loadFromGitLab(showErrors = false)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(0, gitlab.requests.size)
        assertEquals(0, CovdClient.startCount.get())
        assertFalse(CoverageDataService.getInstance(project).hasData())
    }

    // --- helpers ---------------------------------------------------------------------

    private class LoadResult(val error: String?)

    private fun loadService() = CoverageLoadService.getInstance(project)

    /** Runs `loadFromGitLab` and waits until the completion callback fired on the EDT. */
    private fun loadAndWait(): LoadResult {
        val done = CompletableFuture<Unit>()
        var error: String? = null
        loadService().loadFromGitLab(
            showErrors = false,
            onComplete = { done.complete(Unit) },
            onError = { error = it },
        )
        waitUntil("load did not finish") { done.isDone }
        // onError is posted before onComplete's invokeLater; drain what is left.
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        return LoadResult(error)
    }

    private fun readyPipeline(id: Int, sha: String, jobName: String = JOB_PREFIX) {
        gitlab.addReadyPipeline(id, sha, BRANCH, jobName)
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

    /**
     * The COVT fixtures are embedded: the heavyTest IDE classpath has no test resources.
     * Same bytes as src/test/resources/covd/shard{1,2}.covt.
     */
    private fun resource(name: String): ByteArray = java.util.Base64.getDecoder().decode(
        when (name) {
            "shard1.covt" -> SHARD1
            else -> SHARD2
        },
    )

    /** A `not_ready` answer schedules a retry; cancel it so it cannot fire into a later test. */
    private fun cancelRetryAlarm() {
        runCatching {
            val field = CoverageLoadService::class.java.getDeclaredField("covdRetryAlarm").apply { isAccessible = true }
            (field.get(loadService()) as com.intellij.util.Alarm).cancelAllRequests()
        }
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

    companion object {
        private const val PROJECT_ID = "1"
        private const val BRANCH = CovdTestBase.BRANCH
        private const val JOB_PREFIX = "test:behat:coverage:raketa"
        private const val SHARD1 = "VFZPQwEAAAAAAAAAAwAAACMAAABjb3JlL3dlYi9hcHAvcmFrZXRhL0UyRS9BLmZlYXR1cmU6MSMAAABjb3JlL3dlYi9hcHAvcmFrZXRhL0UyRS9CLmZlYXR1cmU6MhcAAABjb3JlL3dlYi9zcmMvRm9vLnBocDoxMkVMSUYUAAAAY29yZS93ZWIvc3JjL0Zvby5waHACAAAAAgAAAAAAAAACAAAAAQAAAAEAAAADAAAACgAAAAAAAAALAAAAAQAAAAwAAAD/////RUxJRhgAAABjb3JlL3dlYi9zcmMvU3ViL0Jhei5waHABAAAAAQAAAAEAAAACAAAABQAAAAAAAAAGAAAA/////0VMSUYWAAAAY29yZS93ZWIvc3JjL0VtcHR5LnBocAAAAAACAAAAAQAAAP////8CAAAA/////0ZETkU="
        private const val SHARD2 = "VFZPQwEAAAAAAAAAAwAAABoAAABhcHAvcmFrZXRhL0UyRS9CLmZlYXR1cmU6MiIAAABhcHAvcmFrZXRhL0ZlYXR1cmVUZXN0L0MuZmVhdHVyZTozHQAAAGFwcC9yYWtldGEvQ3VzdG9tL0MuZmVhdHVyZTo5RUxJRhQAAABjb3JlL3dlYi9zcmMvRm9vLnBocAIAAAABAAAAAAAAAAIAAAABAAAAAgAAAAMAAAAKAAAAAAAAAAsAAAABAAAADQAAAAAAAABFTElGGAAAAGNvcmUvd2ViL3NyYy9TdWIvQmF6LnBocAEAAAACAAAAAQAAAAIAAAABAAAABQAAAAAAAABGRE5F"
    }
}
