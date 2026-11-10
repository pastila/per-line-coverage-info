package com.github.yakov255.perlinecoverageinfo.realproject.inventory

/** Size class of a set of coverage archives, used to build a branch tour of mixed weight. */
enum class SizeClass(val label: String) {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
}

/** Upper bound (exclusive) of `small`; raketa coverage of a normal branch is larger than this. */
const val SMALL_LIMIT_BYTES: Long = 8L * 1024 * 1024

/** Lower bound (inclusive) of `large`; raketa coverage of a big branch is about 40 MB. */
const val LARGE_FROM_BYTES: Long = 36L * 1024 * 1024

/**
 * Selection rules over the scanned pipelines. Pure: no network, no git, no files.
 * Used by the inventory and by the tests over hardcoded API answers.
 */
object CoverageInventory {
    fun classify(bytes: Long): SizeClass = when {
        bytes >= LARGE_FROM_BYTES -> SizeClass.LARGE
        bytes >= SMALL_LIMIT_BYTES -> SizeClass.MEDIUM
        else -> SizeClass.SMALL
    }

    /**
     * The baseline pipeline: the newest `push` pipeline of [branch] that published non-empty
     * [component] coverage. Scheduled pipelines never publish coverage, so they never qualify.
     */
    fun selectBaseline(
        coverage: List<PipelineCoverage>,
        component: String = RAKETA_COMPONENT,
        branch: String = COVERAGE_BRANCH,
    ): PipelineCoverage? = coverage
        .filter { it.pipeline.ref == branch && it.pipeline.source == "push" && it.bytesOf(component) > 0 }
        .maxByOrNull { it.pipeline.id }

    /**
     * Feature branches with non-empty [component] coverage, newest pipeline per ref, biggest first.
     * Pipelines whose archives have expired (zero bytes) are dropped: they cannot be loaded.
     */
    fun selectBranches(
        coverage: List<PipelineCoverage>,
        component: String = RAKETA_COMPONENT,
        excludeRef: String = COVERAGE_BRANCH,
        limit: Int = Int.MAX_VALUE,
    ): List<PipelineCoverage> = coverage
        .filter { it.pipeline.ref != excludeRef && it.bytesOf(component) > 0 }
        .groupBy { it.pipeline.ref }
        .map { (_, sameRef) -> sameRef.maxBy { it.pipeline.id } }
        .sortedByDescending { it.bytesOf(component) }
        .take(limit)

    /** Branches that published hotel coverage (component [HOTELS_COMPONENT]), biggest first. */
    fun selectHotelsBranches(
        coverage: List<PipelineCoverage>,
        excludeRef: String = COVERAGE_BRANCH,
        limit: Int = Int.MAX_VALUE,
    ): List<PipelineCoverage> = coverage
        .filter { it.pipeline.ref != excludeRef && it.bytesOf(HOTELS_COMPONENT) > 0 }
        .groupBy { it.pipeline.ref }
        .map { (_, sameRef) -> sameRef.maxBy { it.pipeline.id } }
        .sortedByDescending { it.bytesOf(HOTELS_COMPONENT) }
        .take(limit)

    /**
     * The branch tour: at most [limit] branches, round-robin over the size classes so that the
     * medium (≈32 MB) and the large (≈40 MB) raketa pipelines both appear. Biggest first inside
     * each class. Without a mix the tour would only contain the newest, largest pipelines.
     */
    fun selectMixedBranches(
        coverage: List<PipelineCoverage>,
        component: String = RAKETA_COMPONENT,
        excludeRef: String = COVERAGE_BRANCH,
        limit: Int = Int.MAX_VALUE,
    ): List<PipelineCoverage> {
        val candidates = selectBranches(coverage, component, excludeRef)
        if (candidates.size <= limit) return candidates
        val order = listOf(SizeClass.LARGE, SizeClass.MEDIUM, SizeClass.SMALL)
        val groups = order.associateWith { sizeClass ->
            candidates.filter { classify(it.bytesOf(component)) == sizeClass }
        }
        val picked = ArrayList<PipelineCoverage>(limit)
        var round = 0
        while (picked.size < limit) {
            var taken = false
            for (sizeClass in order) {
                val group = groups.getValue(sizeClass)
                if (round < group.size) {
                    picked += group[round]
                    taken = true
                    if (picked.size == limit) break
                }
            }
            if (!taken) break
            round++
        }
        return picked
    }
}
