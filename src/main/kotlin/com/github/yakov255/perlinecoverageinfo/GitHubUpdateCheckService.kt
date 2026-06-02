package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManagerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Polls GitHub Releases API and decides whether the user should be notified
 * about a newer plugin version available outside JetBrains Marketplace.
 *
 * The service is intentionally side-effect-free with respect to the IDE UI:
 * it only fetches/parses/compares and returns a [Result]. Actual notification
 * is performed by [GitHubUpdateNotifier].
 */
@Service(Service.Level.APP)
class GitHubUpdateCheckService {

    private val log = CoverageLog.get(GitHubUpdateCheckService::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Outcome of an update check. */
    sealed class Result {
        /** Throttled (last check too recent) — nothing to do. */
        object Throttled : Result()
        /** User opted out — nothing to do. */
        object OptedOut : Result()
        /** No newer release available. */
        object UpToDate : Result()
        /** A newer release is available. */
        data class UpdateAvailable(
            val currentVersion: String,
            val latestVersion: String,
            val releaseUrl: String,
        ) : Result()
        /** Network or parse error. Logged but not surfaced to the user. */
        data class Failed(val reason: String) : Result()
    }

    @Serializable
    private data class GitHubReleaseDto(
        val tag_name: String = "",
        val name: String = "",
        val html_url: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    /**
     * Checks for updates honoring [GitHubUpdateCheckSettings.dontCheckAgain] and
     * the [throttleMs] minimum interval between API calls.
     */
    suspend fun checkForUpdate(
        throttleMs: Long = TimeUnit.HOURS.toMillis(24),
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        val settings = GitHubUpdateCheckSettings.getInstance()
        if (settings.dontCheckAgain) return Result.OptedOut
        if (nowMs - settings.lastCheckedAtMs < throttleMs) return Result.Throttled

        val currentVersion = currentPluginVersion() ?: return Result.Failed("plugin descriptor not found")

        return try {
            val release = fetchLatestRelease()
            if (release == null) return Result.Failed("no release returned")
            val latestVersion = normalizeTag(release.tag_name)
            if (latestVersion.isEmpty()) return Result.Failed("empty tag_name")

            val cmp = SemVer.compare(latestVersion, currentVersion)
            settings.lastCheckedAtMs = nowMs
            if (cmp <= 0) {
                Result.UpToDate
            } else {
                Result.UpdateAvailable(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseUrl = release.html_url.ifBlank {
                        "https://github.com/$REPO/releases/tag/${release.tag_name}"
                    },
                )
            }
        } catch (e: Exception) {
            log.warn("GitHub update check failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fetchLatestRelease(): GitHubReleaseDto? {
        val url = "https://api.github.com/repos/$REPO/releases/latest"
        log.debug("GitHub update check: GET $url")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(10))
            .build()
        val response = withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
        if (response.statusCode() != 200) {
            log.info("GitHub update check: HTTP ${response.statusCode()} from $url")
            return null
        }
        return json.decodeFromString(GitHubReleaseDto.serializer(), response.body())
    }

    private fun currentPluginVersion(): String? {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: return null
        return descriptor.version
    }

    /** Strips a leading `v` from tags like `v2.3.0`. */
    private fun normalizeTag(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")

    companion object {
        const val PLUGIN_ID = "com.github.yakov255.perlinecoverageinfo"
        const val REPO = "yakov255/per-line-coverage-info"

        fun getInstance(): GitHubUpdateCheckService = service()
    }
}

/**
 * Minimal SemVer comparator with pre-release support.
 *
 * Rules (subset of https://semver.org §11):
 *   - MAJOR.MINOR.PATCH compared numerically.
 *   - A version with a pre-release tag has lower precedence than the same
 *     version without one (e.g. `2.2.0-alpha.1` < `2.2.0`).
 *   - Pre-release identifiers compared by ASCII / numerically per spec.
 *   - Build metadata (`+...`) is ignored.
 *   - Non-numeric / malformed segments fall back to lexicographic comparison
 *     so we never crash on unexpected tags.
 */
internal object SemVer {

    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: return a.compareTo(b)
        val pb = parse(b) ?: return a.compareTo(b)

        for (i in 0 until 3) {
            val cmp = pa.numbers[i].compareTo(pb.numbers[i])
            if (cmp != 0) return cmp
        }

        // Pre-release precedence: absent > present.
        if (pa.preRelease.isEmpty() && pb.preRelease.isNotEmpty()) return 1
        if (pa.preRelease.isNotEmpty() && pb.preRelease.isEmpty()) return -1
        if (pa.preRelease.isEmpty() && pb.preRelease.isEmpty()) return 0

        val ai = pa.preRelease
        val bi = pb.preRelease
        val n = minOf(ai.size, bi.size)
        for (i in 0 until n) {
            val x = ai[i]
            val y = bi[i]
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val cmp = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null && yn == null -> -1 // numeric < alpha
                xn == null && yn != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return ai.size.compareTo(bi.size)
    }

    private data class Parsed(val numbers: IntArray, val preRelease: List<String>)

    private fun parse(version: String): Parsed? {
        val trimmed = version.trim().substringBefore('+')
        if (trimmed.isEmpty()) return null
        val dashAt = trimmed.indexOf('-')
        val mainPart = if (dashAt >= 0) trimmed.substring(0, dashAt) else trimmed
        val preRelease = if (dashAt >= 0) trimmed.substring(dashAt + 1).split('.') else emptyList()

        val parts = mainPart.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = IntArray(3)
        for (i in 0 until 3) {
            val seg = parts.getOrNull(i) ?: "0"
            nums[i] = seg.toIntOrNull() ?: return null
        }
        return Parsed(nums, preRelease)
    }
}
