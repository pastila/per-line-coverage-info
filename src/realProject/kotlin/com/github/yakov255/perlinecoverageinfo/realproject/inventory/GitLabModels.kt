package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Prefix of the CI job that publishes one coverage archive: `test:behat:coverage:<component>[-<version>][: shard]`. */
const val COVERAGE_JOB_PREFIX = "test:behat:coverage:"

/** Component whose coverage is merged into the main `.cov4`. */
const val RAKETA_COMPONENT = "raketa"

/** Component whose coverage lives under `api/hotels`. */
const val HOTELS_COMPONENT = "hotels"

/** Default coverage branch (the baseline pipeline is taken from it). */
const val COVERAGE_BRANCH = "master"

/** One pipeline as returned by `GET /projects/:id/pipelines`. */
@Serializable
data class ApiPipeline(
    val id: Long,
    val iid: Long = 0,
    val sha: String,
    val ref: String,
    val status: String = "",
    val source: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("web_url") val webUrl: String = "",
)

/** `artifacts_file` of a job; `null` means the archive has expired or was never uploaded. */
@Serializable
data class ApiArtifactsFile(
    val filename: String = "",
    val size: Long = 0,
)

/** One job as returned by `GET /projects/:id/pipelines/:pipeline_id/jobs`. */
@Serializable
data class ApiJob(
    val id: Long,
    val name: String,
    val status: String = "",
    @SerialName("artifacts_file") val artifactsFile: ApiArtifactsFile? = null,
)

/** Coverage archives of one component inside one pipeline. */
data class ComponentCoverage(
    val shards: Int,
    val bytes: Long,
)

/**
 * One pipeline together with the coverage archives it published.
 * [components] is keyed by component token (`raketa`, `hotels`, `static`, `bus`, ...).
 */
data class PipelineCoverage(
    val pipeline: ApiPipeline,
    val components: Map<String, ComponentCoverage>,
) {
    fun component(token: String): ComponentCoverage? = components[token]

    fun bytesOf(token: String): Long = components[token]?.bytes ?: 0L

    fun shardsOf(token: String): Int = components[token]?.shards ?: 0
}

/** Result of one inventory scan: the pipelines read and their coverage archives. */
data class InventorySnapshot(
    val projectId: String,
    val pipelinesScanned: Int,
    val coverage: List<PipelineCoverage>,
) {
    /** Every component token seen in any scanned pipeline, sorted. */
    val components: List<String>
        get() = coverage.flatMap { it.components.keys }.distinct().sorted()
}

/** JSON readers for the GitLab answers; tolerant to unknown and missing fields. */
object InventoryJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun parsePipelines(body: String): List<ApiPipeline> = json.decodeFromString(body)

    fun parseJobs(body: String): List<ApiJob> = json.decodeFromString(body)
}

/**
 * Component token of a coverage job name, or `null` when the job does not publish coverage.
 * `test:behat:coverage:raketa-8.5: [sprint:7]` -> `raketa`,
 * `test:behat:coverage:hotels-8.5` -> `hotels`.
 */
fun coverageComponentOf(jobName: String): String? {
    if (!jobName.startsWith(COVERAGE_JOB_PREFIX)) return null
    val token = jobName.removePrefix(COVERAGE_JOB_PREFIX).substringBefore(':').trim()
    // Drop the trailing product version ("-8.5"), keeping a dash inside the component name.
    val component = token.replace(Regex("""-\d+(?:\.\d+)*$"""), "").trim()
    return component.ifEmpty { null }
}

/** Sums the coverage jobs of [jobs] per component into one [PipelineCoverage]. */
object CoverageSummarizer {
    fun summarize(pipeline: ApiPipeline, jobs: List<ApiJob>): PipelineCoverage {
        val shards = LinkedHashMap<String, Int>()
        val bytes = LinkedHashMap<String, Long>()
        for (job in jobs) {
            val component = coverageComponentOf(job.name) ?: continue
            shards[component] = (shards[component] ?: 0) + 1
            bytes[component] = (bytes[component] ?: 0L) + (job.artifactsFile?.size ?: 0L)
        }
        val components = LinkedHashMap<String, ComponentCoverage>()
        for ((component, count) in shards) {
            components[component] = ComponentCoverage(count, bytes[component] ?: 0L)
        }
        return PipelineCoverage(pipeline, components)
    }
}
