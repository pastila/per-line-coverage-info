package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

data class ResolvedPipeline(
    val commitHash: String,
    val pipelineId: Long,
    val gitRoot: File,
)

class CoverageResolver(
    private val gitLabClient: GitLabApiClient,
    private val project: Project,
) {

    private val log = Logger.getInstance(CoverageResolver::class.java)

    fun resolve(): ResolvedPipeline {
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
        val (commitHash, pipelineId) = findCoveragePipeline(gitRoot)
        return ResolvedPipeline(commitHash, pipelineId, gitRoot)
    }

    /**
     * Finds the best pipeline to use for coverage queries.
     *
     * Supports two topologies:
     *
     * 1. Coverage lives on the same branch queried (e.g. master):
     *    merge-base(HEAD, master) might itself be a pipeline commit, or one of its
     *    ancestors is → walk ancestors of merge-base.
     *
     * 2. Coverage lives on a dedicated branch (e.g. behat-run-necessary-tests)
     *    that merges master daily and stores coverage run commits:
     *    - merge-base(HEAD, coverageBranch) = some master commit M
     *    - Pipeline commits (C1, C2, …) are ON the coverage branch, not on master
     *    - Walk commits in M..coverageBranch (most-recent first) and pick the
     *      first one that has a successful pipeline.
     */
    private fun findCoveragePipeline(gitRoot: File): Pair<String, Long> {
        val coverageBranch = getCoverageBranch()
        log.info("Coverage: coverage branch = $coverageBranch, gitRoot = $gitRoot")

        val mergeBase = getMergeBase(gitRoot, coverageBranch)
            ?: throw CoverageApiException(
                "Could not compute git merge-base",
                details = mapOf("gitRoot" to gitRoot.absolutePath, "coverageBranch" to coverageBranch),
                kind = CoverageErrorKind.GIT,
            )
        log.info("Coverage: merge-base = $mergeBase")

        val projectId = CoverageApiSettings.getInstance().gitlabProjectId
        val pipelineCommits = gitLabClient.getPipelineCommits(projectId, coverageBranch)
        val pipelineCommitSet = pipelineCommits.associate { it.commitHash to it.pipelineId }
        log.info("Coverage: GitLab returned ${pipelineCommitSet.size} pipeline commits for branch $coverageBranch")

        // Fast path: merge-base itself has a pipeline
        if (mergeBase in pipelineCommitSet) {
            log.info("Coverage: resolved commit = $mergeBase (exact merge-base, pipeline ${pipelineCommitSet[mergeBase]})")
            return Pair(mergeBase, pipelineCommitSet[mergeBase]!!)
        }

        // Topology 2: pipeline commits are on the coverage branch, not on master.
        // Walk commits reachable from the coverage branch but not from merge-base.
        val branchCommits = getCommitsInRange(gitRoot, mergeBase, "origin/$coverageBranch")
        val matchOnBranch = branchCommits.firstOrNull { it in pipelineCommitSet }
        if (matchOnBranch != null) {
            log.info("Coverage: resolved commit = $matchOnBranch (coverage branch after merge-base, pipeline ${pipelineCommitSet[matchOnBranch]})")
            return Pair(matchOnBranch, pipelineCommitSet[matchOnBranch]!!)
        }

        // Topology 1 fallback: walk ancestors of merge-base
        val ancestors = getLocalAncestorCommits(gitRoot, mergeBase)
        val matchAncestor = ancestors.firstOrNull { it in pipelineCommitSet }
        if (matchAncestor != null) {
            log.info("Coverage: resolved commit = $matchAncestor (ancestor of merge-base, pipeline ${pipelineCommitSet[matchAncestor]})")
            return Pair(matchAncestor, pipelineCommitSet[matchAncestor]!!)
        }

        throw CoverageApiException(
            "No coverage pipeline found for this branch",
            details = mapOf(
                "coverageBranch" to coverageBranch,
                "mergeBase" to mergeBase,
                "branchCommitsChecked" to branchCommits.size.toString(),
                "localAncestorsChecked" to ancestors.size.toString(),
                "pipelinesAvailable" to pipelineCommitSet.size.toString(),
            ),
            kind = CoverageErrorKind.NO_DATA,
        )
    }

    private fun getCoverageBranch(): String {
        val configured = CoverageApiSettings.getInstance().coverageBranch.trim()
        if (configured.isNotEmpty()) return configured

        val basePath = project.basePath ?: return "main"
        val projectDir = File(basePath)
        val symbolic = runGitCommand(projectDir, "symbolic-ref", "refs/remotes/origin/HEAD")
        if (symbolic != null) {
            return symbolic.removePrefix("refs/remotes/origin/")
        }
        val branches = runGitCommand(projectDir, "branch", "--list", "main", "master") ?: return "main"
        return if (branches.lines().any { it.trim().trimStart('*').trim() == "main" }) "main" else "master"
    }

    private fun getMergeBase(gitRoot: File, branch: String): String? {
        return runGitCommand(gitRoot, "merge-base", "HEAD", branch)
            ?: runGitCommand(gitRoot, "merge-base", "HEAD", "origin/$branch")
    }

    private fun getLocalAncestorCommits(gitRoot: File, startRef: String, limit: Int = 100): List<String> {
        val output = runGitCommand(gitRoot, "log", "--format=%H", "-$limit", startRef) ?: return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }

    /**
     * Returns commits reachable from [to] but not from [from], most-recent first.
     */
    private fun getCommitsInRange(gitRoot: File, from: String, to: String): List<String> {
        val output = runGitCommand(gitRoot, "log", "--format=%H", "$from..$to") ?: return emptyList()
        return output.lines().filter { it.isNotBlank() }
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
                if (exitCode == 0 && output.isNotEmpty()) output else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
