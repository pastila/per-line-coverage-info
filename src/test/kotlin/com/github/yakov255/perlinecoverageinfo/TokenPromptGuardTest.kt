package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The application-wide latch that keeps the first-run token dialog to one per IDE run.
 */
class TokenPromptGuardTest {

    @Test
    fun `only the first caller claims the prompt`() {
        val guard = TokenPromptGuard()

        assertTrue(guard.tryClaim())
        assertFalse("a second project must not open the dialog again", guard.tryClaim())
        assertFalse(guard.tryClaim())
    }

    @Test
    fun `reset allows exactly one new claim`() {
        val guard = TokenPromptGuard()
        assertTrue(guard.tryClaim())

        guard.resetForTest()

        assertTrue(guard.tryClaim())
        assertFalse(guard.tryClaim())
    }

    @Test
    fun `the singleton is the same instance for every caller`() {
        assertTrue(TokenPromptGuard.getInstance() === TokenPromptGuard.getInstance())
    }
}
