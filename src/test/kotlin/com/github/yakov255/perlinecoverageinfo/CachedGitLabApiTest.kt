package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CachedGitLabApiTest {

    private fun fastLimiter() = RateLimiter(capacity = 1000.0, tokensPerSecond = 1000.0)

    @Test
    fun `identical pipeline request is cached`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        try {
            api.listPipelines(1, "master", "success", 100)
            api.listPipelines(1, "master", "success", 100)
            assertEquals(1, fake.pipelinesCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `different refs are not conflated`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        try {
            api.listPipelines(1, "master", "success", 100)
            api.listPipelines(1, "feature", "success", 100)
            assertEquals(2, fake.pipelinesCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `jobs cached by pipeline id`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        try {
            api.listPipelineJobs(1, 42, 100)
            api.listPipelineJobs(1, 42, 100)
            assertEquals(1, fake.jobsCalls.get())
            api.listPipelineJobs(1, 43, 100)
            assertEquals(2, fake.jobsCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `mergeBase cached but null results are not`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        try {
            api.getMergeBase(1, "a", "b")
            api.getMergeBase(1, "a", "b")
            assertEquals(1, fake.mergeBaseCalls.get())

            api.getMergeBase(1, "missing", "b")
            api.getMergeBase(1, "missing", "b")
            assertEquals(3, fake.mergeBaseCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `concurrent identical calls coalesce on the single worker thread`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        val pool = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (1..8).map {
                pool.submit {
                    start.await()
                    api.listPipelines(1, "master", "success", 100)
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            // All callers share one delegate call (cached) and it never overlaps.
            assertEquals(1, fake.pipelinesCalls.get())
            assertEquals(1, fake.maxActivePipelines)
        } finally {
            pool.shutdownNow()
            api.shutdown()
        }
    }

    @Test
    fun `searchProjects is not cached`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        try {
            api.searchProjects("x")
            api.searchProjects("x")
            assertEquals(2, fake.searchCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `artifact downloads pass through uncached`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        try {
            api.downloadSingleArtifactFile(1, 7, "coverage-reports/coverage.covt.gz")
            api.downloadSingleArtifactFile(1, 7, "coverage-reports/coverage.covt.gz")
            assertEquals(2, fake.downloadsCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `invalidateJobs forces a fresh fetch of the jobs list`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(fake, rateLimiter = fastLimiter())
        try {
            api.listPipelineJobs(1, 42, 100)
            api.listPipelineJobs(1, 42, 100)
            assertEquals(1, fake.jobsCalls.get())

            api.invalidateJobs(1, 42)
            api.listPipelineJobs(1, 42, 100)
            assertEquals(2, fake.jobsCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `cache expires after ttl`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(
            fake,
            pipelinesTtlMs = 100,
            jobsTtlMs = 100,
            mergeBaseTtlMs = 100,
            rateLimiter = fastLimiter(),
        )
        try {
            api.listPipelines(1, "master", "success", 100)
            Thread.sleep(250)
            api.listPipelines(1, "master", "success", 100)
            assertEquals(2, fake.pipelinesCalls.get())
        } finally {
            api.shutdown()
        }
    }

    @Test
    fun `rate limiter blocks metadata calls until a token refills`() {
        val fake = FakeGitLabApi()
        val api = CachedGitLabApi(
            fake,
            pipelinesTtlMs = 0,
            jobsTtlMs = 0,
            mergeBaseTtlMs = 0,
            rateLimiter = RateLimiter(capacity = 1.0, tokensPerSecond = 1.0),
        )
        try {
            api.listPipelines(1, "a", null, 100)
            val started = System.currentTimeMillis()
            api.listPipelines(1, "b", null, 100)
            val elapsed = System.currentTimeMillis() - started
            assertTrue("expected to wait ~1s for a token, took $elapsed ms", elapsed >= 800)
            assertEquals(2, fake.pipelinesCalls.get())
        } finally {
            api.shutdown()
        }
    }

    private class FakeGitLabApi : GitLabApi {
        val pipelinesCalls = AtomicInteger(0)
        val jobsCalls = AtomicInteger(0)
        val mergeBaseCalls = AtomicInteger(0)
        val downloadsCalls = AtomicInteger(0)
        val searchCalls = AtomicInteger(0)

        private val active = AtomicInteger(0)
        @Volatile
        var maxActivePipelines = 0
            private set

        private fun <T> trackConcurrency(block: () -> T): T {
            val a = active.incrementAndGet()
            maxActivePipelines = maxOf(maxActivePipelines, a)
            return try {
                block()
            } finally {
                active.decrementAndGet()
            }
        }

        override fun searchProjects(query: String): List<GitLabProject> {
            searchCalls.incrementAndGet()
            return emptyList()
        }

        override fun listMemberProjects(): List<GitLabProject> = emptyList()

        override fun listPipelines(projectId: Long, ref: String, status: String?, perPage: Int): List<GitLabPipeline> {
            return trackConcurrency {
                pipelinesCalls.incrementAndGet()
                Thread.sleep(30)
                listOf(
                    GitLabPipeline(
                        id = pipelinesCalls.get().toLong(),
                        sha = "sha-$ref",
                        ref = ref,
                        status = "success",
                        webUrl = "",
                    )
                )
            }
        }

        override fun listPipelineJobs(projectId: Long, pipelineId: Long, perPage: Int): List<GitLabJob> {
            jobsCalls.incrementAndGet()
            return listOf(
                GitLabJob(
                    id = pipelineId,
                    name = "test:behat:raketa",
                    status = "success",
                    pipeline = GitLabJobPipeline(id = pipelineId),
                )
            )
        }

        override fun downloadSingleArtifactFile(projectId: Long, jobId: Long, artifactPath: String): ByteArray {
            downloadsCalls.incrementAndGet()
            return "data".toByteArray()
        }

        override fun downloadSingleArtifactFileAsync(
            projectId: Long,
            jobId: Long,
            artifactPath: String,
            onProgress: ((received: Long, total: Long) -> Unit)?,
        ): CompletableFuture<ByteArray> = CompletableFuture.completedFuture("data".toByteArray())

        override fun getPipelineCommits(projectId: Long, ref: String): List<PipelineCommit> =
            listPipelines(projectId, ref).map { PipelineCommit(commitHash = it.sha, pipelineId = it.id) }

        override fun getMergeBase(projectId: Long, ref1: String, ref2: String): String? {
            mergeBaseCalls.incrementAndGet()
            return if (ref1 == "missing") null else "merge-$ref1-$ref2"
        }
    }
}
