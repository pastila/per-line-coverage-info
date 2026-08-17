package com.github.yakov255.perlinecoverageinfo

import java.util.concurrent.CompletableFuture

/**
 * Low-level GitLab REST API surface used by the coverage plugin.
 *
 * Two implementations exist:
 * - [GitLabApiClient]: thin HTTP client — one request per call, no caching.
 * - [CachedGitLabApi]: shared "worker" facade — serializes metadata calls on a
 *   single thread, caches results with a TTL and rate-limits actual requests.
 *
 * Everything that talks to GitLab should go through the shared facade obtained
 * from [GitLabCoordinator.api] so that concurrent callers (multiple project
 * windows, poller, resolver) are coalesced into a single worker.
 */
interface GitLabApi {

    fun searchProjects(query: String): List<GitLabProject>

    fun listMemberProjects(): List<GitLabProject>

    fun listPipelines(
        projectId: Long,
        ref: String,
        status: String? = "success",
        perPage: Int = 100,
    ): List<GitLabPipeline>

    fun listPipelineJobs(
        projectId: Long,
        pipelineId: Long,
        perPage: Int = 100,
    ): List<GitLabJob>

    fun downloadSingleArtifactFile(projectId: Long, jobId: Long, artifactPath: String): ByteArray

    fun downloadSingleArtifactFileAsync(
        projectId: Long,
        jobId: Long,
        artifactPath: String,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    ): CompletableFuture<ByteArray>

    fun getPipelineCommits(projectId: Long, ref: String): List<PipelineCommit>

    fun getMergeBase(projectId: Long, ref1: String, ref2: String): String?
}
