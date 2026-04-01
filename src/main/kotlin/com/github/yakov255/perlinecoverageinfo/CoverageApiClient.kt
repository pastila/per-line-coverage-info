package com.github.yakov255.perlinecoverageinfo

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

    private val baseUrl = apiEndpoint.trimEnd('/')

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private inline fun <reified T> makeRequest(endpoint: String): T? {
        val url = "$baseUrl$endpoint"
        return try {
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
                Json.decodeFromString<T>(response.body())
            } else {
                null
            }
        } catch (e: Exception) {
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

        val mergeBase = getMergeBase(gitRoot, defaultBranch)
        if (mergeBase == null) {
            return null
        }

        val apiResponse = getBranchCommits(defaultBranch)
        if (apiResponse == null) {
            return null
        }
        val apiCommits = apiResponse.commits.toSet()

        // Check if merge-base itself has coverage
        if (mergeBase in apiCommits) {
            return mergeBase
        }

        // Walk backward from merge-base to find nearest commit with coverage
        val ancestors = getLocalAncestorCommits(gitRoot, mergeBase)
        val match = ancestors.firstOrNull { it in apiCommits }
        if (match != null) {
        } else {
        }
        return match
    }

    private fun resolveCommitAndGitRoot(): Pair<String, File>? {
        val basePath = project.basePath
        if (basePath == null) {
            return null
        }
        val projectDir = File(basePath)
        val gitRootPath = runGitCommand(projectDir, "rev-parse", "--show-toplevel")
        if (gitRootPath == null) {
            return null
        }
        val gitRoot = File(gitRootPath)

        val commitHash = findCoverageCommit(gitRoot)
        if (commitHash == null) {
            return null
        }
        return Pair(commitHash, gitRoot)
    }

    fun fetchCoverageForFile(relativePath: String): CoverageResult? {
        val (commitHash, gitRoot) = resolveCommitAndGitRoot() ?: return null

        val response = getFileCoverage(commitHash, relativePath)
        if (response == null) {
            return null
        }
        val result = mapOf(relativePath to response.resolveLines())
        return CoverageResult(commitHash, gitRoot, result)
    }

    fun fetchCoverage(): CoverageResult? {
        val (commitHash, gitRoot) = resolveCommitAndGitRoot() ?: return null
        val basePath = project.basePath ?: return null
        val projectDir = File(basePath)

        // Get all PHP files and fetch coverage for each
        val phpFiles = FilenameIndex.getAllFilesByExt(project, "php", GlobalSearchScope.projectScope(project))
        val projectVf = LocalFileSystem.getInstance().findFileByIoFile(projectDir)
        if (projectVf == null) {
            return null
        }
        val result = mutableMapOf<String, Map<Int, List<String>>>()

        for (virtualFile in phpFiles) {
            // Use path relative to project root for the API (matches how coverage data is stored)
            val relativePath = VfsUtil.getRelativePath(virtualFile, projectVf) ?: continue
            val response = getFileCoverage(commitHash, relativePath)
            if (response == null) {
                continue
            }
            result[relativePath] = response.resolveLines()
        }

        return CoverageResult(commitHash, gitRoot, result)
    }

    companion object {
        fun runGitCommand(gitRoot: File, vararg args: String): String? {
            return try {
                val process = ProcessBuilder("git", *args)
                    .directory(gitRoot)
                    .redirectErrorStream(false)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val stderr = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotEmpty()) {
                    output
                } else {
                    null
                }
            } catch (e: Exception) {
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