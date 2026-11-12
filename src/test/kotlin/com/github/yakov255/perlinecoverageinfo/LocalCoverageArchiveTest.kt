package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.*
import org.junit.Test

class LocalCoverageArchiveTest {
    @Test
    fun `feature snapshots survive edits without adding feature files to line coverage`() {
        val original = "Feature: test\n  Scenario: first\n    Given first\n"
        val sources = mutableMapOf("a.php" to "<?php", "tests/Test.feature" to original)
        LocalCoverageArchive.store(setOf("tests/Test.feature:2"), mapOf("a.php" to mapOf(1 to listOf("tests/Test.feature:2"))),
            "head", additionalSources = setOf("tests/Test.feature")) { sources.getValue(it) }.use { run ->
            sources["tests/Test.feature"] = "changed"
            assertEquals(original, run.snapshots["tests/Test.feature"])
            assertEquals(setOf("a.php"), run.coverage.keys)
        }
    }

    @Test
    fun `clear closes archives immediately after outstanding readers finish`() {
        val layer = LocalCoverageLayer()
        val run = LocalCoverageArchive.store(setOf("t"), mapOf("a.php" to mapOf(1 to listOf("t"))), "head") { "source" }
        layer.add(run)
        layer.withActiveRuns("head") { active ->
            layer.clear()
            assertEquals("source", active.single().snapshots["a.php"])
            assertEquals(mapOf(1 to listOf("t")), active.single().coverage["a.php"])
        }
        try { run.snapshots["a.php"]; fail("Archive must be closed after the last reader") } catch (_: IllegalStateException) { }
    }

    @Test
    fun `fully superseded archived runs release resources immediately`() {
        val layer = LocalCoverageLayer()
        val old = LocalCoverageArchive.store(setOf("t"), mapOf("a.php" to mapOf(1 to listOf("t"))), "head") { "old" }
        layer.add(old)
        layer.add(LocalCoverageRun(setOf("t"), emptyMap(), emptyMap(), "head"))
        try { old.coverage["a.php"]; fail("Superseded archive must be closed") } catch (_: IllegalStateException) { }
        layer.clear()
    }

    @Test
    fun `replacing a run with a view of its archive preserves the new owner`() {
        val layer = LocalCoverageLayer()
        val run = LocalCoverageArchive.store(setOf("t"), mapOf("a.php" to mapOf(1 to listOf("t"))), "old") { "source" }
        layer.add(run)
        layer.add(run.restampedTo("new"))
        assertEquals("source", layer.activeRuns("new").single().snapshots["a.php"])
        layer.clear()
    }

    @Test
    fun `archive keeps original coverage and source text after inputs change`() {
        val lines = mutableMapOf(1 to listOf("t1"), 2 to emptyList())
        val coverage = mutableMapOf("a.php" to lines)
        val sources = mutableMapOf("a.php" to "<?php\n// Пример\n", "b.php" to "")
        val run = LocalCoverageArchive.store(setOf("t1", "header-only"), coverage, "head") { sources.getValue(it) }
        lines.clear()
        coverage.clear()
        sources["a.php"] = "changed"

        assertEquals(setOf("t1", "header-only"), run.tests)
        assertEquals("head", run.commit)
        assertEquals(setOf("a.php"), run.coverage.keys)
        assertEquals(mapOf(1 to listOf("t1"), 2 to emptyList()), run.coverage["a.php"])
        assertEquals("<?php\n// Пример\n", run.snapshots["a.php"])
        assertNull(run.coverage["missing.php"])
        assertNull(run.snapshots["missing.php"])
        assertEquals(setOf("a.php"), run.snapshots.entries.map { it.key }.toSet())
    }

    @Test
    fun `archive supports empty source snapshots and files with no lines`() {
        val run = LocalCoverageArchive.store(setOf("t"), mapOf("a.php" to emptyMap()), null) { "" }
        assertEquals(emptyMap<Int, List<String>>(), run.coverage["a.php"])
        assertEquals("", run.snapshots["a.php"])
    }

    @Test
    fun `header only archived tests still supersede CI coverage`() {
        val layer = LocalCoverageLayer()
        layer.add(LocalCoverageArchive.store(setOf("t"), emptyMap(), "head") { error("No files to read") })
        assertEquals(setOf("t"), layer.activeTests("head"))
        assertEquals(
            mapOf(1 to emptyList<String>()),
            LocalCoverageMerge.merge(mapOf(1 to listOf("t")), null, layer.activeTests("head")),
        )
    }

    @Test
    fun `archived stale runs can be restored after partial supersession`() {
        val layer = LocalCoverageLayer()
        layer.add(LocalCoverageArchive.store(
            setOf("t1", "t2"),
            mapOf("a.php" to mapOf(1 to listOf("t1", "t2"), 2 to listOf("t1"))),
            "old-head",
        ) { "old source" })
        layer.add(LocalCoverageArchive.store(
            setOf("t1"), mapOf("b.php" to mapOf(3 to listOf("t1"))), "new-head",
        ) { "new source" })

        assertEquals(setOf("t1"), layer.activeTests("new-head"))
        assertEquals(1, layer.staleRuns("new-head").size)
        layer.restampTo("new-head")
        assertTrue(layer.staleRuns("new-head").isEmpty())
        assertEquals(setOf("t1", "t2"), layer.activeTests("new-head"))
        val old = layer.activeRuns("new-head").first()
        assertEquals(mapOf(1 to listOf("t2"), 2 to emptyList()), old.coverage["a.php"])
        assertEquals("old source", old.snapshots["a.php"])
    }

    @Test
    fun `local snapshots larger than heap remain available across many runs`() {
        val layer = LocalCoverageLayer()
        repeat(80) { index ->
            val test = "test-$index"
            layer.add(LocalCoverageArchive.store(
                setOf(test), mapOf("a.php" to mapOf(1 to listOf(test))), "head-$index",
            ) { "$index:" + "x".repeat(1024 * 1024) })
        }
        // Run with -Xmx64m: retaining these 80 MB of source strings would exhaust the heap.
        assertEquals(80, layer.runs().size)
        assertEquals(79, layer.staleRuns("head-79").size)
        layer.restampTo("head-79")
        val first = layer.activeRuns("head-79").first()
        assertTrue(first.snapshots.getValue("a.php").startsWith("0:"))
        assertEquals(1024 * 1024 + 2, first.snapshots.getValue("a.php").length)
        assertEquals(mapOf(1 to listOf("test-0")), first.coverage["a.php"])
        layer.clear()
        assertTrue(layer.isEmpty())
    }
}
