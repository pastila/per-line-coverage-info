package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.project.Project
import com.github.yakov255.perlinecoverageinfo.CoverageLog
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
    private val gitLabClient: GitLabApi,
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
            gitLabClient.listPipelines(projectId, currentBranch, status = "success", perPage = 1)
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
     * Pipeline matching (stays on the history side of merge-base):
     *   1. Exact merge-base match — best case
     *   2. Walk ancestors of merge-base — find the nearest ancestor that has a pipeline
     *
     * For each candidate pipeline, verifies it actually contains behat jobs with
     * downloadable artifacts. If the best-match pipeline has no coverage artifacts,
     * the next candidate is tried until one with artifacts is found or all are exhausted.
     *
     * If no pipeline from merge-base/ancestors has artifacts, falls back to the most
     * recent pipeline that has coverage artifacts on the coverage branch.
     */
    private fun findCoveragePipeline(gitRoot: File): Pair<String, Long> {
        val coverageBranch = getCoverageBranch()
        val projectId = CoverageApiSettings.getInstance().gitlabProjectId
        log.info("Coverage: coverage branch = $coverageBranch, gitRoot = $gitRoot")

        val pipelineCommits = gitLabClient.getPipelineCommits(projectId, coverageBranch)
        log.info("Coverage: GitLab returned ${pipelineCommits.size} pipeline commits for branch $coverageBranch")

        if (pipelineCommits.isEmpty()) {
            throw CoverageApiException(
                "No successful pipelines found on branch '$coverageBranch'.\n\n" +
                    "Check that the branch exists and has CI pipeline runs.",
                details = mapOf("coverageBranch" to coverageBranch),
                kind = CoverageErrorKind.NO_DATA,
            )
        }

        val pipelineCommitSet = pipelineCommits.associate { it.commitHash to it.pipelineId }

        // Build prioritized candidate list:
        //   1. merge-base exact match
        //   2. ancestors of merge-base (newest first)
        //   3. remaining pipelines from pipelineCommits (by updated_at desc)
        val candidates = mutableListOf<Pair<String, Long>>()
        val mergeBaseCandidates = mutableListOf<Pair<String, Long>>()

        val mergeBase = resolveThreeTierMergeBase(gitRoot, coverageBranch, projectId)

        if (mergeBase != null) {
            if (mergeBase in pipelineCommitSet) {
                val pid = pipelineCommitSet[mergeBase]!!
                mergeBaseCandidates.add(Pair(mergeBase, pid))
            }

            val ancestors = getLocalAncestorCommits(gitRoot, mergeBase)
            for (ancestor in ancestors) {
                if (ancestor in pipelineCommitSet && ancestor != mergeBase) {
                    val pid = pipelineCommitSet[ancestor]!!
                    mergeBaseCandidates.add(Pair(ancestor, pid))
                }
            }
        }

        candidates.addAll(mergeBaseCandidates)

        // Append remaining pipelines by recency (dedup by pipeline ID)
        for ((commitHash, pipelineId) in pipelineCommits) {
            if (candidates.none { it.second == pipelineId }) {
                candidates.add(Pair(commitHash, pipelineId))
            }
        }

        val mergeBaseCandidateCount = mergeBaseCandidates.size

        // Walk candidates, pick first that has coverage artifacts
        for ((index, candidate) in candidates.withIndex()) {
            val commitHash = candidate.first
            val pipelineId = candidate.second

            if (checkPipelineHasCoverage(projectId, pipelineId)) {
                if (index >= mergeBaseCandidateCount) {
                    fallbackReason = when {
                        mergeBase == null ->
                            "Could not determine merge-base with '$coverageBranch'"
                        mergeBaseCandidateCount == 0 ->
                            "No pipeline commit matched merge-base or its ancestors"
                        else ->
                            "Merge-base/ancestor pipelines had no coverage artifacts"
                    }
                    log.warn("Coverage: falling back to pipeline $pipelineId (commit ${commitHash.take(8)}). Reason: $fallbackReason")
                }
                log.info("Coverage: resolved commit = $commitHash (pipeline $pipelineId)")
                return Pair(commitHash, pipelineId)
            }
            log.info("Coverage: pipeline $pipelineId (commit ${commitHash.take(8)}) has no coverage artifacts, trying next")
        }

        throw CoverageApiException(
            "No pipelines with coverage artifacts found on branch '$coverageBranch'.\n\n" +
                "Checked ${candidates.size} pipeline(s). " +
                "None have successful jobs with 'behat' in the name and downloadable artifacts.",
            details = mapOf("coverageBranch" to coverageBranch, "checkedPipelines" to candidates.size.toString()),
            kind = CoverageErrorKind.NO_DATA,
        )
    }

    private fun checkPipelineHasCoverage(projectId: Long, pipelineId: Long): Boolean {
        return try {
            val jobs = gitLabClient.listPipelineJobs(projectId, pipelineId)
            jobs.any { job ->
                job.status == "success"
                    && (job.artifactsFile != null || job.artifacts.isNotEmpty())
                    && job.name.contains("behat", ignoreCase = true)
            }
        } catch (e: Exception) {
            log.warn("Coverage: failed to list jobs for pipeline $pipelineId: ${e.message}")
            false
        }
    }

    /** Set by findCoveragePipeline when falling back to a non-merge-base / non-ancestor pipeline. */
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
        if (configured.isEmpty()) {
            throw CoverageApiException(
                "Coverage branch is not configured.\nPlease set it in Settings → Tools → GitLab Coverage.",
                kind = CoverageErrorKind.PROJECT_SETUP,
            )
        }
        return configured
    }

    private fun getMergeBase(gitRoot: File, branch: String): String? {
        return runGitCommand(gitRoot, "merge-base", "HEAD", branch)
            ?: runGitCommand(gitRoot, "merge-base", "HEAD", "origin/$branch")
    }

    private fun getLocalAncestorCommits(gitRoot: File, startRef: String, limit: Int = 100): List<String> {
        val output = runGitCommand(gitRoot, "log", "--format=%H", "-$limit", startRef) ?: return emptyList()
        return output.lines().filter { it.isNotBlank() }
    }


    companion object {
        private val log = CoverageLog.get(CoverageResolver::class.java)

        fun runGitCommand(gitRoot: File, vararg args: String): String? {
            return try {
                val process = ProcessBuilder("git", *args)
                    .directory(gitRoot)
                    .redirectErrorStream(false)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                val errorOutput = process.errorStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotEmpty()) {
                    output
                } else {
                    log.warn("CoverageResolver: 'git ${args.joinToString(" ")}' failed (exit=$exitCode): $errorOutput")
                    null
                }
            } catch (e: Exception) {
                log.warn("CoverageResolver: git command failed: ${e.message}")
                null
            }
        }
    }
}
