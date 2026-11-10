package com.github.yakov255.perlinecoverageinfo.covd

import com.github.yakov255.perlinecoverageinfo.Cov4Reader
import com.github.yakov255.perlinecoverageinfo.CovdClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end `ensure` flow against the real `covd` binary: the ready answer, the cache
 * reuse and forced rebuild, the `not_ready` reasons, the stale fallback, dual-coverage
 * baselines, the cross-ref fallback and the request trace.
 *
 * The fixtures are `shard1.covt` (core/web/src/Foo.php lines 10, 11 covered, 12 not;
 * Sub/Baz.php line 5 covered, 6 not; Empty.php entirely uncovered) and `shard2.covt`
 * (Foo.php lines 10, 11, 13; no Empty.php).
 */
class CovdEnsureFlowTest : CovdTestBase() {

    @Test
    fun `ensure returns ready with the commit pipeline stats and a readable cov4 under the cache root`() {
        readyPipeline(id = 100, sha = head)

        val response = CovdClient.ensure(request())

        assertEquals(response.toString(), "ready", response.state)
        assertEquals(head, response.commit)
        assertEquals(100L, response.pipelineId)
        assertFalse("first build is not a cache hit", response.cached)
        assertFalse("first build replaces nothing", response.replaced)
        assertFalse(response.stale)

        val stats = assertNotNull("stats are missing", response.stats)
        assertEquals(3, stats.files)
        assertEquals(7, stats.lines)
        assertEquals(3, stats.covered)

        val path = File(assertNotNull(response.cov4Path))
        assertTrue("cov4 path $path is outside $cacheDir", path.path.startsWith(cacheDir.canonicalPath))
        assertEquals("$head-$COMPONENT.cov4", path.name)
        assertTrue("cov4 missing: $path", path.isFile)
        assertEquals(1, gitlab.downloads)

        Cov4Reader(path).use { reader ->
            val foo = assertNotNull(reader.getCoverage("core/web/src/Foo.php"))
            assertEquals(setOf(10, 11, 12), foo.keys)
            assertEquals(
                setOf("core/web/app/raketa/E2E/A.feature:1", "core/web/src/Foo.php:12"),
                foo.getValue(10).toSet(),
            )
            assertEquals(setOf("core/web/app/raketa/E2E/B.feature:2"), foo.getValue(11).toSet())
            assertEquals(emptyList<String>(), foo.getValue(12))

            val baz = assertNotNull(reader.getCoverage("core/web/src/Sub/Baz.php"))
            assertEquals(setOf(5, 6), baz.keys)
            assertEquals(setOf("core/web/app/raketa/E2E/B.feature:2"), baz.getValue(5).toSet())
            assertEquals(emptyList<String>(), baz.getValue(6))

            val empty = assertNotNull(reader.getCoverage("core/web/src/Empty.php"))
            assertEquals(setOf(1, 2), empty.keys)
            assertTrue(empty.values.all { it.isEmpty() })

            assertNull(reader.getCoverage("core/web/src/Missing.php"))
        }
    }

    @Test
    fun `repeated ensure is served from cache without asking GitLab or downloading again`() {
        readyPipeline(id = 100, sha = head)
        val first = CovdClient.ensure(request())
        assertEquals("ready", first.state)
        val pipelineCallsAfterFirst = gitlab.pipelineListCalls

        val second = CovdClient.ensure(request())

        assertEquals(second.toString(), "ready", second.state)
        assertTrue("second ensure must be a cache hit", second.cached)
        assertFalse(second.replaced)
        assertEquals(head, second.commit)
        assertEquals(100L, second.pipelineId)
        assertEquals(first.cov4Path, second.cov4Path)
        assertEquals(1, gitlab.downloads)
        assertEquals("a fresh resolution must be memoized", pipelineCallsAfterFirst, gitlab.pipelineListCalls)
    }

    @Test
    fun `force rebuilds the cache and an already open reader sees the new data`() {
        readyPipeline(id = 100, sha = head)
        val first = CovdClient.ensure(request())
        assertEquals("ready", first.state)
        val path = File(assertNotNull(first.cov4Path))

        Cov4Reader(path).use { reader ->
            val before = assertNotNull(reader.getCoverage("core/web/src/Foo.php"))
            assertEquals(setOf(10, 11, 12), before.keys)
            assertTrue(before.getValue(12).isEmpty())

            // The next download serves the second shard from the same pipeline and commit.
            gitlab.defaultShard = resource("shard2.covt")

            val forced = CovdClient.ensure(request(force = true))

            assertEquals(forced.toString(), "ready", forced.state)
            assertTrue("force must rewrite the file", forced.replaced)
            assertEquals(2, gitlab.downloads)
            assertEquals(path, File(assertNotNull(forced.cov4Path)))
            assertEquals(4, assertNotNull(forced.stats).covered)

            val after = assertNotNull(reader.getCoverage("core/web/src/Foo.php"))
            assertEquals("the open reader must reload the replaced file", setOf(10, 11, 13), after.keys)
            assertEquals(setOf("app/raketa/E2E/B.feature:2"), after.getValue(10).toSet())
            assertEquals(
                setOf("app/raketa/FeatureTest/C.feature:3", "app/raketa/Custom/C.feature:9"),
                after.getValue(11).toSet(),
            )
            assertEquals(setOf("app/raketa/E2E/B.feature:2"), after.getValue(13).toSet())
            assertNull("the old Empty.php must be gone after the rebuild", reader.getCoverage("core/web/src/Empty.php"))
        }
    }

    @Test
    fun `ensure reports not_ready with jobs_running while the component job runs`() {
        gitlab.pipelines += FakeGitLab.Pipeline(20, head, BRANCH, status = "running")
        gitlab.jobs += FakeGitLab.Job(201, 20, JOB_PREFIX, status = "running")

        val response = CovdClient.ensure(request())

        assertEquals(response.toString(), "not_ready", response.state)
        assertEquals("jobs_running", response.reason)
        assertEquals(10_000L, response.retryAfterMs)
        assertEquals(head, response.commit)
        assertEquals(0, gitlab.downloads)
        assertFalse("no cache may be created for a not_ready answer", File(cacheDir, PROJECT_ID).exists())
    }

    @Test
    fun `ensure reports not_ready with no_pipeline_yet when the branch has no pipeline`() {
        val response = CovdClient.ensure(request())

        assertEquals(response.toString(), "not_ready", response.state)
        assertEquals("no_pipeline_yet", response.reason)
        assertEquals(30_000L, response.retryAfterMs)
        assertEquals(head, response.commit)
        assertEquals(0, gitlab.downloads)
        assertTrue("the resolver must at least look for pipelines", gitlab.pipelineListCalls > 0)
    }

    @Test
    fun `ensure reports not_ready with job_failed when the component job failed`() {
        gitlab.pipelines += FakeGitLab.Pipeline(40, head, BRANCH, status = "failed")
        gitlab.jobs += FakeGitLab.Job(401, 40, JOB_PREFIX, status = "failed")

        val response = CovdClient.ensure(request())

        assertEquals(response.toString(), "not_ready", response.state)
        assertEquals("job_failed", response.reason)
        assertEquals(60_000L, response.retryAfterMs)
        assertEquals(0, gitlab.downloads)
    }

    @Test
    fun `ensure maps a pipeline without component jobs to the NO_COMPONENT_JOBS error`() {
        gitlab.pipelines += FakeGitLab.Pipeline(30, head, BRANCH)
        gitlab.jobs += FakeGitLab.Job(301, 30, "test:behat:coverage:web")

        val response = CovdClient.ensure(request())

        // The daemon answers `error`, not `not_ready`: a pipeline whose jobs belong to another
        // component proves nothing about this component's CI (api/features/covd.feature,
        // "трейс приходит вместе с ошибкой").
        assertEquals(response.toString(), "error", response.state)
        assertEquals("NO_COMPONENT_JOBS", response.code)
        assertEquals("client", response.cause)
        assertFalse(response.retryable)
        assertEquals(0, gitlab.downloads)
    }

    @Test
    fun `ensure reports the no_component_jobs reason when a newer commit has another component`() {
        val older = head
        val newer = commit("newer")
        gitlab.pipelines += FakeGitLab.Pipeline(30, newer, BRANCH)
        gitlab.jobs += FakeGitLab.Job(301, 30, "test:behat:coverage:web")
        readyPipeline(id = 20, sha = older)

        val response = CovdClient.ensure(request())

        assertEquals(response.toString(), "ready", response.state)
        assertTrue(response.stale)
        assertEquals("no_component_jobs", response.staleReason)
        assertEquals(older, response.commit)
        assertEquals(20L, response.pipelineId)
        assertEquals(1, gitlab.downloads)
        assertTrue("the older commit's cov4 must exist", File(assertNotNull(response.cov4Path)).isFile)
    }

    @Test
    fun `an unfinished newer commit falls back to the ready older commit as stale`() {
        val older = head
        val newer = commit("newer")
        gitlab.pipelines += FakeGitLab.Pipeline(30, newer, BRANCH, status = "running")
        gitlab.jobs += FakeGitLab.Job(301, 30, JOB_PREFIX, status = "running")
        readyPipeline(id = 20, sha = older)

        val response = CovdClient.ensure(request())

        assertEquals(response.toString(), "ready", response.state)
        assertTrue("the answer must be marked stale", response.stale)
        assertEquals("jobs_running", response.staleReason)
        assertEquals(older, response.commit)
        assertEquals(20L, response.pipelineId)
        assertEquals(1, gitlab.downloads)
        val path = File(assertNotNull(response.cov4Path))
        assertEquals("$older-$COMPONENT.cov4", path.name)
        assertTrue(path.isFile)
    }

    @Test
    fun `dual coverage returns the coverage-branch baseline and its file`() {
        val masterSha = "b".repeat(40)
        readyPipeline(id = 100, sha = head)
        readyPipeline(id = 90, sha = masterSha, ref = "master")
        gitlab.mergeBase = masterSha

        val response = CovdClient.ensure(request(coverageBranch = "master"))

        assertEquals(response.toString(), "ready", response.state)
        assertEquals(head, response.commit)
        assertEquals(100L, response.pipelineId)
        assertEquals("ready", response.baselineState)
        assertEquals(masterSha, response.baselineCommit)
        val baseline = File(assertNotNull(response.baselineCov4Path))
        assertEquals("$masterSha-$COMPONENT.cov4", baseline.name)
        assertTrue("baseline cov4 missing: $baseline", baseline.isFile)
        assertNotEquals(response.cov4Path, response.baselineCov4Path)
        assertEquals("primary and baseline are downloaded separately", 2, gitlab.downloads)

        Cov4Reader(baseline).use { reader ->
            assertNotNull("the baseline must carry the same component's files", reader.getCoverage("core/web/src/Foo.php"))
        }
    }

    @Test
    fun `an empty coverage branch skips the baseline and downloads once`() {
        val masterSha = "b".repeat(40)
        readyPipeline(id = 100, sha = head)
        readyPipeline(id = 90, sha = masterSha, ref = "master")
        gitlab.mergeBase = masterSha

        val response = CovdClient.ensure(request(coverageBranch = ""))

        assertEquals(response.toString(), "ready", response.state)
        assertNull(response.baselineCommit)
        assertNull(response.baselineCov4Path)
        assertNull(response.baselineState)
        assertEquals(1, gitlab.downloads)
    }

    @Test
    fun `a branch without its own pipeline uses a pipeline at HEAD from another ref`() {
        // covd has no merge-base tier for the primary: when the branch has no pipeline, it looks
        // up pipelines by the HEAD commit regardless of ref, so a coverage-branch pipeline that
        // already covers this commit is used.
        readyPipeline(id = 90, sha = head, ref = "master")
        gitlab.mergeBase = head

        val response = CovdClient.ensure(request(coverageBranch = "master"))

        assertEquals(response.toString(), "ready", response.state)
        assertEquals(head, response.commit)
        assertEquals(90L, response.pipelineId)
        assertNull("the baseline equals the primary commit, so it is dropped", response.baselineCommit)
        assertEquals(1, gitlab.downloads)
    }

    @Test
    fun `only a coverage-branch pipeline at the merge base leaves the primary not_ready`() {
        // Documents the absence of a "current branch -> coverage branch -> merge base" tier for
        // the primary: a baseline candidate never rescues a missing branch pipeline.
        val masterSha = "c".repeat(40)
        readyPipeline(id = 90, sha = masterSha, ref = "master")
        gitlab.mergeBase = masterSha

        val response = CovdClient.ensure(request(coverageBranch = "master"))

        assertEquals(response.toString(), "not_ready", response.state)
        assertEquals("no_pipeline_yet", response.reason)
        assertEquals(head, response.commit)
        assertNull(response.baselineCommit)
        assertEquals(0, gitlab.downloads)
    }

    @Test
    fun `different components produce different cache files for the same commit`() {
        readyPipeline(id = 100, sha = head, jobName = JOB_PREFIX)
        val api = CovdClient.ensure(request(component = COMPONENT, jobPrefixes = listOf(JOB_PREFIX)))
        assertEquals("ready", api.state)

        readyPipeline(id = 101, sha = head, jobName = WEB_JOB_PREFIX)
        val web = CovdClient.ensure(request(component = "web", jobPrefixes = listOf(WEB_JOB_PREFIX)))
        assertEquals("ready", web.state)

        val apiPath = File(assertNotNull(api.cov4Path))
        val webPath = File(assertNotNull(web.cov4Path))
        assertNotEquals(apiPath, webPath)
        assertEquals("$head-$COMPONENT.cov4", apiPath.name)
        assertEquals("$head-web.cov4", webPath.name)
        assertEquals(101L, web.pipelineId)
        assertTrue(apiPath.isFile)
        assertTrue(webPath.isFile)
        assertEquals(2, gitlab.downloads)
    }

    @Test
    fun `the trace names the chosen pipeline`() {
        readyPipeline(id = 100, sha = head)

        // The typed EnsureResponse has no `trace` field, so the daemon is asked directly with
        // the same request body the client sends.
        assertNotNull("the daemon must start", CovdClient.artifacts(PROJECT_ID))
        val handshake = assertNotNull(CovdClient.currentHandshakeForTest())
        val body = CovdClient.encodeRequestForTest(request())

        val connection = URL("http://127.0.0.1:${handshake.port}/v1/ensure").openConnection() as HttpURLConnection
        val text = try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("Authorization", "Bearer ${handshake.token}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val root = JSON.parseToJsonElement(text).jsonObject
        assertEquals("ready", root.getValue("state").jsonPrimitive.content)
        val trace = assertNotNull("the answer must carry a trace", root["trace"]?.jsonObject)
        val decisions = trace.getValue("decisions").jsonArray.map { it.jsonObject }
        val chosen = decisions.filter { it.getValue("code").jsonPrimitive.content == "chosen" }
        assertEquals("exactly one pipeline is chosen", 1, chosen.size)
        val decision = chosen.single()
        assertEquals(100, decision.getValue("pipelineId").jsonPrimitive.int)
        assertEquals(head, decision.getValue("sha").jsonPrimitive.content)
        assertEquals("primary", decision.getValue("scope").jsonPrimitive.content)
        assertTrue("the trace must list the GitLab calls", trace.getValue("gitlab").jsonArray.isNotEmpty())
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private fun <T> assertNotNull(message: String, value: T?): T {
        org.junit.Assert.assertNotNull(message, value)
        return value!!
    }

    private companion object {
        const val WEB_JOB_PREFIX = "test:behat:coverage:web"

        val JSON = Json { ignoreUnknownKeys = true }
    }
}
