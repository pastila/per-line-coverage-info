package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Test-only observability bridge for an out-of-process UI test (RemoteRobot sees Swing only).
 *
 * Active only when the JVM system property `coverage.uiTestBridge` is `true`; otherwise the
 * service is inert — no listeners, no timers, no file writes.
 *
 * While active it writes a JSON snapshot of the real plugin state to the file named by
 * `coverage.uiTestState` (default `~/.cache/coverage-plugin/ui-test-state.json`), atomically, on every coverage change and on a
 * 1-second tick (so an editor opened later still shows up). Files listed in the system property
 * `coverage.uiTestFiles` (comma-separated git-root-relative paths) get their per-line gutter
 * classification in the snapshot.
 */
@Service(Service.Level.PROJECT)
class CoverageUiTestBridge(private val project: Project) : Disposable {

    private val log = CoverageLog.get(CoverageUiTestBridge::class.java)

    private val enabled = isEnabledBySystemProperty()

    /** Refresh tick; null while the bridge is disabled. */
    private val alarm = if (enabled) Alarm(Alarm.ThreadToUse.POOLED_THREAD, this) else null

    init {
        if (enabled) {
            project.messageBus.connect(this).subscribe(
                CoverageDataService.COVERAGE_CHANGED_TOPIC,
                CoverageChangeListener { refreshAsync() },
            )
            alarm?.addRequest({ tick() }, TICK_MS)
            refreshAsync()
            log.info("UI test bridge enabled — snapshot: ${stateFile().absolutePath}")
        }
    }

    override fun dispose() {
        alarm?.cancelAllRequests()
    }

    private fun tick() {
        if (!enabled || project.isDisposed) return
        refresh()
        if (project.isDisposed) return
        alarm?.addRequest({ tick() }, TICK_MS)
    }

    /** Writes the snapshot on a pooled thread: the caller may be on the EDT. */
    private fun refreshAsync() {
        if (!enabled || project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread { refresh() }
    }

    private fun refresh() {
        if (!enabled || project.isDisposed) return
        val snapshot = try {
            buildSnapshot()
        } catch (t: Throwable) {
            log.warn("UI test bridge: cannot build snapshot: ${t.message}", t)
            failureSnapshot(t)
        }
        try {
            writeSnapshot(snapshot)
        } catch (t: Throwable) {
            log.warn("UI test bridge: cannot write snapshot: ${t.message}", t)
        }
    }

    private fun buildSnapshot(): JsonObject {
        val dataService = CoverageDataService.getInstance(project)
        val loadService = CoverageLoadService.getInstance(project)
        val lastError = loadService.lastError

        val state = when {
            dataService.hasData() -> "LOADED"
            loadService.isLoadInProgress() -> "LOADING"
            lastError != null -> "FAILED"
            else -> "NOT_CONFIGURED"
        }

        val artifacts = try {
            CoverageCacheService.getInstance(project).listArtifacts().size
        } catch (e: Exception) {
            log.warn("UI test bridge: cannot list artifacts: ${e.message}")
            0
        }

        return buildJsonObject {
            put("state", state)
            put("project", project.name)
            put("commit", dataService.coverageCommitHash.toJson())
            put("baselineCommit", dataService.baselineCommitHash.toJson())
            // A cached artifact shown by offline-first is LOADED too; the test must tell it
            // from a fresh load, and a hidden gutter suppresses all loading.
            put("stale", dataService.isStale)
            put("headHash", dataService.headHash.toJson())
            put("gutterVisible", CoverageGutterVisibilityService.getInstance(project).visible)
            put("files", dataService.allFiles().size)
            put("artifacts", artifacts)
            put("error", lastError.toJson())
            put("lastError", lastError.toJson())
            put("loading", loadService.isLoadInProgress())
            put("coverageGeneration", dataService.coverageGeneration)
            put("dumb", isDumb())
            put("covdInstanceId", CovdClient.currentConnection()?.instanceId.toJson())
            put("covdPid", CovdClient.daemonPid()?.let { JsonPrimitive(it) } ?: JsonNull)
            put("lines", classifyLines(dataService, requestedFiles()))
            put("updatedAt", System.currentTimeMillis())
        }
    }

    /** Indexing state: a scenario that measures memory must wait until it is over. */
    private fun isDumb(): Boolean = try {
        DumbService.getInstance(project).isDumb
    } catch (e: Exception) {
        log.info("UI test bridge: DumbService unavailable: ${e.message}")
        false
    }

    /** Only the fallback shape, so a bridge bug can never break the IDE. */
    private fun failureSnapshot(t: Throwable): JsonObject = buildJsonObject {
        put("state", "FAILED")
        put("commit", JsonNull)
        put("baselineCommit", JsonNull)
        put("files", 0)
        put("artifacts", 0)
        put("error", t.message ?: t.javaClass.name)
        put("lastError", t.message ?: t.javaClass.name)
        put("loading", false)
        put("coverageGeneration", 0)
        put("dumb", false)
        put("covdInstanceId", JsonNull)
        put("covdPid", JsonNull)
        put("lines", buildJsonObject {})
        put("updatedAt", System.currentTimeMillis())
    }

    /**
     * Per-line classification for the requested files, using the same classifier
     * ([CoverageHighlighter.categorizeLine]) as the gutter renderer. Only lines present in the
     * primary map are emitted.
     *
     * The line numbers are those of the coverage commit: [LineMappingService] (old → current
     * document line) is deliberately NOT applied, so they match the editor only while the file is
     * unchanged since that commit. The UI test checks that precondition itself.
     */
    private fun classifyLines(dataService: CoverageDataService, paths: List<String>): JsonObject = buildJsonObject {
        val hasBaseline = dataService.hasBaseline()
        for (raw in paths) {
            val path = raw.replace('\\', '/')
            val primary = CoveragePathResolver.resolve(dataService, listOf(path))
            if (primary == null) {
                // Listed by the test but absent from the coverage data: empty object, no lines.
                putJsonObject(path) {}
                continue
            }
            val baseline = if (hasBaseline) {
                CoveragePathResolver.resolveBaseline(dataService, listOf(path))
            } else {
                null
            }
            putJsonObject(path) {
                for ((line, tests) in primary) {
                    val category = CoverageHighlighter.categorizeLine(
                        tests,
                        baseline?.get(line) ?: emptyList(),
                        hasBaseline,
                    )
                    put(line.toString(), category.name)
                }
            }
        }
    }

    private fun writeSnapshot(snapshot: JsonObject) {
        val file = stateFile()
        val dir = file.parentFile
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IllegalStateException("cannot create directory ${dir.absolutePath}")
        }
        val tmp = File.createTempFile("ui-test-state", ".tmp", dir)
        try {
            tmp.writeText(snapshot.toString())
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * `-Dcoverage.uiTestState=<file>` when the harness gives one (per-run, outside the user's
     * cache); otherwise `~/.cache/coverage-plugin/ui-test-state.json`.
     */
    private fun stateFile(): File =
        System.getProperty(STATE_PROPERTY)?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(File(System.getProperty("user.home"), ".cache/coverage-plugin"), "ui-test-state.json")

    /** Files the test wants classified; empty when the property is absent. */
    private fun requestedFiles(): List<String> =
        System.getProperty(FILES_PROPERTY, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun String?.toJson(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)

    companion object {
        private const val TICK_MS = 1_000L
        private const val ENABLE_PROPERTY = "coverage.uiTestBridge"
        private const val FILES_PROPERTY = "coverage.uiTestFiles"
        private const val STATE_PROPERTY = "coverage.uiTestState"

        fun getInstance(project: Project): CoverageUiTestBridge = project.service()

        /** True only in an instrumented UI-test run. */
        fun isEnabledBySystemProperty(): Boolean = System.getProperty(ENABLE_PROPERTY) == "true"
    }
}
