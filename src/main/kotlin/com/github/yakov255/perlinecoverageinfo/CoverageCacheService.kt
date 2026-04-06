package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/**
 * Manages disk-based cache of parsed coverage data.
 *
 * Cache structure:
 * ```
 * ~/.cache/coverage-plugin/<projectId>/
 *   <pipelineId>.json       -- serialized coverage map
 *   cache-index.json        -- metadata for all cached entries
 * ```
 */
@Service(Service.Level.PROJECT)
class CoverageCacheService(private val project: Project) {

    private val log = Logger.getInstance(CoverageCacheService::class.java)
    private val json = Json { prettyPrint = false }

    private fun cacheDir(): File {
        val settings = CoverageApiSettings.getInstance()
        val home = System.getProperty("user.home")
        return File(home, ".cache/coverage-plugin/${settings.gitlabProjectId}")
    }

    /**
     * Looks up cached coverage for a pipeline. Returns null if not cached or expired.
     */
    fun get(pipelineId: Long): CachedCoverage? {
        val index = readIndex() ?: return null
        val entry = index.entries.find { it.pipelineId == pipelineId } ?: return null
        val dataFile = File(cacheDir(), "${pipelineId}.json")
        if (!dataFile.exists()) return null

        return try {
            val data: Map<String, Map<String, List<String>>> =
                json.decodeFromString(dataFile.readText())
            // Convert String keys back to Int for line numbers
            val coverage = data.mapValues { (_, lineMap) ->
                lineMap.mapKeys { (k, _) -> k.toInt() }
            }
            CachedCoverage(
                coverage = coverage,
                commitHash = entry.commitHash,
                pipelineId = entry.pipelineId,
            )
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to read cached data for pipeline $pipelineId", e)
            null
        }
    }

    /**
     * Stores coverage data in the disk cache.
     */
    fun put(result: CoverageLoadResult) {
        val dir = cacheDir()
        dir.mkdirs()

        // Serialize coverage: convert Int keys to String for JSON
        val serializable = result.coverage.mapValues { (_, lineMap) ->
            lineMap.mapKeys { (k, _) -> k.toString() }
        }
        val dataFile = File(dir, "${result.resolved.pipelineId}.json")
        try {
            dataFile.writeText(json.encodeToString(serializable))
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to write data for pipeline ${result.resolved.pipelineId}", e)
            return
        }

        // Update index
        val index = readIndex() ?: CacheIndex(entries = emptyList())
        val newEntry = CacheEntry(
            pipelineId = result.resolved.pipelineId,
            commitHash = result.resolved.commitHash,
            timestampMs = System.currentTimeMillis(),
        )
        val updated = index.entries.filter { it.pipelineId != result.resolved.pipelineId } + newEntry
        writeIndex(CacheIndex(entries = updated))
        log.info("Coverage cache: stored pipeline ${result.resolved.pipelineId} (${result.coverage.size} files)")
    }

    /**
     * Removes cache entries older than [maxAgeDays] days.
     */
    fun cleanup(maxAgeDays: Int = 7) {
        val index = readIndex() ?: return
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000L
        val (keep, remove) = index.entries.partition { it.timestampMs > cutoff }

        if (remove.isEmpty()) return

        for (entry in remove) {
            val dataFile = File(cacheDir(), "${entry.pipelineId}.json")
            if (dataFile.exists()) {
                dataFile.delete()
                log.info("Coverage cache: cleaned up pipeline ${entry.pipelineId}")
            }
        }
        writeIndex(CacheIndex(entries = keep))
        log.info("Coverage cache: removed ${remove.size} expired entries, ${keep.size} remaining")
    }

    private fun readIndex(): CacheIndex? {
        val indexFile = File(cacheDir(), "cache-index.json")
        if (!indexFile.exists()) return null
        return try {
            json.decodeFromString<CacheIndex>(indexFile.readText())
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to read index", e)
            null
        }
    }

    private fun writeIndex(index: CacheIndex) {
        val dir = cacheDir()
        dir.mkdirs()
        val indexFile = File(dir, "cache-index.json")
        try {
            indexFile.writeText(json.encodeToString(index))
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to write index", e)
        }
    }

    companion object {
        fun getInstance(project: Project): CoverageCacheService = project.service()
    }
}

data class CachedCoverage(
    val coverage: Map<String, Map<Int, List<String>>>,
    val commitHash: String,
    val pipelineId: Long,
)

@Serializable
private data class CacheIndex(val entries: List<CacheEntry>)

@Serializable
private data class CacheEntry(
    val pipelineId: Long,
    val commitHash: String,
    val timestampMs: Long,
)
