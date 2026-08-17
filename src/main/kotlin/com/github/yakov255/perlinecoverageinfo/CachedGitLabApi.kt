package com.github.yakov255.perlinecoverageinfo

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Shared, serialized, cached and rate-limited [GitLabApi] facade — the "worker"
 * of the plugin.
 *
 * Every metadata call (pipelines, jobs, merge-base) is executed on a single
 * dedicated worker thread, so concurrent callers (multiple project windows,
 * poller, resolver) are naturally serialized. Successful responses are cached
 * with a TTL, and only actual cache misses consume rate-limiter tokens — so
 * repeated identical requests cost zero network traffic and cannot overload
 * the GitLab API.
 *
 * Bulk artifact downloads are passed through unchanged (not serialized, not
 * rate-limited) so a large download never blocks metadata lookups.
 */
class CachedGitLabApi(
    private val delegate: GitLabApi,
    private val pipelinesTtlMs: Long = DEFAULT_PIPELINES_TTL_MS,
    private val jobsTtlMs: Long = DEFAULT_JOBS_TTL_MS,
    private val mergeBaseTtlMs: Long = DEFAULT_MERGE_BASE_TTL_MS,
    private val rateLimiter: RateLimiter = RateLimiter(DEFAULT_CAPACITY, DEFAULT_TOKENS_PER_SECOND),
) : GitLabApi {

    private val log = CoverageLog.get(CachedGitLabApi::class.java)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GitLabCoordinator-Worker").apply { isDaemon = true }
    }

    @Volatile
    private var workerThread: Thread? = null

    private val pipelinesCache = TtlCache<String, List<GitLabPipeline>>(pipelinesTtlMs)
    private val jobsCache = TtlCache<String, List<GitLabJob>>(jobsTtlMs)
    private val mergeBaseCache = TtlCache<String, String>(mergeBaseTtlMs)

    /** Runs [block] on the single worker thread (re-entrant safe). */
    private fun <T> onWorker(block: () -> T): T {
        if (Thread.currentThread() === workerThread) return block()
        val future = executor.submit(Callable<T> {
            if (workerThread == null) workerThread = Thread.currentThread()
            block()
        })
        return try {
            future.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Returns [key] from [cache], or loads it via [loader] (rate-limited).
     * [label] is a short human-readable description used in log messages, e.g.
     * `pipelines ref=master`. Every cache miss logs at INFO — this is the
     * single place where all real metadata API traffic is observable.
     */
    private fun <T> cached(label: String, key: String, cache: TtlCache<String, T>, loader: () -> T): T {
        return onWorker {
            cache.get(key)?.let {
                log.debug("GitLab worker: cache hit ($label)")
                return@onWorker it
            }
            val waitStart = System.nanoTime()
            val acquired = rateLimiter.acquire(RATE_LIMIT_WAIT_MS)
            val waitMs = (System.nanoTime() - waitStart) / 1_000_000
            if (!acquired) {
                log.warn("GitLab worker: rate limiter — no token within ${RATE_LIMIT_WAIT_MS}ms, proceeding without token ($label)")
            } else if (waitMs > 1_000) {
                log.info("GitLab worker: waited ${waitMs}ms for rate-limiter token ($label)")
            }
            log.info("GitLab worker: cache miss ($label), fetching")
            val value = loader()
            cache.put(key, value)
            value
        }
    }

    override fun listPipelines(projectId: Long, ref: String, status: String?, perPage: Int): List<GitLabPipeline> {
        val key = "pipelines:$projectId:$ref:$status:$perPage"
        return cached("pipelines ref=$ref", key, pipelinesCache) { delegate.listPipelines(projectId, ref, status, perPage) }
    }

    override fun listPipelineJobs(projectId: Long, pipelineId: Long, perPage: Int): List<GitLabJob> {
        val key = "jobs:$projectId:$pipelineId"
        return cached("jobs pipeline=$pipelineId", key, jobsCache) { delegate.listPipelineJobs(projectId, pipelineId, perPage) }
    }

    override fun getMergeBase(projectId: Long, ref1: String, ref2: String): String? {
        return onWorker {
            val key = "mergebase:$projectId:$ref1:$ref2"
            val label = "merge-base refs=$ref1,$ref2"
            mergeBaseCache.get(key)?.let {
                log.debug("GitLab worker: cache hit ($label)")
                return@onWorker it
            }
            val waitStart = System.nanoTime()
            val acquired = rateLimiter.acquire(RATE_LIMIT_WAIT_MS)
            val waitMs = (System.nanoTime() - waitStart) / 1_000_000
            if (!acquired) {
                log.warn("GitLab worker: rate limiter — no token within ${RATE_LIMIT_WAIT_MS}ms, proceeding without token ($label)")
            } else if (waitMs > 1_000) {
                log.info("GitLab worker: waited ${waitMs}ms for rate-limiter token ($label)")
            }
            log.info("GitLab worker: cache miss ($label), fetching")
            val result = delegate.getMergeBase(projectId, ref1, ref2)
            if (result != null) mergeBaseCache.put(key, result)
            result
        }
    }

    override fun getPipelineCommits(projectId: Long, ref: String): List<PipelineCommit> =
        listPipelines(projectId, ref).map { PipelineCommit(commitHash = it.sha, pipelineId = it.id) }

    // Pass-through: not cached, not serialized, not rate-limited.
    override fun searchProjects(query: String): List<GitLabProject> = delegate.searchProjects(query)

    override fun listMemberProjects(): List<GitLabProject> = delegate.listMemberProjects()

    override fun downloadSingleArtifactFile(projectId: Long, jobId: Long, artifactPath: String): ByteArray =
        delegate.downloadSingleArtifactFile(projectId, jobId, artifactPath)

    override fun downloadSingleArtifactFileAsync(
        projectId: Long,
        jobId: Long,
        artifactPath: String,
        onProgress: ((received: Long, total: Long) -> Unit)?,
    ): CompletableFuture<ByteArray> =
        delegate.downloadSingleArtifactFileAsync(projectId, jobId, artifactPath, onProgress)

    fun invalidate() {
        pipelinesCache.clear()
        jobsCache.clear()
        mergeBaseCache.clear()
        log.debug("GitLab worker: invalidated all metadata caches")
    }

    /**
     * Drops the cached jobs for [pipelineId] so the next [listPipelineJobs]
     * fetches fresh status. Used by the artifact wait-loop, which must observe
     * running jobs transitioning to success/failure instead of serving the
     * long-TTL cached snapshot.
     */
    fun invalidateJobs(projectId: Long, pipelineId: Long) {
        jobsCache.invalidate("jobs:$projectId:$pipelineId")
        log.debug("GitLab worker: invalidated jobs cache for pipeline $pipelineId")
    }

    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        private const val DEFAULT_PIPELINES_TTL_MS = 120_000L
        private const val DEFAULT_JOBS_TTL_MS = 600_000L
        private const val DEFAULT_MERGE_BASE_TTL_MS = 300_000L
        private const val DEFAULT_CAPACITY = 12.0
        private const val DEFAULT_TOKENS_PER_SECOND = 1.0 / 3.0
        private const val RATE_LIMIT_WAIT_MS = 30_000L
    }
}
