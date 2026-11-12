package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets

/**
 * Minimal anonymous GitLab REST client for the inventory.
 *
 * It never sends a credential: no `Authorization`, no `PRIVATE-TOKEN`. Requests are sequential,
 * with a pause before each one, so a branch inventory stays polite to the shared GitLab.
 * Transient server answers (502/503/504, 429) and I/O failures are retried with a growing pause.
 */
class GitLabClient(
    private val baseUrl: String,
    private val pauseMs: Long = 150,
    private val maxAttempts: Int = 4,
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** GETs [path] (already URL-encoded, starting with `/api/v4/...`) and returns the body. */
    fun get(path: String): String {
        val url = baseUrl.trimEnd('/') + path
        var attempt = 0
        while (true) {
            attempt++
            if (pauseMs > 0) sleeper(pauseMs)
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "per-line-coverage-info-real-project")
                .GET()
                .build()
            val status: Int
            val body: String
            try {
                val response = http.send(request, HttpResponse.BodyHandlers.ofString())
                status = response.statusCode()
                body = response.body()
            } catch (e: IOException) {
                if (attempt >= maxAttempts) {
                    throw IOException("GET $url failed after $attempt attempts: ${e.message}", e)
                }
                sleeper(backoffMs(attempt))
                continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
            }
            if (status in 200..299) return body
            if (status in RETRY_STATUSES) {
                if (attempt >= maxAttempts) {
                    throw IOException("GET $url: HTTP $status after $attempt attempts")
                }
                sleeper(backoffMs(attempt))
                continue
            }
            throw IOException("GET $url: HTTP $status")
        }
    }

    private fun backoffMs(attempt: Int): Long = 2_000L * attempt

    private companion object {
        val RETRY_STATUSES = setOf(429, 502, 503, 504)
    }
}

/**
 * Reads the recent pipelines of one project and keeps the ones that published coverage.
 *
 * One request per page of pipelines plus one request per pipeline for its jobs; pages and
 * pipelines are fetched strictly sequentially.
 */
class GitLabInventory(
    private val client: GitLabClient = GitLabClient(defaultGitLabUrl()),
    private val projectId: String = RealProjectSettings.value("realProject.projectId", "335"),
    private val pageSize: Int = 100,
    private val maxPages: Int = 20,
    private val progress: (String) -> Unit = {},
) {
    /** Newest [limit] pipelines with [status] (optionally only [ref]), at most [maxPages] pages. */
    fun pipelines(status: String, limit: Int, ref: String? = null): List<ApiPipeline> {
        val result = ArrayList<ApiPipeline>(limit.coerceAtMost(pageSize))
        val refQuery = ref?.let { "&ref=${URLEncoder.encode(it, StandardCharsets.UTF_8)}" } ?: ""
        var page = 1
        while (result.size < limit && page <= maxPages) {
            val body = client.get(
                "/api/v4/projects/$projectId/pipelines?status=$status&per_page=$pageSize&page=$page$refQuery",
            )
            val batch = InventoryJson.parsePipelines(body)
            if (batch.isEmpty()) break
            result += batch.take(limit - result.size)
            if (batch.size < pageSize) break
            page++
        }
        return result
    }

    /** All jobs of one pipeline (a pipeline has a few dozen jobs, so one page is enough). */
    fun jobs(pipelineId: Long): List<ApiJob> {
        val result = ArrayList<ApiJob>()
        var page = 1
        while (page <= 3) {
            val body = client.get(
                "/api/v4/projects/$projectId/pipelines/$pipelineId/jobs?per_page=$pageSize&page=$page",
            )
            val batch = InventoryJson.parseJobs(body)
            if (batch.isEmpty()) break
            result += batch
            if (batch.size < pageSize) break
            page++
        }
        return result
    }

    /** Reads [limit] successful pipelines and summarizes their coverage jobs. */
    fun scan(limit: Int = RealProjectSettings.int("realProject.pipelines", 200)): InventorySnapshot {
        val pipelines = pipelines(status = "success", limit = limit)
        val coverage = ArrayList<PipelineCoverage>(pipelines.size)
        pipelines.forEachIndexed { index, pipeline ->
            coverage += CoverageSummarizer.summarize(pipeline, jobs(pipeline.id))
            if ((index + 1) % 25 == 0) progress("read jobs of ${index + 1}/${pipelines.size} pipelines")
        }
        return InventorySnapshot(projectId, pipelines.size, coverage)
    }
}

/** System properties (`-DrealProject.*`) win over `REAL_PROJECT_*` environment variables. */
object RealProjectSettings {
    fun value(name: String, default: String): String =
        System.getProperty(name)
            ?: System.getenv(envName(name))
            ?: default

    fun int(name: String, default: Int): Int = value(name, default.toString()).toIntOrNull() ?: default

    fun long(name: String, default: Long): Long = value(name, default.toString()).toLongOrNull() ?: default

    fun flag(name: String, default: Boolean): Boolean =
        value(name, default.toString()).toBooleanStrictOrNull() ?: default

    /** `realProject.worktree` -> `REAL_PROJECT_WORKTREE`. */
    fun envName(property: String): String = property.uppercase().replace('.', '_')
}

fun defaultGitLabUrl(): String = RealProjectSettings.value("realProject.gitlab", "https://gitlab.raketa.in")
