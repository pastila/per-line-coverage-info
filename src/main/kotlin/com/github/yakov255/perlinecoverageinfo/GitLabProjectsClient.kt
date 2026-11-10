package com.github.yakov255.perlinecoverageinfo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

@Serializable
data class GitLabProject(
    val id: Long,
    val name: String = "",
    val pathWithNamespace: String = "",
    val webUrl: String = "",
)

/**
 * Minimal GitLab client for the settings screen only: listing the projects the token can
 * access. Everything coverage-related (pipelines, jobs, artifacts) lives in the covd daemon.
 */
class GitLabProjectsClient(
    private val baseUrl: String,
    private val token: String,
) {

    private val log = CoverageLog.get(GitLabProjectsClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    fun searchProjects(query: String): List<GitLabProject> =
        request("/api/v4/projects?search=${encode(query)}&membership=true&per_page=20")

    fun listMemberProjects(): List<GitLabProject> {
        val result = mutableListOf<GitLabProject>()
        for (page in 1..5) {
            val batch = request("/api/v4/projects?membership=true&per_page=100&page=$page&order_by=last_activity_at")
            result.addAll(batch)
            if (batch.size < 100) break
        }
        return result
    }

    private fun request(endpoint: String): List<GitLabProject> {
        val connection = URI("$baseUrl$endpoint").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                log.warn("GitLab projects request failed: HTTP $status $error")
                return emptyList()
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GitLabProject.serializer()), body)
        } catch (e: Exception) {
            log.warn("GitLab projects request failed: ${e.message}")
            return emptyList()
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
}
