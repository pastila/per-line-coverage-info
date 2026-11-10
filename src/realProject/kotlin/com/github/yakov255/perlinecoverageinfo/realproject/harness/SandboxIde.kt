package com.github.yakov255.perlinecoverageinfo.realproject.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A real IntelliJ Platform process started without Gradle.
 *
 * The Gradle `runIde` task only exists inside a Gradle build: it prepares the sandbox, copies the
 * plugin and its dependencies into `<repo>/.intellijPlatform/sandbox/<plugin>/<version>/plugins`
 * and then starts the platform with a set of `-Didea.*`/`-Dcoverage.*` arguments. This class
 * repeats that part: it takes the prepared plugin directory (shared by every IDE instance, it is
 * read-only for the platform), gives every instance its own config/system/log directory under
 * `/tmp/rp-ide-<name>`, seeds the plugin settings and the first-run dialog suppression, and starts
 * `bin/idea.sh` with an instance-specific VM options file.
 *
 * Nothing here talks to GitLab and no credential is written: the plugin is pointed at a local
 * reflecting proxy with the token `dummy`.
 */
class SandboxIde private constructor(
    val name: String,
    private val process: Process,
    val prepared: Prepared,
) : AutoCloseable {

    val pid: Long get() = process.pid()

    val projectDir: File get() = prepared.projectDir
    val rootDir: File get() = prepared.rootDir
    val configDir: File get() = prepared.configDir
    val systemDir: File get() = prepared.systemDir
    val logDir: File get() = prepared.logDir
    val stateFile: File get() = prepared.stateFile
    val ideaLog: File get() = File(prepared.logDir, "idea.log")

    @Volatile
    private var stopped = false

    fun isAlive(): Boolean = !stopped && process.isAlive

    /**
     * Waits for the plugin log marker and the first bridge snapshot.
     *
     * The plugin is logged as loaded before the project opens, and the bridge writes its first
     * snapshot only after the project service is preloaded, so both are required.
     */
    fun awaitReady(timeoutMs: Long = DEFAULT_READY_TIMEOUT_MS): BridgeSnapshot {
        val snapshot = waitFor(timeoutMs, "IDE did not become ready (plugin loaded + bridge snapshot)") {
            pluginLoaded() && it != null
        }
        return snapshot!!
    }

    /**
     * Waits for the project to leave dumb (indexing) mode.
     *
     * The bridge exposes `dumb`; until that field exists in the running plugin the idea.log
     * marker "Indexing finished" is used instead.
     */
    fun awaitSmartMode(timeoutMs: Long = DEFAULT_SMART_MODE_TIMEOUT_MS): BridgeSnapshot? {
        return waitFor(timeoutMs, "IDE stayed in dumb mode") { snapshot ->
            snapshot?.dumb == false || indexingFinished()
        }
    }

    /** Current snapshot, or null before the bridge has written one (or while it is unreadable). */
    fun readBridge(): BridgeSnapshot? {
        val file = stateFile
        if (!file.isFile) return null
        return try {
            BridgeSnapshot.parse(file.readText())
        } catch (_: Throwable) {
            // The bridge writes atomically, but a file from an older run or a killed IDE can be
            // truncated; treat it as "no snapshot yet" rather than failing the scenario.
            null
        }
    }

    /**
     * Polls [predicate] until it is true. Throws [IdeTimeoutException] on timeout, with the tail
     * of idea.log attached so a failed start is diagnosable.
     */
    fun waitFor(
        timeoutMs: Long,
        message: String,
        pollMs: Long = DEFAULT_POLL_MS,
        predicate: (BridgeSnapshot?) -> Boolean,
    ): BridgeSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive()) {
                // A process that died cannot produce a later snapshot: fail fast instead of
                // waiting for the whole timeout.
                val snapshot = readBridge()
                if (predicate(snapshot)) return snapshot
                throw IdeTimeoutException("$message: IDE process ${pid} exited early.${logTail()}")
            }
            val snapshot = readBridge()
            if (predicate(snapshot)) return snapshot
            Thread.sleep(pollMs)
        }
        throw IdeTimeoutException("$message after ${timeoutMs} ms.${logTail()}")
    }

    /** True once the plugin registered its extensions. */
    fun pluginLoaded(): Boolean = ideaLog.isFile && LOADED_PATTERN.containsMatchIn(ideaLog.readText())

    private fun indexingFinished(): Boolean {
        val log = ideaLog
        if (!log.isFile) return false
        return INDEX_FINISHED_PATTERN.containsMatchIn(log.readText())
    }

    private fun logTail(lines: Int = 40): String {
        val log = ideaLog
        if (!log.isFile) return "\nidea.log not found at ${log.absolutePath}"
        val text = runCatching { log.readLines() }.getOrElse { return "" }
        return "\n--- idea.log (last $lines lines) ---\n" + text.takeLast(lines).joinToString("\n")
    }

    /**
     * Terminates only this IDE process: SIGTERM, wait, then SIGKILL.
     *
     * Child processes (fsnotifier, the shared covd daemon) are deliberately not touched: covd is
     * shared with every other IDE that uses the same cache root and exits on its own idle timeout.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        if (!process.isAlive) {
            process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)
            return
        }
        process.destroy()
        if (!process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
        }
    }

    override fun close() = stop()

    /** Everything [start] prepared; exposed for tests and for the report. */
    data class Prepared(
        val name: String,
        val rootDir: File,
        val projectDir: File,
        val ideaHome: File,
        val pluginsDir: File,
        val configDir: File,
        val systemDir: File,
        val logDir: File,
        val stateFile: File,
        val cacheRoot: File,
        val perfDir: File,
        val covdResponsesDir: File,
        val vmOptionsFile: File,
        val settingsFile: File,
        /** System properties handed to the IDE, in the order they are written to the VM options file. */
        val properties: Map<String, String>,
        val extraJvmArgs: List<String>,
    ) {
        fun launchCommand(): List<String> = listOf(
            File(ideaHome, "bin/idea.sh").absolutePath,
            projectDir.absolutePath,
        )

        fun launchEnvironment(): Map<String, String> = mapOf(
            // idea.sh merges this file with <IDE_HOME>/bin/idea64.vmoptions.
            "IDEA_VM_OPTIONS" to vmOptionsFile.absolutePath,
        )

        fun installedLicenseFiles(): List<String> = LICENSE_FILES.filter { File(configDir, it).isFile }

        fun isIdeProcess(procPid: Long): Boolean = ProcSampler.commandLineContains(procPid, logDir.absolutePath)
    }

    /** Knobs a scenario may override; every field has a usable default. */
    data class Options(
        /** Platform installation; located in the Gradle cache when null. */
        val ideaHome: File? = null,
        /** Prepared plugin directory (the shared sandbox `plugins` dir); found under the repo when null. */
        val pluginsDir: File? = null,
        /** Shared coverage cache root; defaults to `<rootDir>/cache`. */
        val cacheRoot: File? = null,
        /** Instance directory; defaults to `/tmp/rp-ide-<name>`. */
        val rootDir: File? = null,
        /** Recreate config/system/log on start. A restart scenario passes false to keep them. */
        val freshStart: Boolean = true,
        /** Git-root-relative files the bridge must classify (comma-joined in the property). */
        val uiTestFiles: List<String> = emptyList(),
        /** Extra `-D` properties; they win over the defaults and over [extraJvmArgs]. */
        val extraProps: Map<String, String> = emptyMap(),
        /** Extra raw JVM arguments appended to the VM options file. */
        val extraJvmArgs: List<String> = emptyList(),
        /** Heap cap; the raketa project needs more than the 2 GB default while indexing. */
        val maxHeap: String = "4g",
        /** Where license files are read from (their contents are never printed or copied into the repo). */
        val licenseSourceRoot: File = File(System.getProperty("user.home"), ".config/JetBrains"),
        /** `ideaSandboxJvmArgs` from ~/.gradle/gradle.properties (a local javaagent, --add-opens). */
        val useLocalSandboxJvmArgs: Boolean = true,
        val localGradleProperties: File = File(System.getProperty("user.home"), ".gradle/gradle.properties"),
        val extraEnvironment: Map<String, String> = emptyMap(),
    )

    companion object {
        const val DEFAULT_READY_TIMEOUT_MS = 240_000L
        const val DEFAULT_SMART_MODE_TIMEOUT_MS = 600_000L
        private const val DEFAULT_POLL_MS = 500L
        private const val STOP_GRACE_MS = 30_000L
        private const val KILL_GRACE_MS = 10_000L

        private val LOADED_PATTERN = Regex("Loaded custom plugins:.*per-line-coverage-info")
        private val INDEX_FINISHED_PATTERN = Regex("(?i)(indexing finished|full indexing .*finished)")

        internal val LICENSE_FILES = listOf("idea.key", "phpstorm.key", "plugin_PCWMP.license")

        /**
         * Starts one IDE instance. [projectDir] is the directory the IDE opens (a git worktree
         * root or one of its subdirectories, e.g. `api/hotels`).
         *
         * The plugin is configured with [proxyBaseUrl] as the GitLab domain and the token `dummy`;
         * the proxy is expected to strip credentials, so no real token is involved.
         */
        fun start(
            name: String,
            projectDir: File,
            proxyBaseUrl: String,
            options: Options = Options(),
        ): SandboxIde {
            val prepared = prepare(name, projectDir, proxyBaseUrl, options)
            return launch(prepared, options)
        }

        /** Layout, settings and VM options only: no process. */
        internal fun prepare(
            name: String,
            projectDir: File,
            proxyBaseUrl: String,
            options: Options = Options(),
            projectRoot: File = projectRoot(),
        ): Prepared {
            require(name.isNotBlank()) { "IDE name is required" }
            require(projectDir.isDirectory) { "project directory does not exist: $projectDir" }
            require(proxyBaseUrl.isNotBlank()) { "proxy base URL is required" }

            val ideaHome = options.ideaHome
                ?: findIdeaHome(projectRoot)
                ?: throw IllegalStateException(
                    "IntelliJ platform not found: pass Options.ideaHome or run ./gradlew prepareSandbox once",
                )
            val pluginsDir = options.pluginsDir
                ?: findSandboxPluginsDir(projectRoot)
                ?: throw IllegalStateException(
                    "prepared plugin directory not found: run ./gradlew prepareSandbox",
                )
            require(File(ideaHome, "bin/idea.sh").isFile) { "not a platform home: $ideaHome" }
            require(File(pluginsDir, "per-line-coverage-info").isDirectory) {
                "prepared plugins directory has no per-line-coverage-info: $pluginsDir"
            }

            val rootDir = options.rootDir ?: File("/tmp", "rp-ide-$name")
            val configDir = File(rootDir, "config")
            val systemDir = File(rootDir, "system")
            val logDir = File(rootDir, "log")
            val stateFile = File(rootDir, "ui-test-state.json")
            val cacheRoot = options.cacheRoot ?: File(rootDir, "cache")
            val perfDir = File(cacheRoot, "perf")
            val covdResponsesDir = File(cacheRoot, "covd-responses")

            if (options.freshStart) {
                for (dir in listOf(configDir, systemDir, logDir)) {
                    dir.deleteRecursively()
                }
                stateFile.delete()
            }
            for (dir in listOf(rootDir, configDir, systemDir, cacheRoot, perfDir, covdResponsesDir)) {
                dir.mkdirs()
            }
            File(configDir, "options").mkdirs()
            logDir.mkdirs()

            val settingsFile = File(configDir, "options/coverageApiSettings.xml")
            settingsFile.writeText(
                coverageApiSettingsXml(
                    gitlabDomain = proxyBaseUrl,
                    bearerToken = "dummy",
                    projectId = 335,
                    projectName = "raketa/raketa",
                    coverageBranch = "master",
                ),
            )
            writeDialogSuppression(configDir)
            installLicense(configDir, options.licenseSourceRoot)

            val properties = linkedMapOf<String, String>()
            properties["coverage.cacheRoot"] = cacheRoot.absolutePath
            properties["coverage.perf.dir"] = perfDir.absolutePath
            properties["coverage.covdResponses"] = covdResponsesDir.absolutePath
            properties["coverage.perf.log"] = "true"
            properties["coverage.uiTestBridge"] = "true"
            properties["coverage.uiTestState"] = stateFile.absolutePath
            if (options.uiTestFiles.isNotEmpty()) {
                properties["coverage.uiTestFiles"] = options.uiTestFiles.joinToString(",")
            }
            properties["idea.config.path"] = configDir.absolutePath
            properties["idea.system.path"] = systemDir.absolutePath
            properties["idea.log.path"] = logDir.absolutePath
            properties["idea.plugins.path"] = pluginsDir.absolutePath
            // The plugin was "installed" by prepareSandbox, not by the user: runIde passes this too.
            properties["plugin.path"] = pluginsDir.absolutePath
            properties["idea.trust.all.projects"] = "true"
            properties["idea.initially.ask.config"] = "never"
            properties["ide.show.tips.on.startup.default.value"] = "false"
            properties["jb.consents.confirmation.enabled"] = "false"
            properties["jb.privacy.policy.text"] = "<!--999.999-->"
            properties.putAll(options.extraProps)

            val localJvmArgs = if (options.useLocalSandboxJvmArgs) {
                localSandboxJvmArgs(options.localGradleProperties)
            } else {
                emptyList()
            }
            val extraJvmArgs = localJvmArgs + options.extraJvmArgs
            val vmOptionsFile = File(rootDir, "idea64.vmoptions")
            vmOptionsFile.writeText(renderVmOptions(options.maxHeap, properties, extraJvmArgs))

            return Prepared(
                name = name,
                rootDir = rootDir,
                projectDir = projectDir.absoluteFile,
                ideaHome = ideaHome.absoluteFile,
                pluginsDir = pluginsDir.absoluteFile,
                configDir = configDir,
                systemDir = systemDir,
                logDir = logDir,
                stateFile = stateFile,
                cacheRoot = cacheRoot,
                perfDir = perfDir,
                covdResponsesDir = covdResponsesDir,
                vmOptionsFile = vmOptionsFile,
                settingsFile = settingsFile,
                properties = properties,
                extraJvmArgs = extraJvmArgs,
            )
        }

        private fun launch(prepared: Prepared, options: Options): SandboxIde {
            val process = ProcessBuilder(prepared.launchCommand())
                .redirectErrorStream(true)
                .redirectOutput(File(prepared.rootDir, "ide-stdout.log"))
                .apply {
                    environment().putAll(prepared.launchEnvironment())
                    environment().putAll(options.extraEnvironment)
                }
                .start()
            return SandboxIde(prepared.name, process, prepared)
        }

        /** Gradle project directory; the realProjectTest task passes it as a system property. */
        internal fun projectRoot(): File =
            File(System.getProperty("realProject.projectDir") ?: System.getProperty("user.dir"))

        /**
         * Finds the extracted platform in the Gradle cache. The IntelliJ Platform Gradle plugin
         * unpacks `ideaIU-<platformVersion>` under `~/.gradle/caches/<gradle>/transforms/...`;
         * several Gradle versions may keep their own copy, any of them works.
         */
        internal fun findIdeaHome(
            projectRoot: File,
            cachesRoot: File = File(System.getProperty("user.home"), ".gradle/caches"),
            version: String? = null,
        ): File? {
            val wanted = version ?: readPlatformVersion(projectRoot)
            val transforms = cachesRoot.listFiles { file -> file.isDirectory }.orEmpty()
                .flatMap { gradleVersion -> gradleVersion.resolve("transforms").listFiles().orEmpty().toList() }
                .map { it.resolve("transformed") }
                .flatMap { it.listFiles { file -> file.isDirectory }.orEmpty().toList() }
            return transforms
                .filter { it.name.startsWith("ideaIU-") }
                .filter { wanted == null || it.name.removePrefix("ideaIU-").startsWith(wanted) }
                .filter { File(it, "bin/idea.sh").isFile && File(it, "lib/app.jar").isFile }
                .filter { productCode(it) == "IU" }
                .sortedBy { productVersion(it) }
                .lastOrNull()
        }

        /** Platform version from the project's gradle.properties, when present. */
        internal fun readPlatformVersion(projectRoot: File): String? {
            val file = File(projectRoot, "gradle.properties")
            if (!file.isFile) return null
            return file.readLines()
                .firstOrNull { it.trimStart().startsWith("platformVersion") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        /** `<repo>/.intellijPlatform/sandbox/<plugin>/<version>/plugins`, newest version first. */
        internal fun findSandboxPluginsDir(projectRoot: File): File? {
            val sandbox = File(projectRoot, ".intellijPlatform/sandbox/per-line-coverage-info")
            return sandbox.listFiles { file -> file.isDirectory }.orEmpty()
                .sortedByDescending { it.name }
                .map { File(it, "plugins") }
                .firstOrNull { File(it, "per-line-coverage-info").isDirectory }
        }

        private fun productCode(ideaHome: File): String? =
            productInfoValue(ideaHome, "productCode")

        private fun productVersion(ideaHome: File): String =
            productInfoValue(ideaHome, "version") ?: ideaHome.name

        private fun productInfoValue(ideaHome: File, key: String): String? {
            val file = File(ideaHome, "product-info.json")
            if (!file.isFile) return null
            return Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(file.readText())?.groupValues?.get(1)
        }

        /**
         * IntelliJ storage format for a `@State` service: a stable plain XML file. The values are
         * exactly the fields of `CoverageApiSettings.State`.
         */
        internal fun coverageApiSettingsXml(
            gitlabDomain: String,
            bearerToken: String,
            projectId: Long,
            projectName: String,
            coverageBranch: String,
            enabled: Boolean = true,
            tokenPrompted: Boolean = true,
        ): String = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine("<application>")
            appendLine("  <component name=\"CoverageApiSettings\">")
            appendLine(option("gitlabDomain", gitlabDomain))
            appendLine(option("bearerToken", bearerToken))
            appendLine(option("gitlabProjectId", projectId.toString()))
            appendLine(option("gitlabProjectName", projectName))
            appendLine(option("coverageBranch", coverageBranch))
            appendLine(option("enabled", enabled.toString()))
            appendLine(option("tokenPrompted", tokenPrompted.toString()))
            appendLine("  </component>")
            appendLine("</application>")
        }

        private fun option(name: String, value: String): String =
            "    <option name=\"${xmlEscape(name)}\" value=\"${xmlEscape(value)}\" />"

        internal fun xmlEscape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        /**
         * VM options file read by `bin/idea.sh` through `IDEA_VM_OPTIONS`. The script appends it
         * to the product's `idea64.vmoptions`, so a later `-Xmx` wins.
         */
        internal fun renderVmOptions(
            maxHeap: String,
            properties: Map<String, String>,
            extraJvmArgs: List<String>,
        ): String = buildString {
            appendLine("-Xmx$maxHeap")
            appendLine("-XX:-HeapDumpOnOutOfMemoryError")
            for (arg in extraJvmArgs) appendLine(arg)
            for ((key, value) in properties) {
                appendLine("-D$key=${quoteVmOptionValue(value)}")
            }
        }

        /** A value with whitespace must stay one JVM argument. */
        internal fun quoteVmOptionValue(value: String): String =
            if (value.any { it.isWhitespace() } && !value.startsWith("\"")) {
                "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            } else {
                value
            }

        /** Only the `ideaSandboxJvmArgs` line is read; the file is never printed. */
        internal fun localSandboxJvmArgs(file: File): List<String> {
            if (!file.isFile) return emptyList()
            val line = runCatching {
                file.useLines { lines ->
                    lines.firstOrNull { it.trimStart().startsWith("ideaSandboxJvmArgs") }
                }
            }.getOrNull() ?: return emptyList()
            return line.substringAfter('=', "")
                .trim()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
        }

        /**
         * Copies the local IDE license into [configDir] (contents are never printed or committed)
         * and removes the "disabled plugin" marker that keeps Ultimate/PHP off.
         */
        internal fun installLicense(configDir: File, sourceRoot: File): List<String> {
            val candidates = sourceRoot.listFiles { file -> file.isDirectory }.orEmpty()
                .filter { it.name.startsWith("IntelliJIdea") || it.name.startsWith("PhpStorm") }
                .filter { dir -> LICENSE_FILES.any { File(dir, it).isFile } }
                .sortedByDescending { it.name }
            val copied = mutableListOf<String>()
            for (fileName in LICENSE_FILES) {
                val source = candidates.map { File(it, fileName) }.firstOrNull { it.isFile } ?: continue
                source.copyTo(File(configDir, fileName), overwrite = true)
                copied += fileName
            }
            val disabled = File(configDir, "disabled_plugins.txt")
            if (disabled.isFile) {
                val remaining = disabled.readLines().filterNot { it.trim() == "com.intellij.modules.ultimate" }
                disabled.writeText(remaining.joinToString("\n"))
            }
            return copied
        }

        /**
         * First-run dialogs that would otherwise block an unattended start. The system properties
         * cover most of them; GeneralSettings covers the tip of the day, which has no property.
         */
        internal fun writeDialogSuppression(configDir: File) {
            val options = File(configDir, "options").apply { mkdirs() }
            File(options, "ide.general.xml").writeText(
                """
                <application>
                  <component name="GeneralSettings">
                    <option name="showTipsOnStartup" value="false" />
                    <option name="confirmExit" value="false" />
                  </component>
                </application>
                """.trimIndent() + "\n",
            )
        }

    }
}

/** Thrown when an IDE process never reached the expected state. */
class IdeTimeoutException(message: String) : RuntimeException(message)

/**
 * Snapshot written by `CoverageUiTestBridge` inside the IDE.
 *
 * The reader is deliberately tolerant: the bridge grows fields over time (and some scenarios run
 * against an older plugin build), so unknown fields are ignored and every field is optional.
 */
data class BridgeSnapshot(
    val state: String? = null,
    val project: String? = null,
    val commit: String? = null,
    val baselineCommit: String? = null,
    val stale: Boolean? = null,
    val headHash: String? = null,
    val gutterVisible: Boolean? = null,
    val files: Int? = null,
    val artifacts: Int? = null,
    /** New name of the bridge's error field; the older builds write `error`. */
    val lastError: String? = null,
    val loading: Boolean? = null,
    val coverageGeneration: Long? = null,
    /** True while the project is indexing. */
    val dumb: Boolean? = null,
    val covdInstanceId: String? = null,
    val covdPid: Long? = null,
    val lines: Map<String, Map<Int, String>> = emptyMap(),
    val updatedAt: Long? = null,
    /** The snapshot as written, for the report and for fields this class does not know yet. */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val isLoaded: Boolean get() = state == "LOADED"
    val isFailed: Boolean get() = state == "FAILED"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(text: String): BridgeSnapshot = parse(json.parseToJsonElement(text).jsonObject)

        fun parse(root: JsonObject): BridgeSnapshot {
            val lines = root["lines"]?.asObjectOrNull()?.mapValues { (_, value) ->
                value.asObjectOrNull().orEmpty()
                    .mapNotNull { (line, category) -> line.toIntOrNull()?.let { it to category.asStringOrNull().orEmpty() } }
                    .toMap()
            }.orEmpty()
            return BridgeSnapshot(
                state = root.string("state"),
                project = root.string("project"),
                commit = root.string("commit"),
                baselineCommit = root.string("baselineCommit"),
                stale = root.bool("stale"),
                headHash = root.string("headHash"),
                gutterVisible = root.bool("gutterVisible"),
                files = root.int("files"),
                artifacts = root.int("artifacts"),
                lastError = root.string("lastError") ?: root.string("error"),
                loading = root.bool("loading"),
                coverageGeneration = root.long("coverageGeneration"),
                dumb = root.bool("dumb"),
                covdInstanceId = root.string("covdInstanceId"),
                covdPid = root.long("covdPid"),
                lines = lines,
                updatedAt = root.long("updatedAt"),
                raw = root,
            )
        }

        private fun JsonObject.element(key: String): JsonElement? = this[key]

        private fun JsonObject.string(key: String): String? =
            (element(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

        private fun JsonObject.bool(key: String): Boolean? =
            (element(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

        private fun JsonObject.int(key: String): Int? =
            (element(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

        private fun JsonObject.long(key: String): Long? =
            (element(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

        private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

        private fun JsonElement.asStringOrNull(): String? =
            (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    }
}
