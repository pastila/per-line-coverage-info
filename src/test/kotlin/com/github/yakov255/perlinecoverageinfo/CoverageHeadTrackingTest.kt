package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure tests of the HEAD bookkeeping shared by the git listener and the pipeline poller:
 * the "did HEAD move" decision and the repository filter.
 */
class CoverageHeadTrackingTest {

    // --- HeadRevisionTracker ---------------------------------------------------------

    @Test
    fun `the first revision only seeds the tracker`() {
        val tracker = HeadRevisionTracker()
        assertNull(tracker.lastKnown)

        assertEquals(HeadChange.FIRST, tracker.observe("aaaa1111"))
        assertEquals("aaaa1111", tracker.lastKnown)
    }

    @Test
    fun `the same revision is unchanged and a different one is a change`() {
        val tracker = HeadRevisionTracker()
        tracker.observe("aaaa1111")

        assertEquals(HeadChange.UNCHANGED, tracker.observe("aaaa1111"))
        assertEquals(HeadChange.CHANGED, tracker.observe("bbbb2222"))
        assertEquals("bbbb2222", tracker.lastKnown)
        assertEquals(HeadChange.UNCHANGED, tracker.observe("bbbb2222"))
    }

    @Test
    fun `a revision the poller reported is not a change for the git listener`() {
        val tracker = HeadRevisionTracker()
        tracker.observe("aaaa1111")
        assertEquals(HeadChange.CHANGED, tracker.observe("bbbb2222"))

        // Same commit, reported by the other entry point a moment later.
        assertEquals(HeadChange.UNCHANGED, tracker.observe("bbbb2222"))
    }

    @Test
    fun `an unreadable HEAD keeps the previous revision`() {
        val tracker = HeadRevisionTracker()
        tracker.observe("aaaa1111")

        assertEquals(HeadChange.NO_HEAD, tracker.observe(null))
        assertEquals(HeadChange.NO_HEAD, tracker.observe(""))
        assertEquals("aaaa1111", tracker.lastKnown)
        assertEquals(HeadChange.UNCHANGED, tracker.observe("aaaa1111"))
    }

    // --- CoverageRepositoryFilter ----------------------------------------------------

    @Test
    fun `an unknown coverage root accepts every repository`() {
        assertTrue(CoverageRepositoryFilter.accepts(File("/repo"), null))
    }

    @Test
    fun `a null repository root never matches a known coverage root`() {
        assertFalse(CoverageRepositoryFilter.accepts(null, File("/repo")))
    }

    @Test
    fun `only the repository of the coverage root is accepted`() {
        assertTrue(CoverageRepositoryFilter.accepts(File("/repo"), File("/repo")))
        assertFalse("a submodule change is not a coverage change", CoverageRepositoryFilter.accepts(File("/repo/module"), File("/repo")))
        assertFalse("a sibling repository is not the coverage repository", CoverageRepositoryFilter.accepts(File("/other"), File("/repo")))
    }

    @Test
    fun `paths are compared by canonical form`() {
        val root = Files.createTempDirectory("head-filter-").toFile()
        try {
            assertTrue(CoverageRepositoryFilter.accepts(File(root, "."), root))
            assertTrue(CoverageRepositoryFilter.accepts(File(root, "./sub/.."), root))
            assertFalse(CoverageRepositoryFilter.accepts(File(root, "sub"), root))
        } finally {
            root.deleteRecursively()
        }
    }
}
