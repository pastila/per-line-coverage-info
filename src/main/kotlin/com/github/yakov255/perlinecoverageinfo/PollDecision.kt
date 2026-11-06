package com.github.yakov255.perlinecoverageinfo

/**
 * Pure decision helpers for the pipeline poller, so the "reload or stay quiet" rules are
 * testable without an IDE project.
 */
object PollDecision {

    /**
     * Whether a poll should skip the extra API calls because the remote branch head is
     * exactly the commit the loaded coverage describes.
     */
    fun remoteMatchesLoaded(remoteSha: String?, loadedSha: String?): Boolean =
        remoteSha != null && remoteSha == loadedSha

    /**
     * Whether the latest pipeline's coverage should be loaded again.
     *
     * A pipeline that was already processed stays quiet, but a new pipeline on the same
     * sha (a GitLab re-run) must reload.
     */
    fun shouldReload(
        latestPipelineId: Long,
        latestPipelineSha: String,
        loadedSha: String?,
        handledPipelineId: Long?,
        handledPipelineSha: String?,
    ): Boolean {
        if (latestPipelineSha == loadedSha) return false
        val alreadyHandled = latestPipelineSha == handledPipelineSha && latestPipelineId == handledPipelineId
        return !alreadyHandled
    }
}
