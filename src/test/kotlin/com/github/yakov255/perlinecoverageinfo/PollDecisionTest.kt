package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollDecisionTest {

    @Test
    fun `remote equal to the loaded commit is quiet`() {
        assertTrue(PollDecision.remoteMatchesLoaded("abc", "abc"))
        assertFalse(PollDecision.remoteMatchesLoaded("abc", null))
        assertFalse(PollDecision.remoteMatchesLoaded(null, "abc"))
        assertFalse(PollDecision.remoteMatchesLoaded("abc", "def"))
    }

    @Test
    fun `already handled pipeline is not reloaded`() {
        assertFalse(
            PollDecision.shouldReload(
                latestPipelineId = 10,
                latestPipelineSha = "abc",
                loadedSha = "baseline",
                handledPipelineId = 10,
                handledPipelineSha = "abc",
            )
        )
    }

    @Test
    fun `a new pipeline with the same sha is reloaded`() {
        assertTrue(
            PollDecision.shouldReload(
                latestPipelineId = 11,
                latestPipelineSha = "abc",
                loadedSha = "baseline",
                handledPipelineId = 10,
                handledPipelineSha = "abc",
            )
        )
    }

    @Test
    fun `a pipeline whose sha is already loaded is not reloaded`() {
        assertFalse(
            PollDecision.shouldReload(
                latestPipelineId = 12,
                latestPipelineSha = "abc",
                loadedSha = "abc",
                handledPipelineId = null,
                handledPipelineSha = null,
            )
        )
    }

    @Test
    fun `a different sha is reloaded even without handled state`() {
        assertTrue(
            PollDecision.shouldReload(
                latestPipelineId = 12,
                latestPipelineSha = "abc",
                loadedSha = "baseline",
                handledPipelineId = null,
                handledPipelineSha = null,
            )
        )
    }
}
