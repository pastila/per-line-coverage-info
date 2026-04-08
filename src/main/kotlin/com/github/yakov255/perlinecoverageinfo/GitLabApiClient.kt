package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GitLabApiClient(baseUrl: String, private val privateToken: String) {

    private val log = CoverageLog.get(GitLabApiClient::class.java)
    private val baseUrl = baseUrl.trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private inline fun <reified T> makeRequest(endpoint: String): T {
        val url = "${this.baseUrl}$endpoint"
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
                ),
                kind = CoverageErrorKind.GITLAB_API,
            )
        }
        return try {
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

    private fun makeRawRequest(endpoint: String): ByteArray {
        val url = "${this.baseUrl}$endpoint"
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
                ),
                kind = CoverageErrorKind.GITLAB_API,
            )
        }
        return response.body()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)

    fun searchProjects(query: String): List<GitLabProject> =
        makeRequest("/api/v4/projects?search=${encode(query)}&membership=true&per_page=20")

    fun listMemberProjects(): List<GitLabProject> {
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

    fun listPipelines(
        projectId: Long,
        ref: String,
        status: String = "success",
        perPage: Int = 100,
    ): List<GitLabPipeline> =
        makeRequest(
            "/api/v4/projects/$projectId/pipelines" +
                "?ref=${encode(ref)}&status=${encode(status)}&per_page=$perPage" +
                "&order_by=updated_at&sort=desc"
        )

    fun listPipelineJobs(
        projectId: Long,
        pipelineId: Long,
        perPage: Int = 100,
    ): List<GitLabJob> =
        makeRequest("/api/v4/projects/$projectId/pipelines/$pipelineId/jobs?per_page=$perPage")

    fun downloadJobArtifacts(projectId: Long, jobId: Long): ByteArray =
        makeRawRequest("/api/v4/projects/$projectId/jobs/$jobId/artifacts")

    fun getPipelineCommits(projectId: Long, ref: String): List<PipelineCommit> =
        listPipelines(projectId, ref).map { PipelineCommit(commitHash = it.sha, pipelineId = it.id) }

    /**
     * Computes merge-base server-side via GitLab API.
     * Returns the merge-base commit SHA, or null if the refs are unknown or have no common ancestor.
     */
    fun getMergeBase(projectId: Long, ref1: String, ref2: String): String? {
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
