package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

@Serializable
data class GitLabProject(
    val id: Long,
    val name: String = "",
    @SerialName("path_with_namespace") val pathWithNamespace: String = "",
    @SerialName("web_url") val webUrl: String = "",
)

/**
 * Minimal GitLab client for the settings screen only: listing the projects the token can
 * access. Everything coverage-related (pipelines, jobs, artifacts) lives in the covd daemon.
 *
 * Calls block: run them on a pooled thread. Failures are thrown as [IOException] so the caller
 * can show the reason; an empty list means GitLab really returned no projects.
 */
class GitLabProjectsClient(
    private val baseUrl: String,
    private val token: String,
) {

    private val log = CoverageLog.get(GitLabProjectsClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Throws(IOException::class)
    fun searchProjects(query: String): List<GitLabProject> =
        request("/api/v4/projects?search=${encode(query)}&membership=true&per_page=20")

    @Throws(IOException::class)
    fun listMemberProjects(): List<GitLabProject> {
        val deadline = System.nanoTime() + TOTAL_BUDGET_NANOS
        val result = mutableListOf<GitLabProject>()
        for (page in 1..5) {
            val batch = request(
                "/api/v4/projects?membership=true&per_page=100&page=$page&order_by=last_activity_at",
                deadline,
            )
            result.addAll(batch)
            if (batch.size < 100) break
        }
        return result
    }

    /**
     * gitlab.raketa.in answers the same project-list query in 3 s or with a 504 after a minute,
     * so a transient failure (timeout, 5xx) is retried with a growing pause before it is thrown.
     * No attempt starts after [deadline].
     */
    private fun request(endpoint: String, deadline: Long = System.nanoTime() + TOTAL_BUDGET_NANOS): List<GitLabProject> {
        var failure: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                if (System.nanoTime() >= deadline) break
                log.warn("GitLab projects request retry $attempt/$MAX_ATTEMPTS after: ${failure?.message}")
                try {
                    Thread.sleep(BACKOFF_MS * (attempt - 1))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted while waiting to retry", e)
                }
            }
            try {
                return requestOnce(endpoint)
            } catch (e: RetryableException) {
                failure = e
            }
        }
        throw failure ?: IOException("GitLab projects request failed")
    }

    /** Throws [RetryableException] for timeouts, connection errors and 5xx; [IOException] otherwise. */
    private fun requestOnce(endpoint: String): List<GitLabProject> {
        val connection = URI("$baseUrl$endpoint").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                log.warn("GitLab projects request failed: ${e::class.simpleName}: ${e.message}")
                throw RetryableException("${e::class.simpleName}: ${e.message}", e)
            }
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)
                log.warn("GitLab projects request failed: HTTP $status $error")
                val message = "HTTP $status"
                if (status >= 500) throw RetryableException(message) else throw IOException(message)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return try {
                json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitLabProject.serializer()), body)
            } catch (e: Exception) {
                // Surfaced as an exception (shown in the dialog), never as an empty list: that
                // is how a field-mapping bug stayed hidden before.
                log.warn("GitLab projects response cannot be parsed: ${e.message}", e)
                throw IOException("unexpected GitLab response: ${e.message}", e)
            }
        } catch (e: java.io.InterruptedIOException) {
            throw RetryableException("${e::class.simpleName}: ${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    private class RetryableException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 1_000L
        const val TOTAL_BUDGET_NANOS = 120_000_000_000L
    }
}
