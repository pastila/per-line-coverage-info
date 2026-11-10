package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** The plan file is shared with the other work items, so its shape is asserted here. */
class InventoryPlanTest {

    private val master = pipeline(200, "master", "push", "aa".repeat(20))
    private val feature = pipeline(201, "feature/hotels", "push", "bb".repeat(20))
    private val hotelsBranch = pipeline(202, "hotels-only", "push", "cc".repeat(20))

    private val coverage = listOf(
        master to jobs(shards(7, 4_500_000), hotels = 683_765),
        feature to jobs(shards(7, 5_600_000), hotels = 512_000),
        hotelsBranch to jobs(emptyList(), hotels = 700_000),
    ).map { (p, j) -> CoverageSummarizer.summarize(p, j) }

    private val plan = InventoryPlan.build(
        coverage = coverage,
        repo = "/home/user/projects/raketa",
        worktree = "/tmp/raketa-load",
        localSha = { it.startsWith("aa") || it.startsWith("cc") },
        generatedAt = "2026-11-11T10:00:00Z",
    )

    @Test
    fun `plan carries the baseline and the branch lists`() {
        assertEquals("2026-11-11T10:00:00Z", plan.generatedAt)
        assertEquals("/home/user/projects/raketa", plan.repo)
        assertEquals("/tmp/raketa-load", plan.worktree)

        assertEquals("master", plan.baseline?.ref)
        assertEquals(200L, plan.baseline?.pipelineId)
        assertEquals(7, plan.baseline?.shards)
        assertEquals(31_500_000L, plan.baseline?.bytes)

        assertEquals(listOf("feature/hotels"), plan.branches.map { it.ref })
        val branch = plan.branches.single()
        assertEquals("large", branch.sizeClass)
        assertEquals(7, branch.shards)
        assertEquals(39_200_000L, branch.bytes)
        assertTrue(branch.hasHotels)
        assertEquals(512_000L, branch.hotelsBytes)
        assertFalse(branch.localSha)

        assertEquals(listOf("hotels-only", "feature/hotels"), plan.hotelsBranches.map { it.ref })
        val hotels = plan.hotelsBranches.first()
        assertEquals("small", hotels.sizeClass)
        assertEquals(700_000L, hotels.bytes)
        assertTrue(hotels.hasHotels)
        assertEquals(700_000L, hotels.hotelsBytes)
        assertTrue(hotels.localSha)
    }

    @Test
    fun `encoded plan keeps the documented keys`() {
        val root = Json.parseToJsonElement(InventoryPlan.encode(plan)).jsonObject

        assertEquals(
            setOf("generatedAt", "repo", "worktree", "baseline", "branches", "hotelsBranches"),
            root.keys,
        )
        val baseline = root.getValue("baseline").jsonObject
        assertEquals(setOf("ref", "sha", "pipelineId", "shards", "bytes"), baseline.keys)

        val branch = root.getValue("branches").jsonArray.single().jsonObject
        assertEquals(
            listOf("ref", "sha", "pipelineId", "shards", "bytes", "class", "hasHotels", "hotelsBytes", "localSha"),
            branch.keys.toList(),
        )
        assertEquals("large", branch.getValue("class").jsonPrimitive.content)
        assertEquals(40, branch.getValue("sha").jsonPrimitive.content.length)

        val hotelsBranch = root.getValue("hotelsBranches").jsonArray.first().jsonObject
        assertEquals("small", hotelsBranch.getValue("class").jsonPrimitive.content)
    }

    @Test
    fun `every pipeline sha of a branch that appears in both lists is prepared`() {
        val raketaNew = pipeline(501, "shared-branch", "push", "dd".repeat(20))
        val hotelsOld = pipeline(500, "shared-branch", "push", "ee".repeat(20))
        val both = listOf(
            raketaNew to jobs(shards(7, 4_500_000)),
            hotelsOld to jobs(emptyList(), hotels = 600_000),
        ).map { (p, j) -> CoverageSummarizer.summarize(p, j) }

        val targets = InventoryPlan.commitsToPrepare(InventoryPlan.select(both))

        assertEquals(2, targets.size)
        assertEquals(setOf("shared-branch"), targets.map { it.first }.toSet())
        assertEquals(
            setOf(raketaNew.sha, hotelsOld.sha),
            targets.map { it.second }.toSet(),
        )
    }

    @Test
    fun `a sha shared by two branches is prepared once`() {
        val first = pipeline(601, "first", "push", "ff".repeat(20))
        val second = pipeline(602, "second", "push", "ff".repeat(20))
        val shared = listOf(first, second).map { CoverageSummarizer.summarize(it, jobs(shards(7, 4_500_000))) }

        val targets = InventoryPlan.commitsToPrepare(InventoryPlan.select(shared))

        assertEquals(1, targets.size)
        assertEquals("first", targets.single().first)
    }

    @Test
    fun `plan without a coverage baseline keeps baseline null`() {
        val withoutBaseline = InventoryPlan.build(
            coverage = coverage.filter { it.pipeline.ref != "master" },
            repo = "/repo",
            worktree = "/wt",
            generatedAt = "2026-11-11T10:00:00Z",
        )
        assertTrue(Json.parseToJsonElement(InventoryPlan.encode(withoutBaseline)).jsonObject["baseline"] is JsonNull)
        assertTrue(withoutBaseline.branches.isNotEmpty())
    }

    @Test
    fun `write creates the parent directory and a trailing newline`() {
        val dir = Files.createTempDirectory("plan-write-test")
        try {
            val target: Path = dir.resolve("nested/plan.json")
            InventoryPlan.write(plan, target)
            val text = Files.readString(target)
            assertTrue(text.endsWith("\n"))
            assertTrue(Json.parseToJsonElement(text).jsonObject.containsKey("branches"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default path follows outputDir and the explicit property`() {
        val previousOutput = System.getProperty("realProject.outputDir")
        val previousPlan = System.getProperty("realProject.plan")
        try {
            System.clearProperty("realProject.plan")
            System.setProperty("realProject.outputDir", "/tmp/out")
            assertEquals(Path.of("/tmp/out", "plan.json"), InventoryPlan.defaultPath())

            System.setProperty("realProject.plan", "/tmp/explicit-plan.json")
            assertEquals(Path.of("/tmp/explicit-plan.json"), InventoryPlan.defaultPath())
        } finally {
            if (previousOutput == null) System.clearProperty("realProject.outputDir")
            else System.setProperty("realProject.outputDir", previousOutput)
            if (previousPlan == null) System.clearProperty("realProject.plan")
            else System.setProperty("realProject.plan", previousPlan)
        }
    }

    private fun pipeline(id: Long, ref: String, source: String, sha: String): ApiPipeline =
        InventoryJson.parsePipelines(
            """[{"id":$id,"sha":"$sha","ref":"$ref","status":"success","source":"$source"}]""",
        ).single()

    private fun shards(count: Int, bytes: Long): List<Pair<String, Long>> =
        (1..count).map { "test:behat:coverage:raketa-8.5: [sprint:$it]" to bytes }

    private fun jobs(entries: List<Pair<String, Long>>, hotels: Long? = null): List<ApiJob> {
        val all = entries + listOfNotNull(hotels?.let { "test:behat:coverage:hotels-8.5" to it })
        return InventoryJson.parseJobs(
            all.mapIndexed { index, (name, size) ->
                """{"id":${index + 1},"name":"$name","status":"success","artifacts_file":{"filename":"artifacts.zip","size":$size}}"""
            }.joinToString(prefix = "[", postfix = "]"),
        )
    }
}
