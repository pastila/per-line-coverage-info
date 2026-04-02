package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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

    private inline fun <reified T> makeRequest(endpoint: String): T {
        val url = "$baseUrl$endpoint"
        log.info("Coverage API request: GET $url")
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .header("Accept", "application/json")
        if (bearerToken.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer <redacted>")
        }
        val response = try {
            httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("Coverage API network error for $url", e)
            throw CoverageApiException(
                "Network error contacting coverage API",
                e,
                mapOf("url" to url, "error" to e.message),
                kind = CoverageErrorKind.NETWORK,
            )
        }
        log.info("Coverage API response: HTTP ${response.statusCode()} for $url")
        if (response.statusCode() != 200) {
            val body = response.body().take(500)
            log.warn("Coverage API returned HTTP ${response.statusCode()} for $url. Body: $body")
            throw CoverageApiException(
                "Coverage API returned HTTP ${response.statusCode()}",
                details = mapOf(
                    "url" to url,
                    "httpStatus" to response.statusCode().toString(),
                    "responseBody" to body
                ),
                kind = CoverageErrorKind.API_RESPONSE,
            )
        }
        return try {
            Json.decodeFromString<T>(response.body())
        } catch (e: Exception) {
            log.warn("Coverage API JSON parse error for $url", e)
            throw CoverageApiException(
                "Failed to parse coverage API response",
                e,
                mapOf("url" to url, "error" to e.message, "body" to response.body().take(500)),
                kind = CoverageErrorKind.PARSE,
            )
        }
    }

    fun getBranchCommits(branch: String): BranchCommitsResponse =
        makeRequest("/api/ide/branches/$branch/commits")

    fun getFileCoverage(commitHash: String, filePath: String): IdeFileCoverageResponse =
        makeRequest("/api/ide/commits/$commitHash/coverage/$filePath")

    private fun runGitCommand(gitRoot: File, vararg args: String): String? =
        runGitCommand(gitRoot, *args)

    private fun getDefaultBranch(gitRoot: File): String {
        val configured = CoverageApiSettings.getInstance().mergeBaseBranch.trim()
        if (configured.isNotEmpty()) return configured
        val symbolic = runGitCommand(gitRoot, "symbolic-ref", "refs/remotes/origin/HEAD")
        if (symbolic != null) {
            return symbolic.removePrefix("refs/remotes/origin/")
        }
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
     * Returns commits reachable from [to] but not from [from], most-recent first.
     * Equivalent to `git log --format=%H from..to`.
     */
    private fun getCommitsInRange(gitRoot: File, from: String, to: String): List<String> {
        val output = runGitCommand(gitRoot, "log", "--format=%H", "$from..$to") ?: return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    /**
     * Finds the best commit hash to use for coverage queries.
     *
     * Supports two topologies:
     *
     * 1. Coverage lives on the same branch queried (e.g. master):
     *    merge-base(HEAD, master) might itself be an API commit, or one of its
     *    ancestors is → old "walk ancestors" path.
     *
     * 2. Coverage lives on a dedicated branch (e.g. behat-run-necessary-tests)
     *    that merges master daily and stores coverage run commits:
     *    - merge-base(HEAD, coverageBranch) = some master commit M
     *    - API commits (C1, C2, …) are ON the coverage branch, not on master
     *    - Walk commits in M..coverageBranch (most-recent first) and pick the
     *      first one that has API coverage.  This is the most recent coverage
     *      run whose master base is ≥ where the current branch diverged.
     */
    private fun findCoverageCommit(gitRoot: File): String {
        val defaultBranch = getDefaultBranch(gitRoot)
        log.info("Coverage: default branch = $defaultBranch, gitRoot = $gitRoot")

        val mergeBase = getMergeBase(gitRoot, defaultBranch)
            ?: throw CoverageApiException(
                "Could not compute git merge-base",
                details = mapOf("gitRoot" to gitRoot.absolutePath, "defaultBranch" to defaultBranch),
                kind = CoverageErrorKind.GIT,
            )
        log.info("Coverage: merge-base = $mergeBase")

        val apiBranch = CoverageApiSettings.getInstance().effectiveApiBranch
        val apiResponse = getBranchCommits(apiBranch)
        val apiCommits = apiResponse.commits.toSet()
        log.info("Coverage: API returned ${apiCommits.size} commits for branch $apiBranch (git branch: $defaultBranch)")

        // Fast path: merge-base itself has coverage (topology 1 exact match).
        if (mergeBase in apiCommits) {
            log.info("Coverage: resolved commit = $mergeBase (exact merge-base)")
            return mergeBase
        }

        // Topology 2: API commits are on the coverage branch, not on master.
        // Walk commits reachable from the coverage branch but not from merge-base
        // (i.e. coverage-branch-specific commits added after the shared master state).
        val branchCommits = getCommitsInRange(gitRoot, mergeBase, defaultBranch)
        val matchOnBranch = branchCommits.firstOrNull { it in apiCommits }
        if (matchOnBranch != null) {
            log.info("Coverage: resolved commit = $matchOnBranch (most recent coverage run after merge-base on $defaultBranch)")
            return matchOnBranch
        }

        // Topology 1 fallback: walk ancestors of merge-base (coverage built on master commits).
        val ancestors = getLocalAncestorCommits(gitRoot, mergeBase)
        val matchAncestor = ancestors.firstOrNull { it in apiCommits }
            ?: throw CoverageApiException(
                "No coverage data found for this branch",
                details = mapOf(
                    "defaultBranch" to defaultBranch,
                    "mergeBase" to mergeBase,
                    "branchCommitsChecked" to branchCommits.size.toString(),
                    "localAncestorsChecked" to ancestors.size.toString(),
                    "apiCommitsAvailable" to apiCommits.size.toString()
                ),
                kind = CoverageErrorKind.NO_DATA,
            )
        log.info("Coverage: resolved commit = $matchAncestor (ancestor of merge-base)")
        return matchAncestor
    }

    private fun resolveCommitAndGitRoot(): Pair<String, File> {
        val basePath = project.basePath
            ?: throw CoverageApiException(
                "Could not determine project base path",
                details = mapOf("project" to project.name),
                kind = CoverageErrorKind.PROJECT_SETUP,
            )
        val projectDir = File(basePath)
        val gitRootPath = runGitCommand(projectDir, "rev-parse", "--show-toplevel")
            ?: throw CoverageApiException(
                "Could not find git repository root — is this project in a git repo?",
                details = mapOf("projectDir" to basePath),
                kind = CoverageErrorKind.GIT,
            )
        val gitRoot = File(gitRootPath)
        val commitHash = findCoverageCommit(gitRoot)
        return Pair(commitHash, gitRoot)
    }

    fun fetchCoverageForFile(relativePath: String): CoverageResult {
        val (commitHash, gitRoot) = resolveCommitAndGitRoot()
        log.info("Coverage: fetching coverage for file $relativePath at commit $commitHash")
        val response = getFileCoverage(commitHash, relativePath)
        return CoverageResult(commitHash, gitRoot, mapOf(relativePath to response.resolveLines()))
    }



    companion object {
        fun runGitCommand(gitRoot: File, vararg args: String): String? {
            return try {
                val process = ProcessBuilder("git", *args)
                    .directory(gitRoot)
                    .redirectErrorStream(false)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotEmpty()) {
                    output
                } else {
                    null
                }
            } catch (_: Exception) {
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