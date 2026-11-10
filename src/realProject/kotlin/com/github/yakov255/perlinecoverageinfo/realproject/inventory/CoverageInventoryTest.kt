package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure inventory rules over hardcoded GitLab answers: job-name parsing, shard/byte sums, the size
 * classes and the two pipeline selections (baseline from a push, branch tour).
 */
class CoverageInventoryTest {

    private val masterPush = pipeline(id = 100, ref = "master", source = "push", sha = "a".repeat(40))
    private val masterSchedule = pipeline(id = 106, ref = "master", source = "schedule", sha = "b".repeat(40))
    private val masterExpired = pipeline(id = 99, ref = "master", source = "push", sha = "c".repeat(40))
    private val feature = pipeline(id = 102, ref = "feature-x", source = "push", sha = "d".repeat(40))
    private val featureNewest = pipeline(id = 103, ref = "feature-x", source = "push", sha = "e".repeat(40))
    private val hotelsOnly = pipeline(id = 104, ref = "hotels-only", source = "push", sha = "f".repeat(40))
    private val noCoverage = pipeline(id = 105, ref = "no-coverage", source = "push", sha = "1".repeat(40))
    private val expiredBranch = pipeline(id = 107, ref = "expired", source = "push", sha = "2".repeat(40))

    private val coverage: List<PipelineCoverage> = listOf(
        masterExpired to jobs(raketaShards(7, bytes = 0)),
        masterPush to jobs(raketaShards(7, bytes = 4_586_000) + monoJob("hotels", 683_765)),
        masterSchedule to jobs(listOf(monoJob("static", 316_875))),
        feature to jobs(raketaShards(7, bytes = 3_000_000)),
        featureNewest to jobs(raketaShards(7, bytes = 5_600_000) + monoJob("hotels", 500_000)),
        hotelsOnly to jobs(listOf(monoJob("hotels", 700_000))),
        noCoverage to jobs(emptyList()),
        expiredBranch to jobs(raketaShards(7, bytes = 0)),
    ).map { (p, j) -> CoverageSummarizer.summarize(p, j) }

    @Test
    fun `job name gives the component token`() {
        assertEquals("raketa", coverageComponentOf("test:behat:coverage:raketa-8.5: [sprint:7]"))
        assertEquals("hotels", coverageComponentOf("test:behat:coverage:hotels-8.5"))
        assertEquals("static", coverageComponentOf("test:behat:coverage:static-8.5"))
        assertEquals("bus", coverageComponentOf("test:behat:coverage:bus-8.5"))
        assertNull(coverageComponentOf("codecov-8.5"))
        assertNull(coverageComponentOf("test:behat:coverage"))
    }

    @Test
    fun `shards and bytes are summed per component`() {
        val summary = CoverageSummarizer.summarize(
            masterPush,
            jobs(raketaShards(7, 4_000_000) + monoJob("hotels", 600_000) + monoJob("static", 300_000)),
        )

        assertEquals(setOf("raketa", "hotels", "static"), summary.components.keys)
        assertEquals(7, summary.shardsOf("raketa"))
        assertEquals(28_000_000L, summary.bytesOf("raketa"))
        assertEquals(1, summary.shardsOf("hotels"))
        assertEquals(600_000L, summary.bytesOf("hotels"))
        assertEquals(0, summary.shardsOf("avia"))
    }

    @Test
    fun `expired archive counts as a shard with zero bytes`() {
        val summary = CoverageSummarizer.summarize(masterPush, jobs(raketaShards(7, 0)))
        assertEquals(7, summary.shardsOf("raketa"))
        assertEquals(0L, summary.bytesOf("raketa"))
    }

    @Test
    fun `size classes follow the raketa spread`() {
        assertEquals(SizeClass.SMALL, CoverageInventory.classify(683_765))
        assertEquals(SizeClass.SMALL, CoverageInventory.classify(0))
        assertEquals(SizeClass.MEDIUM, CoverageInventory.classify(32_141_240))
        assertEquals(SizeClass.MEDIUM, CoverageInventory.classify(35_000_000))
        assertEquals(SizeClass.LARGE, CoverageInventory.classify(40_063_656))
    }

    @Test
    fun `baseline is the newest master push with coverage`() {
        val baseline = CoverageInventory.selectBaseline(coverage)

        assertEquals(masterPush.id, baseline?.pipeline?.id)
        // The newer master pipeline is a schedule and publishes no raketa coverage.
        assertEquals("push", baseline?.pipeline?.source)
        assertEquals(7, baseline?.shardsOf("raketa"))
        assertEquals(32_102_000L, baseline?.bytesOf("raketa"))
    }

    @Test
    fun `baseline is null without a master push that published coverage`() {
        val onlySchedules = coverage.filter { it.pipeline.ref != "master" || it.pipeline.source != "push" }
        assertNull(CoverageInventory.selectBaseline(onlySchedules))
    }

    @Test
    fun `branch tour keeps one newest pipeline per ref, drops empty archives and master`() {
        val branches = CoverageInventory.selectBranches(coverage)
        val refs = branches.map { it.pipeline.ref }

        assertEquals(listOf("feature-x"), refs)
        assertEquals(featureNewest.id, branches.first().pipeline.id)
        assertTrue("expired branches are not loadable", "expired" !in refs)
        assertTrue("a hotels-only branch has no raketa coverage", "hotels-only" !in refs)
        assertTrue("the baseline branch is not part of the tour", "master" !in refs)
        assertEquals(39_200_000L, branches.first().bytesOf("raketa"))
    }

    @Test
    fun `hotel branches are separate and sorted by hotel bytes`() {
        val hotels = CoverageInventory.selectHotelsBranches(coverage)

        assertEquals(listOf("hotels-only", "feature-x"), hotels.map { it.pipeline.ref })
        assertEquals(700_000L, hotels.first().bytesOf("hotels"))
        assertEquals(0L, hotels.first().bytesOf("raketa"))
    }

    @Test
    fun `limit caps both lists`() {
        assertEquals(1, CoverageInventory.selectBranches(coverage, limit = 1).size)
        assertEquals(1, CoverageInventory.selectHotelsBranches(coverage, limit = 1).size)
    }

    @Test
    fun `tour mixes size classes instead of taking only the biggest pipelines`() {
        val sized = (1..6).map { index ->
            pipeline(id = 300L + index, ref = "large-$index", source = "push", sha = "3".repeat(40)) to
                jobs(raketaShards(7, bytes = 5_600_000L + index))
        } + (1..6).map { index ->
            pipeline(id = 400L + index, ref = "medium-$index", source = "push", sha = "4".repeat(40)) to
                jobs(raketaShards(7, bytes = 4_500_000L + index))
        }
        val mixed = sized.map { (p, j) -> CoverageSummarizer.summarize(p, j) }

        val tour = CoverageInventory.selectMixedBranches(mixed, limit = 6)

        assertEquals(6, tour.size)
        assertEquals(3, tour.count { it.bytesOf("raketa") > LARGE_FROM_BYTES })
        assertEquals(3, tour.count { it.bytesOf("raketa") in SMALL_LIMIT_BYTES until LARGE_FROM_BYTES })
        assertEquals("large-6", tour[0].pipeline.ref)
        assertEquals("medium-6", tour[1].pipeline.ref)
        assertEquals("everything when the limit is not reached", 12, CoverageInventory.selectMixedBranches(mixed, limit = 24).size)
    }

    private fun pipeline(id: Long, ref: String, source: String, sha: String): ApiPipeline =
        InventoryJson.parsePipelines(
            """
            [{"id":$id,"iid":$id,"project_id":335,"sha":"$sha","ref":"$ref",
              "status":"success","source":"$source",
              "created_at":"2026-11-10T10:00:00.000+05:00",
              "updated_at":"2026-11-10T11:00:00.000+05:00",
              "web_url":"https://gitlab.raketa.in/raketa/raketa/-/pipelines/$id"}]
            """.trimIndent(),
        ).single()

    private fun jobs(entries: List<Pair<String, Long>>): List<ApiJob> =
        InventoryJson.parseJobs(
            entries.mapIndexed { index, (name, size) ->
                """{"id":${index + 1},"name":"$name","status":"success",
                   "artifacts_file":{"filename":"artifacts.zip","size":$size}}"""
            }.joinToString(prefix = "[", postfix = "]"),
        )

    private fun raketaShards(count: Int, bytes: Long): List<Pair<String, Long>> =
        (1..count).map { "test:behat:coverage:raketa-8.5: [sprint:$it]" to bytes }

    private fun monoJob(component: String, bytes: Long): Pair<String, Long> =
        "test:behat:coverage:$component-8.5" to bytes
}
