package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CoverageApiClient(apiEndpoint: String, private val bearerToken: String = "", private val project: Project) {

    private val log = Logger.getInstance(CoverageApiClient::class.java)
    private val baseUrl = apiEndpoint.trimEnd('/')

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private inline fun <reified T> makeRequest(endpoint: String): T? {
        val url = "$baseUrl$endpoint"
        return try {
            log.warn("Coverage API request: GET $url")
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .header("Accept", "application/json")
            if (bearerToken.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $bearerToken")
            }
            val request = requestBuilder.build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                log.warn("Coverage API response 200 for $url")
                Json.decodeFromString<T>(response.body())
            } else {
                log.warn("Coverage API response ${response.statusCode()} for $url: ${response.body().take(500)}")
                null
            }
        } catch (e: Exception) {
            log.warn("Coverage API request failed for $url: ${e.message}", e)
            null
        }
    }

    fun getBranchCommits(branch: String): BranchCommitsResponse? =
        makeRequest<BranchCommitsResponse>("/api/ide/branches/$branch/commits")

    fun getFileCoverage(commitHash: String, filePath: String): IdeFileCoverageResponse? =
        makeRequest<IdeFileCoverageResponse>("/api/ide/commits/$commitHash/coverage/$filePath")

    private fun runGitCommand(gitRoot: File, vararg args: String): String? =
        Companion.runGitCommand(gitRoot, *args)

    private fun getDefaultBranch(gitRoot: File): String {
        // Try to detect the default branch from the remote
        val symbolic = runGitCommand(gitRoot, "symbolic-ref", "refs/remotes/origin/HEAD")
        if (symbolic != null) {
            return symbolic.removePrefix("refs/remotes/origin/")
        }
        // Fallback: try main, then master
        val branches = runGitCommand(gitRoot, "branch", "--list", "main", "master") ?: return "main"
        return if (branches.lines().any { it.trim().trimStart('*').trim() == "main" }) "main" else "master"
    }

    private fun getMergeBase(gitRoot: File, defaultBranch: String): String? {
        return runGitCommand(gitRoot, "merge-base", "HEAD", defaultBranch)
            ?: runGitCommand(gitRoot, "merge-base", "HEAD", "origin/$defaultBranch")
    }

    private fun getLocalAncestorCommits(gitRoot: File, startRef: String, limit: Int = 100): List<String> {
        val output = runGitCommand(gitRoot, "log", "--format=%H", "-$limit", startRef) ?: return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    /**
     * Finds the best commit hash to use for coverage queries:
     * 1. Gets the merge-base between HEAD and the default branch
     * 2. Fetches available commits from the API for that branch
     * 3. Finds the nearest ancestor commit that has coverage data
     */
    private fun findCoverageCommit(gitRoot: File): String? {
        val defaultBranch = getDefaultBranch(gitRoot)
        log.warn("Coverage: detected default branch='$defaultBranch'")

        val mergeBase = getMergeBase(gitRoot, defaultBranch)
        if (mergeBase == null) {
            log.warn("Coverage: merge-base not found for HEAD and '$defaultBranch'")
            return null
        }
        log.warn("Coverage: merge-base=$mergeBase")

        val apiResponse = getBranchCommits(defaultBranch)
        if (apiResponse == null) {
            log.warn("Coverage: failed to fetch branch commits from API for branch='$defaultBranch'")
            return null
        }
        log.warn("Coverage: API returned ${apiResponse.commits.size} commits for branch='$defaultBranch'")
        val apiCommits = apiResponse.commits.toSet()

        // Check if merge-base itself has coverage
        if (mergeBase in apiCommits) {
            log.warn("Coverage: merge-base $mergeBase found in API commits")
            return mergeBase
        }

        // Walk backward from merge-base to find nearest commit with coverage
        val ancestors = getLocalAncestorCommits(gitRoot, mergeBase)
        log.warn("Coverage: checking ${ancestors.size} ancestor commits for coverage match")
        val match = ancestors.firstOrNull { it in apiCommits }
        if (match != null) {
            log.warn("Coverage: found matching commit=$match")
        } else {
            log.warn("Coverage: no matching commit found among ancestors")
        }
        return match
    }

    fun fetchCoverage(): CoverageResult? {
        val basePath = project.basePath
        if (basePath == null) {
            log.warn("Coverage: project basePath is null")
            return null
        }
        val gitRoot = File(basePath)
        log.warn("Coverage: gitRoot=$gitRoot, baseUrl=$baseUrl")

        val commitHash = findCoverageCommit(gitRoot)
        if (commitHash == null) {
            log.warn("Coverage: could not find a commit with coverage data")
            return null
        }
        log.warn("Coverage: using commit=$commitHash")

        // Get all PHP files and fetch coverage for each
        val phpFiles = FilenameIndex.getAllFilesByExt(project, "php", GlobalSearchScope.projectScope(project))
        log.warn("Coverage: found ${phpFiles.size} PHP files in project")
        val rootVf = LocalFileSystem.getInstance().findFileByIoFile(gitRoot)
        if (rootVf == null) {
            log.warn("Coverage: could not find VirtualFile for gitRoot=$gitRoot")
            return null
        }
        val result = mutableMapOf<String, Map<Int, List<String>>>()

        for (virtualFile in phpFiles) {
            val relativePath = VfsUtil.getRelativePath(virtualFile, rootVf) ?: continue
            val response = getFileCoverage(commitHash, relativePath)
            if (response == null) {
                log.warn("Coverage: no coverage for file=$relativePath")
                continue
            }
            result[relativePath] = response.resolveLines()
        }

        log.warn("Coverage: fetched coverage for ${result.size} / ${phpFiles.size} files")
        return CoverageResult(commitHash, gitRoot, result)
    }

    companion object {
        fun runGitCommand(gitRoot: File, vararg args: String): String? {
            val log = Logger.getInstance(CoverageApiClient::class.java)
            val cmd = "git ${args.joinToString(" ")}"
            return try {
                val process = ProcessBuilder("git", *args)
                    .directory(gitRoot)
                    .redirectErrorStream(false)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val stderr = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotEmpty()) {
                    log.warn("Git command '$cmd' succeeded: ${output.take(200)}")
                    output
                } else {
                    log.warn("Git command '$cmd' failed (exit=$exitCode): $stderr")
                    null
                }
            } catch (e: Exception) {
                log.warn("Git command '$cmd' threw exception: ${e.message}")
                null
            }
        }
    }
}

data class CoverageResult(
    val commitHash: String,
    val gitRoot: File,
    val coverageData: Map<String, Map<Int, List<String>>>
)