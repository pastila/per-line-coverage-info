package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCoverageLayerTest {

    // --- merge ---

    @Test
    fun `merge without local data returns CI coverage as is`() {
        val ci = mapOf(1 to listOf("ci1"), 2 to emptyList())
        assertSame(ci, LocalCoverageMerge.merge(ci, null, emptySet()))
    }

    @Test
    fun `merge without CI coverage returns local coverage`() {
        val local = mapOf(5 to listOf("loc"))
        assertSame(local, LocalCoverageMerge.merge(null, local, setOf("loc")))
    }

    @Test
    fun `merge returns null when neither side has the file`() {
        assertNull(LocalCoverageMerge.merge(null, null, setOf("loc")))
    }

    @Test
    fun `locally run test replaces its CI data`() {
        // t1 covered line 1 on CI; after a local change the re-run t1 reaches line 2 only.
        val ci = mapOf(1 to listOf("t1", "t2"), 2 to emptyList())
        val local = mapOf(1 to emptyList(), 2 to listOf("t1"))

        val merged = LocalCoverageMerge.merge(ci, local, setOf("t1"))

        assertEquals(mapOf(1 to listOf("t2"), 2 to listOf("t1")), merged)
    }

    @Test
    fun `locally run test is dropped from files it no longer reaches`() {
        // The local run has no data for this file at all — t1 must still lose its CI lines.
        val ci = mapOf(1 to listOf("t1"), 2 to listOf("t1", "t2"))

        val merged = LocalCoverageMerge.merge(ci, null, setOf("t1"))

        assertEquals(mapOf(1 to emptyList(), 2 to listOf("t2")), merged)
    }

    @Test
    fun `lines known only to the local run are added`() {
        // New code on the branch: CI has never seen line 10.
        val ci = mapOf(1 to listOf("ci"))
        val local = mapOf(10 to listOf("loc"), 11 to emptyList())

        val merged = LocalCoverageMerge.merge(ci, local, setOf("loc"))

        assertEquals(mapOf(1 to listOf("ci"), 10 to listOf("loc"), 11 to emptyList()), merged)
    }

    @Test
    fun `CI tests come first and are not duplicated`() {
        val ci = mapOf(1 to listOf("ci", "shared"))
        val local = mapOf(1 to listOf("loc", "ci"))

        // "ci" is in the local line list but was not run locally (e.g. stale data) — no duplicates.
        val merged = LocalCoverageMerge.merge(ci, local, setOf("loc"))

        assertEquals(mapOf(1 to listOf("ci", "shared", "loc")), merged)
    }

    // --- unionRuns ---

    @Test
    fun `unionRuns merges lines of several runs`() {
        val merged = LocalCoverageMerge.unionRuns(
            listOf(
                mapOf(1 to listOf("a"), 2 to emptyList()),
                mapOf(2 to listOf("b"), 3 to listOf("b")),
            ),
        )
        assertEquals(mapOf(1 to listOf("a"), 2 to listOf("b"), 3 to listOf("b")), merged)
    }

    @Test
    fun `unionRuns of nothing is null`() {
        assertNull(LocalCoverageMerge.unionRuns(emptyList()))
    }

    // --- layer ---

    @Test
    fun `newer run supersedes the same test in older runs`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1", "t2"), "src/A.php" to mapOf(1 to listOf("t1", "t2"), 2 to listOf("t1"))))
        layer.add(run(setOf("t1"), "src/B.php" to mapOf(7 to listOf("t1"))))

        val (older, newer) = layer.runs()
        assertEquals(setOf("t2"), older.tests)
        // Line 2 stays coverable but is no longer covered by the old t1 result.
        assertEquals(mapOf(1 to listOf("t2"), 2 to emptyList()), older.coverage["src/A.php"])
        assertEquals(setOf("t1"), newer.tests)
        assertEquals(setOf("t1", "t2"), layer.tests())
    }

    @Test
    fun `run whose tests are all re-run is dropped`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(1 to listOf("t1"))))
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(2 to listOf("t1"))))

        assertEquals(1, layer.runs().size)
        assertEquals(mapOf(2 to listOf("t1")), layer.runs().single().coverage["src/A.php"])
    }

    @Test
    fun `unrelated runs accumulate`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(1 to listOf("t1"))))
        layer.add(run(setOf("t2"), "src/B.php" to mapOf(1 to listOf("t2"))))

        assertEquals(2, layer.runs().size)
        assertEquals(2, layer.fileCount())
    }

    @Test
    fun `clear empties the layer`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(1 to listOf("t1"))))
        layer.clear()

        assertTrue(layer.isEmpty())
        assertEquals(emptySet<String>(), layer.tests())
    }

    // --- paths ---

    @Test
    fun `existing path is kept`() {
        assertEquals("core/src/A.php", LocalCoveragePaths.normalize("core/src/A.php") { it == "core/src/A.php" })
    }

    @Test
    fun `path outside the behat base path loses the web prefix`() {
        // /web/app/raketa/api/... relativised against /web/core with root "core".
        val existing = setOf("app/raketa/api/src/Access/AccessFacade.php")
        assertEquals(
            "app/raketa/api/src/Access/AccessFacade.php",
            LocalCoveragePaths.normalize("core/web/app/raketa/api/src/Access/AccessFacade.php") { it in existing },
        )
    }

    @Test
    fun `leading slash is ignored`() {
        assertEquals("api/hotels/src/A.php", LocalCoveragePaths.normalize("/api/hotels/src/A.php") { it == "api/hotels/src/A.php" })
    }

    @Test
    fun `missing path is null`() {
        assertNull(LocalCoveragePaths.normalize("core/src/Gone.php") { false })
    }

    private fun run(tests: Set<String>, vararg files: Pair<String, Map<Int, List<String>>>) =
        LocalCoverageRun(tests, mapOf(*files), emptyMap())
}
