package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Read-only view of the coverage cache written by the covd daemon.
 *
 * ```
 * ~/.cache/coverage-plugin/<projectId>/
 *   <commitHash>-<component>.cov4   -- binary per-line coverage
 *   cache-index.json                -- metadata, written only by the daemon
 * ```
 *
 * The plugin never writes the index or deletes files itself: mutations go through the
 * daemon API, otherwise two writers would corrupt `cache-index.json`.
 */
@Service(Service.Level.PROJECT)
class CoverageCacheService(private val project: Project) {

    private val log = CoverageLog.get(CoverageCacheService::class.java)

    // The daemon may add fields to its index at any time; a new field must not make the whole
    // cache invisible to the plugin (offline-first and the Artifacts tab read this file).
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private fun cacheDir(): File {
        val settings = CoverageApiSettings.getInstance()
        val root = CoveragePaths.cacheRoot(cacheRootOverride)
        return File(root, settings.gitlabProjectId.toString())
    }

    private fun canonicalComponent(component: String): String = ComponentConfig.allEntries()
        .firstOrNull { it.componentId == component || it.coverageKey == component }?.coverageKey ?: component

    private fun cov4FileName(commitHash: String, component: String): String =
        "${commitHash}-${component.replace('/', '_')}.cov4"

    /** Commit hashes that have a cached `.cov4` on disk. Used for the offline-first lookup. */
    fun cachedCommitHashes(): Set<String> {
        val index = readIndex() ?: return emptySet()
        val dir = cacheDir()
        return index.entries
            .filter { File(dir, cov4FileName(it.commitHash, it.component)).exists() }
            .map { it.commitHash }
            .toSet()
    }

    /**
     * Returns the most recent cached commit of [component] among [candidates], or null.
     *
     * The component filter matters: a cache directory can hold artifacts of several components,
     * and a commit cached for another component has no coverage for this one.
     */
    fun findCachedCommit(candidates: List<String>, component: String): String? {
        val index = readIndex() ?: return null
        val dir = cacheDir()
        val available = index.entries
            .filter { canonicalComponent(it.component) == canonicalComponent(component) }
            .filter { File(dir, cov4FileName(it.commitHash, it.component)).exists() }
            .map { it.commitHash }
            .toSet()
        return candidates.firstOrNull { it in available }
    }

    /**
     * Opens a reader for a cached artifact, sharing it between windows of the same repo.
     * Returns null when the index has no entry or the file is missing.
     */
    fun get(commitHash: String, component: String, gitRoot: File? = null): Cov4Reader? {
        val index = readIndex() ?: return null
        val entry = index.entries.firstOrNull { it.commitHash == commitHash && it.component == component }
            ?: index.entries.firstOrNull {
                it.commitHash == commitHash && canonicalComponent(it.component) == canonicalComponent(component)
            } ?: return null
        val cov4File = File(cacheDir(), cov4FileName(commitHash, entry.component))
        val identity = try {
            val attributes = Files.readAttributes(cov4File.toPath(), BasicFileAttributes::class.java)
            "${cov4File.canonicalPath}|${attributes.fileKey()}|${attributes.size()}|${attributes.lastModifiedTime()}"
        } catch (_: java.io.IOException) {
            return null
        }

        // Old caches were written before path normalization; normalizing while reading keeps
        // them usable. For the current daemon-written layout this is a no-op.
        val pathNormalizer: (String) -> String
        val testNormalizer: (String) -> String
        if (component == "raketa") {
            val suites = CoveragePathNormalizer.raketaFeatureSuiteEntries(gitRoot)
            pathNormalizer = { CoveragePathNormalizer.normalizeRaketaFilePath(it) }
            testNormalizer = { CoveragePathNormalizer.normalizeRaketaBehatName(it, suites) }
        } else {
            pathNormalizer = { it }
            testNormalizer = { it }
        }

        return GitLabCoordinator.getInstance().sharedReader(
            commitHash,
            component,
            open = {
                try {
                    CoveragePerfLog.span("cov4.open", "commit" to commitHash.take(8), "component" to component, "bytes" to cov4File.length()) {
                        Cov4Reader(cov4File, pathNormalizer, testNormalizer).also {
                            log.info("Coverage cache: opened COV4 reader for commit $commitHash (${cov4File.length() / 1024}KB, component=$component)")
                        }
                    }
                } catch (e: Exception) {
                    log.warn("Coverage cache: failed to open COV4 file for commit $commitHash (component=$component)", e)
                    null
                }
            },
            variant = "$identity|" +
                if (component == "raketa") "norm-v1" else "plain",
        )
    }

    /** Deletes a cached artifact through the daemon, which owns the cache and the index. */
    fun deleteArtifact(commitHash: String, component: String): Boolean {
        val settings = CoverageApiSettings.getInstance()
        val storedComponent = readIndex()?.entries?.firstOrNull {
            it.commitHash == commitHash && canonicalComponent(it.component) == canonicalComponent(component)
        }?.component ?: component
        return CovdClient.deleteArtifact(settings.gitlabProjectId.toString(), commitHash, storedComponent).also { deleted ->
            if (deleted) GitLabCoordinator.getInstance().evictSharedReaders(commitHash, component)
        }
    }

    /** Cached artifacts for the Artifacts panel, newest first. */
    fun listArtifacts(): List<ArtifactInfo> {
        val index = readIndex() ?: return emptyList()
        val dir = cacheDir()
        return index.entries.mapNotNull { entry ->
            val file = File(dir, cov4FileName(entry.commitHash, entry.component))
            if (!file.exists()) return@mapNotNull null
            ArtifactInfo(
                commitHash = entry.commitHash,
                pipelineId = entry.pipelineId,
                timestampMs = entry.timestampMs,
                fileSizeBytes = file.length(),
                totalFiles = entry.totalFiles,
                totalLines = entry.totalLines,
                coveredLines = entry.coveredLines,
                lastUsedMs = entry.lastUsedMs,
                component = canonicalComponent(entry.component),
                branch = entry.branch,
            )
        }.sortedByDescending { it.lastUsedMs ?: it.timestampMs }
    }

    private fun readIndex(): CacheIndex? {
        return try {
            val indexFile = File(cacheDir(), "cache-index.json")
            if (!indexFile.isFile) return null
            json.decodeFromString(CacheIndex.serializer(), indexFile.readText())
        } catch (e: Exception) {
            log.warn("Coverage cache: cannot read cache-index.json: ${e.message}")
            null
        }
    }

    companion object {
        /** Test seam: replaces `~/.cache/coverage-plugin`. Production code never sets it. */
        @Volatile
        internal var cacheRootOverride: File? = null

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
