package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import java.io.File

data class ResolvedPipeline(
    val commitHash: String,
    val pipelineId: Long,
    val gitRoot: File,
    val fallback: Boolean = false,
    val fallbackReason: String? = null,
)

/**
 * Pair of pipelines used by dual-coverage mode:
 * - [primary] = the pipeline the user is actively working on (current branch if available, otherwise coverage branch).
 * - [baseline] = the coverage-branch (master) pipeline used to highlight feature-only tests.
 *
 * [baseline] is null when:
 *   - [primary] already came from the coverage branch (single-coverage mode), OR
 *   - resolving the coverage branch failed (network/no pipelines).
 */
data class DualResolved(
    val primary: ResolvedPipeline,
    val baseline: ResolvedPipeline?,
)

class CoverageResolver(
    private val gitLabClient: GitLabApiClient,
    private val project: Project,
) {

    private val log = CoverageLog.get(CoverageResolver::class.java)

    fun resolve(): ResolvedPipeline {
        val gitRoot = resolveGitRoot()

        // Prefer the latest successful pipeline on the current branch, if any.
        tryResolveFromCurrentBranch(gitRoot)?.let { return it }

        return resolveCoverageBranchPipeline(gitRoot)
    }

    /**
     * Resolves a pair of pipelines for dual-coverage mode:
     * - primary = current-branch pipeline (or coverage-branch if no current-branch pipeline).
     * - baseline = coverage-branch pipeline. Null when equal to primary or when resolution fails.
     */
    fun resolveDual(): DualResolved {
        val gitRoot = resolveGitRoot()

        val current = tryResolveFromCurrentBranch(gitRoot)
        val coverage: ResolvedPipeline? = try {
            // Use a fresh resolver-state for baseline so its fallbackReason doesn't leak into primary.
            resolveCoverageBranchPipeline(gitRoot)
        } catch (e: CoverageApiException) {
            log.warn("Coverage: baseline (coverage-branch) resolution failed: ${e.userMessage}")
            null
        } catch (e: Exception) {
            log.warn("Coverage: baseline (coverage-branch) resolution failed: ${e.message}")
            null
        }

        val primary = current ?: coverage
            ?: throw CoverageApiException(
                "Could not resolve any coverage pipeline.",
                kind = CoverageErrorKind.NO_DATA,
            )

        val baseline = when {
            coverage == null -> null
            primary.commitHash == coverage.commitHash -> null
            else -> coverage
        }

        return DualResolved(primary = primary, baseline = baseline)
    }

    private fun resolveGitRoot(): File {
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
        return File(gitRootPath)
    }

    private fun resolveCoverageBranchPipeline(gitRoot: File): ResolvedPipeline {
        // findCoveragePipeline mutates the shared fallbackReason field; reset it before each call
        // so a previous resolution doesn't taint the new one.
        fallbackReason = null
        val (commitHash, pipelineId) = findCoveragePipeline(gitRoot)
        return ResolvedPipeline(
            commitHash = commitHash,
            pipelineId = pipelineId,
            gitRoot = gitRoot,
            fallback = fallbackReason != null,
            fallbackReason = fallbackReason,
        )
    }

    /**
     * If the currently checked-out local branch has at least one successful pipeline on GitLab,
     * return that pipeline directly (latest by updated_at). Otherwise return null and let the
     * caller fall back to coverage-branch resolution.
     */
    private fun tryResolveFromCurrentBranch(gitRoot: File): ResolvedPipeline? {
        val currentBranch = runGitCommand(gitRoot, "rev-parse", "--abbrev-ref", "HEAD")
        if (currentBranch.isNullOrBlank() || currentBranch == "HEAD") {
            log.info("Coverage: current branch unknown or detached HEAD, skipping current-branch resolve")
            return null
        }
        val coverageBranch = getCoverageBranch()
        if (currentBranch == coverageBranch) {
            log.info("Coverage: current branch == coverage branch ($coverageBranch), using coverage-branch resolution")
            return null
        }
        val projectId = CoverageApiSettings.getInstance().gitlabProjectId
        val pipelines = try {
            gitLabClient.listPipelines(projectId, currentBranch, status = "success")
        } catch (e: Exception) {
            log.warn("Coverage: failed to list pipelines for current branch '$currentBranch', falling back to coverage branch: ${e.message}")
            return null
        }
        if (pipelines.isEmpty()) {
            log.info("Coverage: no successful pipelines on current branch '$currentBranch', falling back to coverage branch '$coverageBranch'")
            return null
        }
        val latest = pipelines.first()
        log.info("Coverage: using latest pipeline ${latest.id} (commit ${latest.sha}) on current branch '$currentBranch'")
        return ResolvedPipeline(
            commitHash = latest.sha,
            pipelineId = latest.id,
            gitRoot = gitRoot,
            fallback = false,
            fallbackReason = null,
        )
    }

    /**
     * Finds the best pipeline to use for coverage queries.
     *
     * Merge-base resolution (three-tier):
     *   1. Local `git merge-base HEAD origin/<coverageBranch>` — fast, no network
     *   2. GitLab API merge_base — works with shallow clones and unfetched branches
     *   3. Fallback to latest pipeline — if merge-base can't be determined at all
     *
     * Pipeline matching supports two topologies:
     *   1. Coverage on same branch: merge-base or its ancestor has a pipeline
     *   2. Coverage on dedicated branch: walk commits in mergeBase..coverageBranch
     *
     * If no pipeline commit matches, falls back to the latest pipeline.
     */
    private fun findCoveragePipeline(gitRoot: File): Pair<String, Long> {
        val coverageBranch = getCoverageBranch()
        val projectId = CoverageApiSettings.getInstance().gitlabProjectId
        log.info("Coverage: coverage branch = $coverageBranch, gitRoot = $gitRoot")

        val pipelineCommits = gitLabClient.getPipelineCommits(projectId, coverageBranch)
        val pipelineCommitSet = pipelineCommits.associate { it.commitHash to it.pipelineId }
        log.info("Coverage: GitLab returned ${pipelineCommitSet.size} pipeline commits for branch $coverageBranch")

        if (pipelineCommitSet.isEmpty()) {
            throw CoverageApiException(
                "No successful pipelines found on branch '$coverageBranch'.\n\n" +
                    "Check that the branch exists and has CI pipeline runs.",
                details = mapOf("coverageBranch" to coverageBranch),
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        // --- Three-tier merge-base resolution ---
        val mergeBase = resolveThreeTierMergeBase(gitRoot, coverageBranch, projectId)

        if (mergeBase != null) {
            // Fast path: merge-base itself has a pipeline
            if (mergeBase in pipelineCommitSet) {
                log.info("Coverage: resolved commit = $mergeBase (exact merge-base, pipeline ${pipelineCommitSet[mergeBase]})")
                return Pair(mergeBase, pipelineCommitSet[mergeBase]!!)
            }

            // Topology 2: pipeline commits are on the coverage branch, not on master.
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
        }

        // --- Fallback: use latest pipeline ---
        val latest = pipelineCommits.first()
        val reason = if (mergeBase == null) {
            "Could not determine merge-base with '$coverageBranch'"
        } else {
            "No pipeline commit matched merge-base or its neighbors"
        }
        log.warn("Coverage: falling back to latest pipeline ${latest.pipelineId} (commit ${latest.commitHash}). Reason: $reason")
        fallbackReason = reason
        return Pair(latest.commitHash, latest.pipelineId)
    }

    /** Set by findCoveragePipeline when falling back to latest pipeline. */
    var fallbackReason: String? = null
        private set

    /**
     * Tries to compute merge-base in three tiers:
     * 1. Local git merge-base (fast, works when coverage branch is fetched)
     * 2. GitLab API merge_base with HEAD sha (works with shallow/unfetched)
     * 3. GitLab API merge_base with upstream tracking ref
     * Returns null if all tiers fail.
     */
    private fun resolveThreeTierMergeBase(gitRoot: File, coverageBranch: String, projectId: Long): String? {
        // Tier 1: local git
        val localResult = getMergeBase(gitRoot, coverageBranch)
        if (localResult != null) {
            log.info("Coverage: merge-base via local git = $localResult")
            return localResult
        }
        log.info("Coverage: local merge-base failed, trying GitLab API")

        // Tier 2: GitLab API with HEAD sha
        val headSha = runGitCommand(gitRoot, "rev-parse", "HEAD")
        if (headSha != null) {
            val apiResult = gitLabClient.getMergeBase(projectId, headSha, coverageBranch)
            if (apiResult != null) {
                log.info("Coverage: merge-base via GitLab API (HEAD) = $apiResult")
                return apiResult
            }
            log.info("Coverage: GitLab API merge-base with HEAD sha failed (unpushed?)")
        }

        // Tier 3: GitLab API with upstream tracking ref
        val upstream = runGitCommand(gitRoot, "rev-parse", "@{upstream}")
        if (upstream != null && upstream != headSha) {
            val apiResult = gitLabClient.getMergeBase(projectId, upstream, coverageBranch)
            if (apiResult != null) {
                log.info("Coverage: merge-base via GitLab API (upstream) = $apiResult")
                return apiResult
            }
        }

        log.warn("Coverage: all merge-base attempts failed")
        return null
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
