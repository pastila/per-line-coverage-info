package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.harness.BridgeSnapshot
import com.github.yakov255.perlinecoverageinfo.realproject.harness.SandboxIde
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Evidence and contract helpers of the failure scenarios F1/F2 (see
 * "Failure handling contract (F1, F2)" in `docs/real-project-testing.md`).
 *
 * Two rules shape this file:
 *
 * * The contract fields (`retryAttempt`, `retryMax`, `blocked`, `notifications`, `lastCommand`)
 *   are read from [BridgeSnapshot.raw]. [BridgeSnapshot] has a fixed set of typed properties, and
 *   the failure scenarios must also run against a plugin build that does not have the new fields
 *   yet: a missing field is reported as "bridge has no field <name> yet", never as a crash.
 * * Passive facts (ensure answers, proxy requests, the loaded commit) are always recorded, so a
 *   red run against the old plugin still tells what actually happened.
 *
 * The parser of `covd-responses` is local to this wave (the other waves keep their own copies);
 * the file layout is the one in "Saved covd answers".
 */
internal object FailureSupport {

    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val COMMANDS_FILE = "ui-test-commands.txt"

    /** Instance directory the harness gives an IDE named [ideName] (`SandboxIde.Options.rootDir` default). */
    fun ideRoot(ideName: String): File = File("/tmp", "rp-ide-$ideName")

    /**
     * System properties a failure scenario passes to [SandboxIde]: the command file the bridge
     * polls, and the shortened retry policy (5/15/45 s would make a run take minutes).
     */
    fun ideProps(ideName: String, retryDelaysMs: String? = null): Map<String, String> = buildMap {
        put("coverage.uiTestCommands", File(ideRoot(ideName), COMMANDS_FILE).absolutePath)
        retryDelaysMs?.let { put("coverage.retry.delaysMs", it) }
    }

    // ---- commands file ---------------------------------------------------------------

    /**
     * Appends one command line to the bridge's command file. The IDE polls the file and runs
     * every new line once (`reload`, `settings.token=<value>`, `settings.apply`).
     */
    fun sendCommand(ideName: String, line: String): File {
        val file = File(ideRoot(ideName), COMMANDS_FILE)
        file.parentFile?.mkdirs()
        file.appendText(line.trim() + "\n")
        return file
    }

    // ---- saved covd answers ----------------------------------------------------------

    /** One `ensure`/`import` answer as `CovdResponseRecorder` wrote it. */
    data class Answer(
        val file: String,
        val seq: Long,
        val atMs: Long,
        val kind: String,
        val httpStatus: Int,
        val state: String,
        val code: String?,
        val cause: String?,
        val retryable: Boolean,
        val retryAfterMs: Long?,
        val commit: String?,
        val cached: Boolean,
        val stale: Boolean,
        val elapsedMs: Long,
        /** Every job id whose artifact the answer's trace names (duplicates prove a retry). */
        val artifactJobIds: List<Int>,
        val errors: List<String>,
        val gitlabCalls: Int,
    ) {
        val shortCommit: String get() = commit?.take(8) ?: "--------"

        val isError: Boolean get() = state == "error"

        /** The daemon classified the failure as retryable (or the answer never arrived). */
        val retryableFailure: Boolean
            get() = isError && (retryable || code == "CONNECTION" || code == "GITLAB_API")

        /** Compact form for a failure message. */
        fun describe(): String = buildString {
            append("#").append(seq).append(" ").append(state)
            code?.let { append("/").append(it) }
            if (retryable) append("/retryable")
            if (state == "ready" || commit != null) append("@").append(shortCommit)
            if (cached) append("/cached")
        }
    }

    /** Every saved answer of [cacheRoot], oldest first. */
    fun answers(cacheRoot: File): List<Answer> {
        val dir = File(cacheRoot, "covd-responses")
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            .mapNotNull { file -> runCatching { parseAnswer(file) }.getOrNull() }
            .sortedWith(compareBy({ it.atMs }, { it.seq }))
    }

    fun ensures(cacheRoot: File): List<Answer> = answers(cacheRoot).filter { it.kind == "ensure" }

    fun describe(answers: List<Answer>): String =
        if (answers.isEmpty()) "none" else answers.joinToString(", ") { it.describe() }

    /**
     * Polls the saved answers until [predicate] holds or [timeoutMs] passes. Returns the last
     * snapshot either way: a scenario records facts first and asserts afterwards.
     */
    fun awaitAnswers(
        cacheRoot: File,
        timeoutMs: Long,
        pollMs: Long = 250L,
        predicate: (List<Answer>) -> Boolean,
    ): List<Answer> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val all = answers(cacheRoot)
            if (predicate(all)) return all
            if (System.currentTimeMillis() >= deadline) return all
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return all
            }
        }
    }

    /** Waits until at least [count] `ensure` answers exist (success or failure). */
    fun awaitEnsureCount(cacheRoot: File, count: Int, timeoutMs: Long): List<Answer> =
        awaitAnswers(cacheRoot, timeoutMs) { all -> all.count { it.kind == "ensure" } >= count }

    private fun parseAnswer(file: File): Answer? {
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val response = root["response"] as? JsonObject ?: return null
        val request = root["request"] as? JsonObject
        val trace = response["trace"] as? JsonObject
        val gitlab = trace?.get("gitlab") as? JsonArray
        val jobIds = gitlab.orEmpty().mapNotNull { element ->
            val path = ((element as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            Regex("""/jobs/(\d+)/artifacts""").find(path)?.groupValues?.get(1)?.toIntOrNull()
        }
        val errors = (trace?.get("errors") as? JsonArray).orEmpty().mapNotNull { element ->
            val error = element as? JsonObject ?: return@mapNotNull null
            listOfNotNull(error.string("scope"), error.string("code"), error.string("message"))
                .joinToString(":").ifEmpty { null }
        }
        return Answer(
            file = file.name,
            seq = root.long("seq") ?: 0L,
            atMs = root.long("atMs") ?: file.lastModified(),
            kind = root.string("kind") ?: request?.string("kind") ?: "ensure",
            httpStatus = (root.long("httpStatus") ?: 0L).toInt(),
            state = response.string("state") ?: "unknown",
            code = response.string("code"),
            cause = response.string("cause"),
            retryable = response.bool("retryable") ?: false,
            retryAfterMs = response.long("retryAfterMs"),
            commit = response.string("commit"),
            cached = response.bool("cached") ?: false,
            stale = response.bool("stale") ?: false,
            elapsedMs = root.long("elapsedMs") ?: 0L,
            artifactJobIds = jobIds,
            errors = errors,
            gitlabCalls = gitlab?.size ?: 0,
        )
    }

    // ---- proxy journal ---------------------------------------------------------------

    /** One row of `proxy.jsonl`. */
    data class JournalRow(
        val t: Long,
        val seq: Int,
        val path: String,
        val status: Int,
        val bytesDown: Long,
        val fault: String?,
        val artifactJobId: Int?,
    )

    fun journal(runDir: File): List<JournalRow> {
        val file = File(runDir, "proxy.jsonl")
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val root = json.parseToJsonElement(line).jsonObject
                JournalRow(
                    t = root.long("t") ?: 0L,
                    seq = (root.long("seq") ?: 0L).toInt(),
                    path = root.string("path").orEmpty(),
                    status = (root.long("status") ?: 0L).toInt(),
                    bytesDown = root.long("bytesDown") ?: 0L,
                    fault = root.string("fault"),
                    artifactJobId = root.long("artifactJobId")?.toInt(),
                )
            }.getOrNull()
        }.sortedWith(compareBy({ it.t }, { it.seq }))
    }

    /** Requests that started inside `[fromMs, toMs]` (both inclusive). */
    fun requestsBetween(runDir: File, fromMs: Long, toMs: Long): List<JournalRow> =
        journal(runDir).filter { it.t in fromMs..toMs }

    fun requestCountBetween(runDir: File, fromMs: Long, toMs: Long): Int =
        requestsBetween(runDir, fromMs, toMs).size

    /** The first proxied request that started strictly after [afterMs]; null when there is none. */
    fun firstRequestAfter(runDir: File, afterMs: Long): JournalRow? =
        journal(runDir).firstOrNull { it.t > afterMs }

    // ---- bridge snapshot contract fields ---------------------------------------------

    /** True when the snapshot JSON actually carries [key] (a `null` value still counts). */
    fun fieldPresent(snapshot: BridgeSnapshot, key: String): Boolean = snapshot.raw.containsKey(key)

    fun stringField(snapshot: BridgeSnapshot, key: String): String? =
        (snapshot.raw[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    fun longField(snapshot: BridgeSnapshot, key: String): Long? =
        (snapshot.raw[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    fun boolField(snapshot: BridgeSnapshot, key: String): Boolean? =
        (snapshot.raw[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    /** One notification as the bridge lists it (last 20, newest unknown order). */
    data class Balloon(
        val title: String,
        val content: String,
        val type: String?,
        val actions: List<String>,
        val atMs: Long?,
    ) {
        /** Action labels are matched without a trailing ellipsis/punctuation and case-insensitively. */
        fun hasAction(prefix: String): Boolean {
            val wanted = normalize(prefix)
            return actions.any { normalize(it).startsWith(wanted) }
        }

        fun describe(): String =
            "'$title' type=${type ?: "?"} actions=$actions"
    }

    /** The `notifications` array, or null when the bridge does not expose the field yet. */
    fun notifications(snapshot: BridgeSnapshot): List<Balloon>? {
        val element = snapshot.raw["notifications"] ?: return null
        if (element is JsonNull) return emptyList()
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val actions = (obj["actions"] as? JsonArray).orEmpty().mapNotNull { action ->
                when (action) {
                    is JsonPrimitive -> action.contentOrNull
                    is JsonObject -> action.string("label") ?: action.string("text")
                    else -> null
                }
            }
            Balloon(
                title = obj.string("title").orEmpty(),
                content = obj.string("content").orEmpty(),
                type = obj.string("type"),
                actions = actions,
                atMs = obj.long("atMs"),
            )
        }
    }

    fun describeBalloons(balloons: List<Balloon>?): String = when {
        balloons == null -> "no notifications field"
        balloons.isEmpty() -> "none"
        else -> balloons.joinToString("; ") { it.describe() }
    }

    /** Waits for a bridge snapshot matching [predicate]; null on timeout (never throws). */
    fun awaitBridge(
        ide: SandboxIde,
        timeoutMs: Long,
        pollMs: Long = 250L,
        predicate: (BridgeSnapshot) -> Boolean,
    ): BridgeSnapshot? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val snapshot = ide.readBridge()
            if (snapshot != null && predicate(snapshot)) return snapshot
            if (System.currentTimeMillis() >= deadline) {
                return ide.readBridge()?.takeIf(predicate)
            }
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    /** The balloon of the retry policy ("Retry now" / "Show log"), if one is listed. */
    fun retryBalloon(snapshot: BridgeSnapshot): Balloon? = notifications(snapshot).orEmpty()
        .filter { it.hasAction("Retry now") || it.hasAction("Show log") }
        .maxByOrNull { it.atMs ?: 0L }

    /** The "GitLab token expired or invalid" balloon, if one is listed. */
    fun tokenBalloon(snapshot: BridgeSnapshot): Balloon? = notifications(snapshot).orEmpty()
        .filter {
            it.title.contains("token expired", ignoreCase = true) ||
                it.content.contains("token expired", ignoreCase = true) ||
                it.hasAction("Update token")
        }
        .maxByOrNull { it.atMs ?: 0L }

    private fun normalize(label: String): String =
        label.trim().trimEnd('…', '.', ' ', '!').lowercase()

    // ---- contract checks -------------------------------------------------------------

    /**
     * Collects contract violations instead of failing on the first one. The old plugin has none
     * of the new bridge fields, and a baseline run is only useful when it lists every gap and
     * every observed fact at once.
     */
    class Checks(private val scenario: String) {
        private val problems = mutableListOf<String>()

        fun check(name: String, ok: Boolean, message: String) {
            if (!ok) problems += "[$name] $message"
        }

        /** Records "bridge has no field X yet" and returns whether the field is readable. */
        fun hasField(snapshot: BridgeSnapshot?, key: String): Boolean {
            if (snapshot == null) {
                problems += "bridge has no field $key yet: no snapshot was written"
                return false
            }
            if (!fieldPresent(snapshot, key)) {
                problems += "bridge has no field $key yet (contract: CoverageUiTestBridge must expose it)"
                return false
            }
            return true
        }

        fun fail(message: String) {
            problems += message
        }

        val violations: List<String> get() = problems.toList()

        fun assertAll() {
            if (problems.isEmpty()) return
            throw AssertionError(
                "$scenario contract checks failed (${problems.size}):\n" +
                    problems.joinToString("\n") { " - $it" },
            )
        }
    }

    // ---- small JSON helpers ----------------------------------------------------------

    fun JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull
}
