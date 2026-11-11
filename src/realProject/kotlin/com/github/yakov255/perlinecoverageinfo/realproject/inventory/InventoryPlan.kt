package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Branch inventory written by the prepare step. The field names are shared with the other
 * real-project work items (see docs/real-project-testing.md), so they must not change.
 */
@Serializable
data class CoveragePlan(
    val generatedAt: String,
    val repo: String,
    val worktree: String,
    val baseline: BaselineEntry? = null,
    val branches: List<PlanEntry> = emptyList(),
    val hotelsBranches: List<PlanEntry> = emptyList(),
)

/** The coverage-branch pipeline that every load uses as the baseline. */
@Serializable
data class BaselineEntry(
    val ref: String,
    val sha: String,
    val pipelineId: Long,
    val shards: Int,
    val bytes: Long,
)

/** One branch of the tour: a pipeline and the coverage size it publishes. */
@Serializable
data class PlanEntry(
    val ref: String,
    val sha: String,
    val pipelineId: Long,
    val shards: Int,
    val bytes: Long,
    @SerialName("class") val sizeClass: String,
    val hasHotels: Boolean,
    val hotelsBytes: Long,
    /** Whether the commit exists in the local repository after the prepare step. */
    val localSha: Boolean,
)

object InventoryPlan {
    const val DEFAULT_BRANCH_LIMIT = 24
    const val DEFAULT_HOTELS_LIMIT = 12

    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** The pipelines the plan is built from. */
    data class Selection(
        val baseline: PipelineCoverage?,
        val branches: List<PipelineCoverage>,
        val hotelsBranches: List<PipelineCoverage>,
    )

    fun select(
        coverage: List<PipelineCoverage>,
        coverageBranch: String = COVERAGE_BRANCH,
        branchLimit: Int = DEFAULT_BRANCH_LIMIT,
        hotelsLimit: Int = DEFAULT_HOTELS_LIMIT,
    ): Selection = Selection(
        baseline = CoverageInventory.selectBaseline(coverage, RAKETA_COMPONENT, coverageBranch),
        branches = CoverageInventory.selectMixedBranches(
            coverage,
            RAKETA_COMPONENT,
            coverageBranch,
            branchLimit,
        ),
        hotelsBranches = CoverageInventory.selectHotelsBranches(
            coverage,
            coverageBranch,
            hotelsLimit,
        ),
    )

    /**
     * Every ref/sha the plan mentions, deduplicated by sha. A branch can appear with one pipeline
     * in the raketa list and a different one in the hotels list, so both commits must be prepared.
     */
    fun commitsToPrepare(selection: Selection): List<Pair<String, String>> {
        val wanted = LinkedHashMap<String, String>()
        val all = listOfNotNull(selection.baseline) + selection.branches + selection.hotelsBranches
        for (pipeline in all) {
            wanted.putIfAbsent(pipeline.pipeline.sha, pipeline.pipeline.ref)
        }
        return wanted.map { (sha, ref) -> ref to sha }
    }

    fun build(
        coverage: List<PipelineCoverage>,
        repo: String,
        worktree: String,
        coverageBranch: String = COVERAGE_BRANCH,
        branchLimit: Int = DEFAULT_BRANCH_LIMIT,
        hotelsLimit: Int = DEFAULT_HOTELS_LIMIT,
        localSha: (String) -> Boolean = { false },
        generatedAt: String = Instant.now().toString(),
    ): CoveragePlan {
        val selection = select(coverage, coverageBranch, branchLimit, hotelsLimit)
        val baseline = selection.baseline
            ?.let { entry ->
                BaselineEntry(
                    ref = entry.pipeline.ref,
                    sha = entry.pipeline.sha,
                    pipelineId = entry.pipeline.id,
                    shards = entry.shardsOf(RAKETA_COMPONENT),
                    bytes = entry.bytesOf(RAKETA_COMPONENT),
                )
            }

        val branches = selection.branches.map { raketaEntry(it, RAKETA_COMPONENT, localSha) }
        val hotelsBranches = selection.hotelsBranches.map { raketaEntry(it, HOTELS_COMPONENT, localSha) }

        return CoveragePlan(
            generatedAt = generatedAt,
            repo = repo,
            worktree = worktree,
            baseline = baseline,
            branches = branches,
            hotelsBranches = hotelsBranches,
        )
    }

    private fun raketaEntry(
        coverage: PipelineCoverage,
        component: String,
        localSha: (String) -> Boolean,
    ): PlanEntry {
        val hotels = coverage.component(HOTELS_COMPONENT)
        val bytes = coverage.bytesOf(component)
        return PlanEntry(
            ref = coverage.pipeline.ref,
            sha = coverage.pipeline.sha,
            pipelineId = coverage.pipeline.id,
            shards = coverage.shardsOf(component),
            bytes = bytes,
            sizeClass = CoverageInventory.classify(bytes).label,
            hasHotels = (hotels?.bytes ?: 0L) > 0L,
            hotelsBytes = hotels?.bytes ?: 0L,
            localSha = localSha(coverage.pipeline.sha),
        )
    }

    fun encode(plan: CoveragePlan): String = json.encodeToString(plan)

    fun write(plan: CoveragePlan, target: Path): Path {
        target.parent?.let { Files.createDirectories(it) }
        Files.writeString(target, encode(plan) + "\n")
        return target
    }

    /** `realProject.plan`, or `<realProject.outputDir>/plan.json`. */
    fun defaultPath(): Path {
        val explicit = System.getProperty("realProject.plan")
            ?: System.getenv("REAL_PROJECT_PLAN")
        if (!explicit.isNullOrBlank()) return Path.of(explicit)
        return Path.of(outputDir(), "plan.json")
    }

    fun outputDir(): String = RealProjectSettings.value(
        "realProject.outputDir",
        Path.of("build", "real-project").toString(),
    )
}
