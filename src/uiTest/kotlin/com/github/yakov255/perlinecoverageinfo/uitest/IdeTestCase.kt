package com.github.yakov255.perlinecoverageinfo.uitest

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.intellij.remoterobot.RemoteRobot

/**
 * Base class for the out-of-process UI tests.
 *
 * The IDE is a separate process started by `runIdeForUiTests`; this JVM is only a RemoteRobot
 * client. [robotUrl] and [sandboxLogPath] come from system properties set by the Gradle `uiTest`
 * task, so the same test can be pointed at any running sandbox IDE.
 */
abstract class IdeTestCase {

    // The default OkHttp timeouts are too short while the IDE is busy indexing the large project
    // or waiting on GitLab: a slow answer must not look like a dead robot server.
    protected val robot: RemoteRobot by lazy {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .readTimeout(java.time.Duration.ofSeconds(120))
            .writeTimeout(java.time.Duration.ofSeconds(120))
            .build()
        RemoteRobot(robotUrl(), client)
    }
    protected val ide: IdeRobot by lazy { IdeRobot(robot) }

    /** `coverage.uiTestBridge=true` makes the plugin write its state snapshot. */
    protected fun bridgeSnapshot(): CoverageSnapshot? {
        val file = File(
            System.getProperty(
                "uiTest.stateFile",
                File(System.getProperty("user.home"), ".cache/coverage-plugin/ui-test-state.json").path,
            ),
        )
        if (!file.isFile) return null
        return runCatching { parseSnapshot(file.readText()) }.getOrNull()
    }

    /**
     * Waits for a FRESH, non-stale load. A `LOADED` state alone is not enough: offline-first shows
     * a cached artifact as `LOADED` straight away, and a snapshot from an earlier run may still be
     * on disk. So the snapshot must have been written after this test JVM started, must not be
     * stale, and the gutter must be visible (a hidden gutter suppresses loading).
     */
    protected fun awaitCoverageLoaded(timeout: Duration = Duration.ofSeconds(180)): CoverageSnapshot {
        // Explicit opt-in: accept the cached (stale) artifact. Needed when HEAD of the opened
        // project has no pipeline yet, so the daemon keeps answering not_ready.
        val allowStale = System.getenv("UI_TEST_ALLOW_STALE") == "1"
        return try {
            ide.await("coverage did not load within $timeout", timeout, Duration.ofSeconds(2)) {
                val snapshot = bridgeSnapshot() ?: return@await null
                if (snapshot.updatedAt < STARTED_AT_MILLIS) return@await null
                when {
                    snapshot.state == "FAILED" -> throw AssertionError("coverage load failed: ${snapshot.error}")
                    snapshot.state == "LOADED" && (allowStale || !snapshot.stale) && snapshot.gutterVisible -> snapshot
                    else -> null
                }
            }
        } catch (e: Throwable) {
            val last = bridgeSnapshot()
            throw AssertionError(
                "no fresh coverage load within $timeout. Last snapshot: state=${last?.state}, " +
                    "stale=${last?.stale}, coverage commit=${last?.commit?.take(8)}, " +
                    "HEAD=${last?.headHash?.take(8)}. A stale LOADED state is only the cached artifact: " +
                    "if the daemon answers not_ready (no_pipeline_yet) for HEAD, check out a commit with a " +
                    "pipeline, or set UI_TEST_ALLOW_STALE=1 to accept the cache knowingly.",
                e,
            )
        }
    }

    /** Saves the live Swing hierarchy with the token masked: the token sits in a plain text field. */
    fun saveHierarchy(target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(TestSecrets.mask(java.net.URL("${robotUrl()}/hierarchy").readText()))
        }
    }

    protected fun artifactDir(): File =
        File(System.getProperty("uiTest.artifacts", "build/uiTest-artifacts")).apply { mkdirs() }

    /** Saves the whole IDE screenshot plus a text dump next to it; safe to call on failure. */
    fun captureDiagnostics(name: String, extra: () -> String = { "" }) {
        val dir = artifactDir()
        runCatching {
            ImageIO.write(robot.getScreenshot(), "png", File(dir, "$name-ide.png"))
        }
        // The live Swing hierarchy is what locators have to be written against.
        saveHierarchy(File(dir, "$name-hierarchy.html"))
        runCatching {
            File(dir, "$name-state.txt").writeText(
                buildString {
                    appendLine("bridge: ${bridgeSnapshot()}")
                    appendLine(extra())
                    appendLine("--- idea.log tail ---")
                    appendLine(ideLogTail())
                },
            )
        }
    }

    protected fun ideLogTail(lines: Int = 200): String {
        val log = File(sandboxLogPath())
        if (!log.isFile) return "no idea.log at ${log.absolutePath}"
        return log.readLines().takeLast(lines).joinToString("\n")
    }

    /** Copies idea.log into the artifact directory for a failing run. */
    fun archiveIdeLog(name: String) {
        val log = File(sandboxLogPath())
        if (!log.isFile) return
        runCatching {
            Files.copy(log.toPath(), File(artifactDir(), "$name-idea.log").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun parseSnapshot(text: String): CoverageSnapshot {
        val root = Json.parseToJsonElement(text).jsonObject
        return CoverageSnapshot(
            state = root.string("state") ?: "UNKNOWN",
            commit = root.string("commit"),
            baselineCommit = root.string("baselineCommit"),
            files = root["files"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            artifacts = root["artifacts"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            error = root.string("error"),
            lines = parseLines(root["lines"]?.jsonObject),
            stale = root["stale"]?.jsonPrimitive?.content == "true",
            gutterVisible = root["gutterVisible"]?.jsonPrimitive?.content != "false",
            headHash = root.string("headHash"),
            updatedAt = root["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
        )
    }

    private fun parseLines(lines: JsonObject?): Map<String, Map<Int, String>> {
        if (lines == null) return emptyMap()
        return lines.entries.associate { (path, value) ->
            val perLine = value.jsonObject.entries.associate { (line, category) ->
                line.toInt() to category.jsonPrimitive.content
            }
            path to perLine
        }
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.content?.takeIf { it != "null" }

    companion object {
        /** Fixed when the test JVM starts; anything older than this is a leftover of another run. */
        val STARTED_AT_MILLIS: Long = System.currentTimeMillis()

        fun robotUrl(): String = System.getProperty("robot.server.url", "http://127.0.0.1:8082")

        fun sandboxLogPath(): String =
            System.getProperty("uiTest.sandboxLog", "build/uiTestSandbox/idea.log")
    }
}
