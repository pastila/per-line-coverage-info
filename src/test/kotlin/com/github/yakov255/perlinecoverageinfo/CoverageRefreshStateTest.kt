package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageRefreshStateTest {
    private val context = CoverageRefreshContext("/repo", "head", "feature", "head", "master", "settings")

    @Test
    fun `fresh coverage stops polling even after the cooldown expires`() {
        var now = 0L
        val state = CoverageRefreshState { now }
        state.onReady(context, stale = false)
        assertTrue(state.isWithinCooldown(context))
        assertFalse(state.shouldPoll(context, "head"))
        now = 60_000_000_000L
        assertFalse(state.isWithinCooldown(context))
        assertFalse(state.shouldPoll(context, "head"))
    }

    @Test
    fun `head push fetch branch and settings changes bypass the settled context`() {
        val state = CoverageRefreshState()
        state.onReady(context, stale = false)
        for (changed in listOf(
            context.copy(head = "new"), context.copy(remoteHead = "pushed"),
            context.copy(baselineHead = "new-master"), context.copy(branch = "other"),
            context.copy(settings = "new-token"), context.copy(gitRoot = "/other"),
        )) {
            assertFalse(state.isWithinCooldown(changed))
            assertTrue(state.shouldPoll(changed, "head"))
        }
    }

    @Test
    fun `stale fallback keeps waiting for coverage of a pushed commit`() {
        val state = CoverageRefreshState()
        state.onReady(context, stale = true)
        assertFalse(state.isWithinCooldown(context))
        assertTrue(state.shouldPoll(context, "older"))
        state.onReady(context, stale = false)
        assertFalse(state.shouldPoll(context, "head"))
    }

    @Test
    fun `unpublished and detached branches do not cause idle network requests`() {
        val state = CoverageRefreshState()
        for (local in listOf(context.copy(remoteHead = null), context.copy(branch = null, remoteHead = null))) {
            assertFalse(state.shouldPoll(local, "older"))
            state.onReady(local, stale = true)
            assertFalse(state.shouldPoll(local, "older"))
        }
    }

    @Test
    fun `stale fallback for an unpushed head stops once the remote commit is covered`() {
        val local = context.copy(head = "unpushed", remoteHead = "pushed")
        val state = CoverageRefreshState()
        state.onReady(local, stale = true)
        assertFalse(state.shouldPoll(local, "pushed"))
        assertTrue(state.shouldPoll(local.copy(remoteHead = "unpushed"), "pushed"))
    }

    @Test
    fun `a pending baseline continues refreshing even when primary is fresh`() {
        val state = CoverageRefreshState()
        state.onReady(context, stale = false, baselinePending = true)
        assertFalse(state.isWithinCooldown(context))
        assertTrue(state.shouldPoll(context, "head"))
        state.onReady(context, stale = false)
        assertFalse(state.shouldPoll(context, "head"))
    }

    @Test
    fun `settings apply and clear invalidate the cooldown and settled context`() {
        val state = CoverageRefreshState()
        state.onReady(context, stale = false)
        state.clear()
        assertFalse(state.isWithinCooldown(context))
        assertTrue(state.shouldPoll(context, "head"))
    }

    @Test
    fun `a missing pipeline for an unpublished head waits for push instead of a timer`() {
        val state = CoverageRefreshState()
        val unpublished = context.copy(remoteHead = "older")
        assertFalse(state.onNotReady(unpublished, "no_pipeline_yet"))
        assertFalse(state.shouldPoll(unpublished, "older"))
        assertTrue(state.shouldPoll(context, "older"))
        assertTrue(state.onNotReady(context, "no_pipeline_yet"))
        // Known running jobs still need a retry even without a local remote-tracking ref.
        assertTrue(state.onNotReady(context.copy(remoteHead = null), "jobs_running"))
    }
}
