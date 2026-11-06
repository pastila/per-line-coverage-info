package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCoverageLayerTest {

    // --- merge ---

    @Test
    fun `legacy local test names replace repo relative CI coverage including header only tests`() {
        val local = CoveragePathNormalizer.normalizeRaketaRun(
            BinaryCoverageParser.CovtData(
                listOf("app/raketa/E2E/order.feature:10", "app/raketa/FeatureTest/client.feature:20"),
                mapOf("app/raketa/web/src/A.php" to mapOf(2 to listOf("app/raketa/E2E/order.feature:10"))),
            ),
            null,
        )
        val order = "app/raketa/features/E2E/order.feature:10"
        val client = "app/raketa/features/FeatureTest/client.feature:20"
        val ci = mapOf(1 to listOf(order, client, "other"), 2 to emptyList())

        assertEquals(setOf(order, client), local.tests.toSet())
        assertEquals(
            mapOf(1 to listOf("other"), 2 to listOf(order)),
            LocalCoverageMerge.merge(ci, local.coverage["src/A.php"], local.tests.toSet()),
        )
        assertEquals(
            mapOf(3 to listOf("other")),
            LocalCoverageMerge.merge(mapOf(3 to listOf(client, "other")), null, local.tests.toSet()),
        )
    }

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
    fun `partial supersession decodes only the requested file and does not stack filters`() {
        val reads = mutableListOf<String>()
        val data = mapOf(
            "a.php" to mapOf(1 to listOf("t1", "t2", "t3"), 2 to emptyList()),
            "b.php" to mapOf(5 to listOf("t3")),
        )
        val original = LocalCoverageRun(setOf("t1", "t2", "t3"), LazyFileMap(data.keys) { path ->
            reads.add(path)
            data[path]
        }, emptyMap(), "old-head")
        val remaining = original.without(setOf("t1"))!!.without(setOf("t2"))!!.restampedTo("new-head")
        assertTrue(reads.isEmpty())
        assertEquals(setOf("a.php", "b.php"), remaining.coverage.keys)
        assertTrue(reads.isEmpty())
        assertEquals(mapOf(1 to listOf("t3"), 2 to emptyList()), remaining.coverage["a.php"])
        assertEquals(listOf("a.php"), reads)
        assertEquals(setOf("t3"), remaining.tests)
        assertEquals("new-head", remaining.commit)
    }

    @Test
    fun `filtered lines continue sharing identical test sets`() {
        val shared = listOf("t1", "t2")
        val run = run(setOf("t1", "t2"), "a.php" to (1..1000).associateWith { shared })
        val lines = run.without(setOf("t1"))!!.coverage.getValue("a.php")
        assertEquals(listOf("t2"), lines[1])
        assertSame(lines[1], lines[1000])
    }

    @Test
    fun `runs without executed tests do not accumulate`() {
        val layer = LocalCoverageLayer()
        repeat(1000) { layer.add(run(emptySet())) }
        assertTrue(layer.isEmpty())
    }

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

    // --- staleness ---

    @Test
    fun `runs of another commit are stale and excluded from the merge`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(1 to listOf("t1")), commit = "aaa"))
        layer.add(run(setOf("t2"), "src/B.php" to mapOf(1 to listOf("t2")), commit = "bbb"))

        assertEquals(1, layer.activeRuns("bbb").size)
        assertEquals(1, layer.staleRuns("bbb").size)
        assertEquals(setOf("t2"), layer.activeTests("bbb"))
        assertEquals(1, layer.activeFileCount("bbb"))
        // All runs are still there — stale ones can be restored.
        assertEquals(2, layer.runs().size)
        assertEquals(setOf("t1", "t2"), layer.tests())
    }

    @Test
    fun `a run without a known commit is never stale`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(1 to listOf("t1"))))

        assertEquals(setOf("t1"), layer.activeTests("bbb"))
        assertTrue(layer.staleRuns("bbb").isEmpty())
    }

    @Test
    fun `nothing is stale while HEAD is unknown`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(1 to listOf("t1")), commit = "aaa"))

        assertEquals(setOf("t1"), layer.activeTests(null))
        assertTrue(layer.staleRuns(null).isEmpty())
    }

    @Test
    fun `restamp brings stale runs back onto the current HEAD`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(1 to listOf("t1")), commit = "aaa"))
        layer.add(run(setOf("t2"), "src/B.php" to mapOf(1 to listOf("t2")), commit = "bbb"))

        layer.restampTo("bbb")

        assertEquals(2, layer.activeRuns("bbb").size)
        assertTrue(layer.staleRuns("bbb").isEmpty())
        assertEquals(setOf("t1", "t2"), layer.activeTests("bbb"))
    }

    @Test
    fun `superseding keeps the commit of the surviving run`() {
        val layer = LocalCoverageLayer()
        layer.add(run(setOf("t1", "t2"), "src/A.php" to mapOf(1 to listOf("t1", "t2")), commit = "aaa"))
        layer.add(run(setOf("t1"), "src/A.php" to mapOf(2 to listOf("t1")), commit = "aaa"))

        assertEquals(2, layer.activeRuns("aaa").size)
        assertTrue(layer.staleRuns("aaa").isEmpty())
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
        assertEquals(
            "cli/scripts/check.php",
            LocalCoveragePaths.normalize("app/raketa/web/cli/scripts/check.php") {
                it == "cli/scripts/check.php"
            },
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

    private fun run(
        tests: Set<String>,
        vararg files: Pair<String, Map<Int, List<String>>>,
        commit: String? = null,
    ) = LocalCoverageRun(tests, mapOf(*files), emptyMap(), commit)
}
