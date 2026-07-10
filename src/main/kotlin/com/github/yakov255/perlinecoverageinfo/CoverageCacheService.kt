package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
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
 *   <commitHash>-<component>.cov4   -- component-scoped binary coverage file
 *   cache-index.json                 -- metadata for all cached entries
 * ```
 *
 * Cache key is the **commit hash + component** (not pipeline ID), since the same commit
 * always produces the same coverage regardless of which pipeline ran it.
 */
@Service(Service.Level.PROJECT)
class CoverageCacheService(private val project: Project) {

    private val log = CoverageLog.get(CoverageCacheService::class.java)
    private val json = Json { prettyPrint = false }

    private fun cacheDir(): File {
        val settings = CoverageApiSettings.getInstance()
        val home = System.getProperty("user.home")
        return File(home, ".cache/coverage-plugin/${settings.gitlabProjectId}")
    }

    private fun cov4FileName(commitHash: String, component: String): String =
        "${commitHash}-${component.replace('/', '_')}.cov4"

    /**
     * Returns the set of all commit hashes that have cached .cov4 files.
     * Used for fast offline lookup without opening readers.
     */
    fun cachedCommitHashes(): Set<String> {
        val index = readIndex() ?: return emptySet()
        val dir = cacheDir()
        return index.entries
            .filter { File(dir, cov4FileName(it.commitHash, it.component)).exists() }
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
     * Looks up cached coverage for a commit and component.
     * Returns a [Cov4Reader] for on-demand file access, or null if not cached.
     * The caller owns the reader lifecycle and must close it when done.
     */
    fun get(commitHash: String, component: String): Cov4Reader? {
        val index = readIndex() ?: return null
        val entry = index.entries.find { it.commitHash == commitHash && it.component == component } ?: return null
        val cov4File = File(cacheDir(), cov4FileName(commitHash, component))
        if (!cov4File.exists()) return null

        return try {
            Cov4Reader(cov4File).also {
                log.info("Coverage cache: opened COV4 reader for commit $commitHash (${cov4File.length() / 1024}KB, component=$component)")
            }
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to open COV4 file for commit $commitHash (component=$component)", e)
            null
        }
    }

    /**
     * Updates the last-used timestamp for an existing cache entry.
     * Called whenever an artifact is activated so the Artifacts panel can show
     * when each artifact was last viewed.
     */
    fun updateLastUsed(commitHash: String, component: String) {
        val index = readIndex() ?: return
        if (index.entries.none { it.commitHash == commitHash && it.component == component }) return
        val updated = index.entries.map { entry ->
            if (entry.commitHash == commitHash && entry.component == component)
                entry.copy(lastUsedMs = System.currentTimeMillis()) else entry
        }
        writeIndex(CacheIndex(entries = updated))
    }

    /**
     * Writes merged coverage data to a .cov4 cache file.
     * Computes and persists coverage statistics (file count, line counts) into the cache index.
     */
    fun writeCov4(
        commitHash: String,
        pipelineId: Long,
        coverage: Map<String, Map<Int, List<String>>>,
        component: String,
        branch: String? = null,
    ) {
        cleanup()
        val dir = cacheDir()
        dir.mkdirs()
        val cov4File = File(dir, cov4FileName(commitHash, component))

        try {
            Cov4Writer.write(coverage, cov4File)
        } catch (e: Exception) {
            log.warn("Coverage cache: failed to write COV4 for commit $commitHash (component=$component)", e)
            return
        }

        val totalFiles = coverage.size
        val totalLines = coverage.values.sumOf { it.size }
        val coveredLines = coverage.values.sumOf { lineMap -> lineMap.values.count { it.isNotEmpty() } }

        val now = System.currentTimeMillis()
        val index = readIndex() ?: CacheIndex(entries = emptyList())
        val newEntry = CacheEntry(
            commitHash = commitHash,
            pipelineId = pipelineId,
            component = component,
            branch = branch,
            timestampMs = now,
            totalFiles = totalFiles,
            totalLines = totalLines,
            coveredLines = coveredLines,
            lastUsedMs = now,
        )
        val updated = index.entries.filter { it.commitHash != commitHash || it.component != component } + newEntry
        writeIndex(CacheIndex(entries = updated))

        log.info("Coverage cache: stored commit $commitHash as COV4 (${cov4File.length() / 1024}KB, component=$component, $totalFiles files, $coveredLines/$totalLines lines covered)")
    }

    /**
     * Deletes the cached artifact for [commitHash] — both the .cov4 file and its index entry.
     * Returns true if the file was deleted (or was already absent), false on I/O error.
     */
    fun deleteArtifact(commitHash: String, component: String): Boolean {
        val dir = cacheDir()
        val cov4File = File(dir, cov4FileName(commitHash, component))

        if (cov4File.exists() && !cov4File.delete()) {
            log.warn("Coverage cache: failed to delete ${cov4File.absolutePath}")
            return false
        }

        val index = readIndex() ?: CacheIndex(entries = emptyList())
        val updated = index.entries.filter { it.commitHash != commitHash || it.component != component }
        if (updated.size != index.entries.size) {
            writeIndex(CacheIndex(entries = updated))
        }

        log.info("Coverage cache: deleted artifact for commit $commitHash (component=$component)")
        return true
    }

    /**
     * Returns metadata for all locally cached artifacts, sorted newest-first.
     * Entries whose .cov4 file is missing from disk are excluded.
     */
    fun listArtifacts(): List<ArtifactInfo> {
        val dir = cacheDir()
        val index = readIndex() ?: return emptyList()
        return index.entries
            .sortedByDescending { it.timestampMs }
            .mapNotNull { entry ->
                val cov4File = File(dir, cov4FileName(entry.commitHash, entry.component))
                if (!cov4File.exists()) return@mapNotNull null
                ArtifactInfo(
                    commitHash = entry.commitHash,
                    component = entry.component,
                    pipelineId = entry.pipelineId,
                    timestampMs = entry.timestampMs,
                    fileSizeBytes = cov4File.length(),
                    totalFiles = entry.totalFiles,
                    totalLines = entry.totalLines,
                    coveredLines = entry.coveredLines,
                    lastUsedMs = entry.lastUsedMs,
                    branch = entry.branch,
                )
            }
    }

    /**
     * Removes cache entries older than [maxAgeDays] days, then reconciles the
     * index with the files actually on disk:
     *  - index entries whose `.cov4` file is missing are dropped
     *  - `.cov4` files in the cache dir with no matching index entry are deleted
     *
     * If a `.cov4` file fails to delete, the matching index entry is kept so the
     * next cleanup can retry.
     */
    fun cleanup(maxAgeDays: Int = 7) {
        val dir = cacheDir()
        val index = readIndex() ?: CacheIndex(entries = emptyList())
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000L
        val (freshEntries, expiredEntries) = index.entries.partition { (it.lastUsedMs ?: 0) > cutoff }

        val expiredRetained = mutableListOf<CacheEntry>()
        var expiredDeleted = 0
        for (entry in expiredEntries) {
            val cov4File = File(dir, cov4FileName(entry.commitHash, entry.component))
            if (!cov4File.exists()) {
                continue
            }
            if (cov4File.delete()) {
                expiredDeleted++
                log.info("Coverage cache: cleaned up commit ${entry.commitHash} (component=${entry.component})")
            } else {
                log.warn("Coverage cache: failed to delete ${cov4File.absolutePath}; will retry on next cleanup")
                expiredRetained.add(entry)
            }
        }

        // Drop index entries whose file is missing (crashed write, manual rm, etc.).
        val (existing, missing) = freshEntries.partition {
            File(dir, cov4FileName(it.commitHash, it.component)).exists()
        }
        if (missing.isNotEmpty()) {
            log.info("Coverage cache: dropped ${missing.size} index entries with no file on disk")
        }

        // Delete orphan .cov4 files on disk that no index entry references.
        val knownKeys = (existing + expiredRetained).map { it.commitHash to it.component }.toHashSet()
        var orphansDeleted = 0
        var orphansFailed = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(".cov4") }?.forEach { file ->
            val key = fileKey(file)
            if (key !in knownKeys) {
                if (file.delete()) {
                    orphansDeleted++
                    log.info("Coverage cache: deleted orphan file ${file.name}")
                } else {
                    orphansFailed++
                    log.warn("Coverage cache: failed to delete orphan ${file.absolutePath}")
                }
            }
        }

        val finalEntries = existing + expiredRetained
        if (finalEntries.size != index.entries.size) {
            writeIndex(CacheIndex(entries = finalEntries))
        }

        if (expiredDeleted > 0 || missing.isNotEmpty() || orphansDeleted > 0 || orphansFailed > 0) {
            log.info(
                "Coverage cache: cleanup done — expired=$expiredDeleted, " +
                    "missingIndexDropped=${missing.size}, orphansDeleted=$orphansDeleted, " +
                    "orphansFailed=$orphansFailed, remaining=${finalEntries.size}"
            )
        }
    }

    /**
     * Parses a .cov4 filename back into (commitHash, component).
     * Example: "a280a1c1-api_avia.cov4" → ("a280a1c1", "api_avia")
     */
    private fun fileKey(file: File): Pair<String, String> {
        val name = file.name.removeSuffix(".cov4")
        val dash = name.indexOf('-')
        return if (dash > 0) {
            name.substring(0, dash) to name.substring(dash + 1).replace('_', '/')
        } else {
            // Legacy file without component — ignore by using empty string
            name to ""
        }
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
    val totalFiles: Int = 0,
    val totalLines: Int = 0,
    val coveredLines: Int = 0,
    val lastUsedMs: Long? = null,
    val component: String = "",
    val branch: String? = null,
)

/**
 * Public metadata for a locally cached coverage artifact,
 * used by [CoverageArtifactsPanel] to display the artifact list.
 */
data class ArtifactInfo(
    val commitHash: String,
    val pipelineId: Long,
    val timestampMs: Long,
    val fileSizeBytes: Long,
    val totalFiles: Int,
    val totalLines: Int,
    val coveredLines: Int,
    val lastUsedMs: Long? = null,
    val component: String,
    val branch: String? = null,
) {
    /** Percentage of tracked lines covered by at least one test, or null for legacy entries. */
    val coveragePercent: Float?
        get() = if (totalLines > 0) coveredLines.toFloat() / totalLines * 100f else null
}
