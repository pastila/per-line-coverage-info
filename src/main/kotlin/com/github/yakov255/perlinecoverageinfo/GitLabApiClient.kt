package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Flow.Subscription
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.net.http.HttpResponse.BodyHandler
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpResponse.BodySubscriber
import java.net.http.HttpResponse.BodySubscribers
import java.net.http.HttpResponse.ResponseInfo

class GitLabApiClient(baseUrl: String, private val privateToken: String) : GitLabApi {

    private val log = CoverageLog.get(GitLabApiClient::class.java)
    private val baseUrl = baseUrl.trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val asyncExecutor: ExecutorService = Executors.newFixedThreadPool(5)
    private val retryScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    private val retryDelays = listOf(1000L, 3000L)

    private fun isRetryable(e: Throwable): Boolean {
        if (e !is CoverageApiException) return true
        return when (e.kind) {
            CoverageErrorKind.NETWORK -> true
            CoverageErrorKind.GITLAB_API -> {
                val httpStatus = e.details["httpStatus"]
                httpStatus != null && (httpStatus.startsWith("5") || httpStatus == "429")
            }
            else -> false
        }
    }

    /** Retry-After (in seconds) → milliseconds, or null when not applicable. */
    private fun retryAfterMs(response: HttpResponse<*>): String? {
        if (response.statusCode() != 429) return null
        return response.headers().firstValue("Retry-After")
            .map { (it.toLongOrNull() ?: 1L) * 1000L }
            .map { it.toString() }
            .orElse(null)
    }

    private fun retryDelayMs(e: Exception, attempt: Int): Long {
        val explicit = (e as? CoverageApiException)?.details?.get("retryAfterMs")?.toLongOrNull()
        return explicit ?: retryDelays[attempt - 1]
    }

    private fun <T> retrySync(url: String, block: () -> T): T {
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                if (!isRetryable(e) || attempt == maxAttempts) throw e
                val delayMs = retryDelayMs(e, attempt)
                log.info("Coverage: retrying $url after ${delayMs}ms (attempt $attempt/$maxAttempts)")
                Thread.sleep(delayMs)
            }
        }
        throw IllegalStateException("Unreachable")
    }

    private inline fun <reified T> makeRequest(endpoint: String): T {
        val url = "${this.baseUrl}$endpoint"
        return retrySync(url) {
            log.debug("GitLab API request: GET $url")
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .header("PRIVATE-TOKEN", privateToken)
                .build()
            val response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                log.warn("GitLab API network error for $url", e)
                throw CoverageApiException(
                    "Network error contacting GitLab API",
                    e,
                    mapOf("url" to url, "error" to e.message),
                    kind = CoverageErrorKind.NETWORK,
                )
            }
            log.debug("GitLab API response: HTTP ${response.statusCode()} for $url, body=${response.body().take(200)}")
            if (response.statusCode() != 200) {
                val body = response.body().take(500)
                log.warn("GitLab API returned HTTP ${response.statusCode()} for $url. Body: $body")
                throw CoverageApiException(
                    "GitLab API returned HTTP ${response.statusCode()}",
                    details = mapOf(
                        "url" to url,
                        "httpStatus" to response.statusCode().toString(),
                        "responseBody" to body,
                        "retryAfterMs" to retryAfterMs(response),
                    ),
                    kind = CoverageErrorKind.GITLAB_API,
                )
            }
            try {
                json.decodeFromString<T>(response.body())
            } catch (e: Exception) {
                log.warn("GitLab API JSON parse error for $url", e)
                throw CoverageApiException(
                    "Failed to parse GitLab API response",
                    e,
                    mapOf("url" to url, "error" to e.message, "body" to response.body().take(500)),
                    kind = CoverageErrorKind.PARSE,
                )
            }
        }
    }

    private fun makeRawRequest(endpoint: String): ByteArray {
        val url = "${this.baseUrl}$endpoint"
        return retrySync(url) {
            log.info("GitLab API raw request: GET $url")
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("PRIVATE-TOKEN", privateToken)
                .build()
            val response = try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                log.warn("GitLab API network error for $url", e)
                throw CoverageApiException(
                    "Network error contacting GitLab API",
                    e,
                    mapOf("url" to url, "error" to e.message),
                    kind = CoverageErrorKind.NETWORK,
                )
            }
            log.info("GitLab API raw response: HTTP ${response.statusCode()} for $url (${response.body().size} bytes)")
            if (response.statusCode() != 200) {
                log.warn("GitLab API returned HTTP ${response.statusCode()} for $url")
                throw CoverageApiException(
                    "GitLab API returned HTTP ${response.statusCode()}",
                    details = mapOf(
                        "url" to url,
                        "httpStatus" to response.statusCode().toString(),
                        "retryAfterMs" to retryAfterMs(response),
                    ),
                    kind = CoverageErrorKind.GITLAB_API,
                )
            }
            response.body()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)

    override fun searchProjects(query: String): List<GitLabProject> =
        makeRequest("/api/v4/projects?search=${encode(query)}&membership=true&per_page=20")

    override fun listMemberProjects(): List<GitLabProject> {
        val all = mutableListOf<GitLabProject>()
        var page = 1
        while (true) {
            val batch: List<GitLabProject> = makeRequest(
                "/api/v4/projects?membership=true&per_page=100&page=$page&order_by=last_activity_at"
            )
            all.addAll(batch)
            if (batch.size < 100) break
            page++
        }
        return all
    }

    override fun listPipelines(
        projectId: Long,
        ref: String,
        status: String?,
        perPage: Int,
    ): List<GitLabPipeline> =
        makeRequest(
            "/api/v4/projects/$projectId/pipelines" +
                "?ref=${encode(ref)}" +
                (status?.let { "&status=${encode(it)}" } ?: "") +
                "&per_page=$perPage&order_by=updated_at&sort=desc"
        )

    override fun listPipelineJobs(
        projectId: Long,
        pipelineId: Long,
        perPage: Int,
    ): List<GitLabJob> =
        makeRequest("/api/v4/projects/$projectId/pipelines/$pipelineId/jobs?per_page=$perPage")

    override fun downloadSingleArtifactFile(projectId: Long, jobId: Long, artifactPath: String): ByteArray =
        makeRawRequest("/api/v4/projects/$projectId/jobs/$jobId/artifacts/${encode(artifactPath)}")

    override fun downloadSingleArtifactFileAsync(
        projectId: Long,
        jobId: Long,
        artifactPath: String,
        onProgress: ((received: Long, total: Long) -> Unit)?,
    ): CompletableFuture<ByteArray> =
        makeRawRequestAsync(
            "/api/v4/projects/$projectId/jobs/$jobId/artifacts/${encode(artifactPath)}",
            onProgress,
        )

    /**
     * A [BodySubscriber] that wraps the standard [BodySubscribers.ofByteArray]
     * and reports progress via [onProgress] on every chunk.
     */
    private class ProgressBodySubscriber(
        private val totalBytes: Long,
        private val onProgress: (Long) -> Unit,
    ) : BodySubscriber<ByteArray> {

        private val delegate = BodySubscribers.ofByteArray()
        private var received = 0L

        override fun getBody(): CompletionStage<ByteArray> = delegate.body

        override fun onSubscribe(subscription: Subscription) {
            delegate.onSubscribe(subscription)
        }

        override fun onNext(items: List<ByteBuffer>) {
            for (buf in items) {
                received += buf.remaining().toLong()
            }
            onProgress(received)
            delegate.onNext(items)
        }

        override fun onError(t: Throwable) {
            delegate.onError(t)
        }

        override fun onComplete() {
            delegate.onComplete()
        }
    }

    private fun makeRawRequestAsync(
        endpoint: String,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    ): CompletableFuture<ByteArray> {
        val url = "${this.baseUrl}$endpoint"

        fun attempt(n: Int): CompletableFuture<ByteArray> {
            log.info("GitLab API raw request (async): GET $url (attempt $n)")
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("PRIVATE-TOKEN", privateToken)
                .timeout(Duration.ofSeconds(30))
                .build()

            val bodyHandler: BodyHandler<ByteArray> = if (onProgress != null) {
                BodyHandler { responseInfo: ResponseInfo ->
                    val total = responseInfo.headers()
                        .firstValue("content-length")
                        .map(String::toLong)
                        .orElse(-1L)
                    ProgressBodySubscriber(total) { received -> onProgress(received, total) }
                }
            } else {
                BodyHandlers.ofByteArray()
            }

            return httpClient.sendAsync(request, bodyHandler)
                .orTimeout(30, TimeUnit.SECONDS)
                .thenApplyAsync({ response ->
                    log.info("GitLab API raw response: HTTP ${response.statusCode()} for $url (${response.body().size} bytes) (attempt $n)")
                    if (response.statusCode() != 200) {
                        log.warn("GitLab API returned HTTP ${response.statusCode()} for $url (attempt $n)")
                        throw CoverageApiException(
                            "GitLab API returned HTTP ${response.statusCode()}",
                            details = mapOf(
                                "url" to url,
                                "httpStatus" to response.statusCode().toString(),
                                "retryAfterMs" to retryAfterMs(response),
                            ),
                            kind = CoverageErrorKind.GITLAB_API,
                        )
                    }
                    response.body()
                }, asyncExecutor)
                .exceptionallyComposeAsync({ raw ->
                    val e = (raw as? CompletionException)?.cause ?: raw
                    if (isRetryable(e) && n < 3) {
                        val explicit = (e as? CoverageApiException)?.details?.get("retryAfterMs")?.toLongOrNull()
                        val delayMs = explicit ?: retryDelays[n - 1]
                        log.info("Coverage: retrying $url after ${delayMs}ms (attempt $n/3)")
                        val delayed = CompletableFuture<ByteArray>()
                        retryScheduler.schedule({
                            attempt(n + 1).whenComplete { r, ex ->
                                if (ex != null) delayed.completeExceptionally(ex)
                                else delayed.complete(r)
                            }
                        }, delayMs, TimeUnit.MILLISECONDS)
                        delayed
                    } else {
                        CompletableFuture.failedFuture(e)
                    }
                }, asyncExecutor)
        }

        return attempt(1)
    }

    override fun getPipelineCommits(projectId: Long, ref: String): List<PipelineCommit> =
        listPipelines(projectId, ref).map { PipelineCommit(commitHash = it.sha, pipelineId = it.id) }

    /**
     * Computes merge-base server-side via GitLab API.
     * Returns the merge-base commit SHA, or null if the refs are unknown or have no common ancestor.
     */
    override fun getMergeBase(projectId: Long, ref1: String, ref2: String): String? {
        val endpoint = "/api/v4/projects/$projectId/repository/merge_base" +
            "?refs[]=${encode(ref1)}&refs[]=${encode(ref2)}"
        return try {
            val commit: GitLabCommit = makeRequest(endpoint)
            commit.id
        } catch (e: CoverageApiException) {
            if (e.details["httpStatus"] == "400" || e.details["httpStatus"] == "404") {
                log.info("GitLab merge_base returned ${e.details["httpStatus"]} for refs [$ref1, $ref2] — refs not found or no common ancestor")
                null
            } else {
                throw e
            }
        }
    }
}
