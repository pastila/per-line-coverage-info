package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages disk-based cache of coverage data in COV4 binary format.
 *
 * Cache structure:
 * ```
 * ~/.cache/coverage-plugin/<projectId>/
 *   <commitHash>.cov4       -- merged binary coverage file (indexed, random-access)
 *   cache-index.json        -- metadata for all cached entries
 * ```
 *
 * Cache key is the **commit hash** (not pipeline ID), since the same commit
 * always produces the same coverage regardless of which pipeline ran it.
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
     * Returns the set of all commit hashes that have cached .cov4 files.
     * Used for fast offline lookup without opening readers.
     */
    fun cachedCommitHashes(): Set<String> {
        val index = readIndex() ?: return emptySet()
        val dir = cacheDir()
        return index.entries
            .filter { File(dir, "${it.commitHash}.cov4").exists() }
            .map { it.commitHash }
            .toSet()
    }

    /**
     * Finds the first commit from [candidates] that has a cached .cov4 file.
     * Returns the commit hash, or null if none are cached.
     */
    fun findCachedCommit(candidates: List<String>): String? {
        val cached = cachedCommitHashes()
        if (cached.isEmpty()) return null
        return candidates.firstOrNull { it in cached }
    }

    /**
     * Looks up cached coverage for a commit.
     * Returns a [Cov4Reader] for on-demand file access, or null if not cached.
     * The caller owns the reader lifecycle and must close it when done.
     */
    fun get(commitHash: String): Cov4Reader? {
        val index = readIndex() ?: return null
        val entry = index.entries.find { it.commitHash == commitHash } ?: return null
        val cov4File = File(cacheDir(), "${commitHash}.cov4")
        if (!cov4File.exists()) return null

        return try {
            Cov4Reader(cov4File).also {
                log.info("Coverage cache: opened COV4 reader for commit $commitHash (${cov4File.length() / 1024}KB)")
            }
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to open COV4 file for commit $commitHash", e)
            null
        }
    }

    /**
     * Writes merged coverage data to a .cov4 cache file.
     */
    fun writeCov4(commitHash: String, pipelineId: Long, coverage: Map<String, Map<Int, List<String>>>) {
        val dir = cacheDir()
        dir.mkdirs()
        val cov4File = File(dir, "${commitHash}.cov4")

        try {
            Cov4Writer.write(coverage, cov4File)
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to write COV4 for commit $commitHash", e)
            return
        }

        val index = readIndex() ?: CacheIndex(entries = emptyList())
        val newEntry = CacheEntry(
            commitHash = commitHash,
            pipelineId = pipelineId,
            timestampMs = System.currentTimeMillis(),
        )
        val updated = index.entries.filter { it.commitHash != commitHash } + newEntry
        writeIndex(CacheIndex(entries = updated))

        log.info("Coverage cache: stored commit $commitHash as COV4 (${cov4File.length() / 1024}KB, ${coverage.size} files)")
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
            val cov4File = File(cacheDir(), "${entry.commitHash}.cov4")
            if (cov4File.exists()) {
                cov4File.delete()
                log.info("Coverage cache: cleaned up commit ${entry.commitHash}")
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

@Serializable
private data class CacheIndex(val entries: List<CacheEntry>)

@Serializable
private data class CacheEntry(
    val commitHash: String,
    val pipelineId: Long,
    val timestampMs: Long,
)
