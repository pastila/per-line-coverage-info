package com.github.yakov255.perlinecoverageinfo.realproject.scenarios

import com.github.yakov255.perlinecoverageinfo.realproject.harness.BridgeSnapshot
import com.github.yakov255.perlinecoverageinfo.realproject.harness.ProcSampler
import com.github.yakov255.perlinecoverageinfo.realproject.harness.RunContext
import com.github.yakov255.perlinecoverageinfo.realproject.harness.SandboxIde
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.CoveragePlan
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.InventoryPlan
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.PlanEntry
import com.github.yakov255.perlinecoverageinfo.realproject.inventory.RepoPreparer
import com.github.yakov255.perlinecoverageinfo.realproject.proxy.ReflectingProxy
import com.github.yakov255.perlinecoverageinfo.realproject.report.ReportGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Shared scaffolding of the real-project scenarios (see `docs/real-project-testing.md`).
 *
 * One scenario method gets: a fresh run directory (`build/real-project/<run-id>/`), a
 * [ReflectingProxy] in front of `gitlab.raketa.in` (credentials are stripped; the IDE is
 * configured with the token `dummy`), a [ProcSampler], and the dedicated raketa worktree from
 * `plan.json`. IDEs are started with [startIde] and share `<run>/cache` as the covd cache root,
 * which is also where the report looks for it.
 *
 * Scenarios are opt-in: without `-DrealProject.ide=true` (or `REAL_PROJECT_IDE=true`) every
 * scenario is skipped, so a bare `./gradlew realProjectTest` only runs the unit tests of the
 * suite. `-DrealProject.scenario=R1,R3` narrows the selection.
 *
 * At the end ([finish]) the IDEs are stopped (only the processes started here), the perf logs and
 * the saved covd answers are copied into the run directory, `report.md` is generated and the run
 * directory is scanned for credentials: any finding fails the scenario.
 */
abstract class ScenarioBase {

    protected lateinit var ctx: RunContext
    protected lateinit var proxy: ReflectingProxy
    protected lateinit var sampler: ProcSampler
    protected lateinit var plan: CoveragePlan
    protected lateinit var preparer: RepoPreparer

    /** The covd cache root shared by every IDE of the scenario. */
    protected val cacheRoot: File get() = File(ctx.dir, "cache")

    /** Directory the IDE opens for the raketa component. */
    protected val worktree: File get() = preparer.worktree.toFile()

    private val ides = mutableListOf<SandboxIde>()
    private val pollers = mutableListOf<Thread>()
    private val covdRegistered = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var polling = false
    private var scenarioName = "scenario"
    private var finished = false

    /** Scenario ids selected with `-DrealProject.scenario` (null = all). */
    private val selected: Set<String>? =
        (System.getProperty("realProject.scenario") ?: System.getenv("REAL_PROJECT_SCENARIO"))
            ?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet()

    @Before
    fun setUpBase() {
        val optIn = (System.getProperty("realProject.ide") ?: System.getenv("REAL_PROJECT_IDE")) == "true"
        assumeTrue("real-project scenarios are opt-in: set -DrealProject.ide=true", optIn)
        val planPath = InventoryPlan.defaultPath()
        assumeTrue("no plan.json at $planPath: run scripts/real-project-prepare.sh first", planPath.toFile().isFile)
        plan = Json { ignoreUnknownKeys = true }.decodeFromString(CoveragePlan.serializer(), planPath.toFile().readText())
        preparer = RepoPreparer(worktree = java.nio.file.Path.of(plan.worktree))
        assumeTrue("worktree ${plan.worktree} is missing: run scripts/real-project-prepare.sh", preparer.worktree.toFile().isDirectory)
        assumeTrue("the plan has no baseline", plan.baseline != null)
    }

    /** Skips the scenario unless [id] (for example `R1`) is selected. Call it first in each test. */
    protected fun scenario(id: String, label: String = id.lowercase()) {
        assumeTrue("scenario $id not selected (-DrealProject.scenario)", selected == null || id.uppercase() in selected)
        scenarioName = id
        ctx = RunContext.create(label)
        ctx.writeMachineInfo(mapOf("scenario" to id))
        proxy = ReflectingProxy(journal = File(ctx.dir, "proxy.jsonl"))
        cacheRoot.mkdirs()
        sampler = ProcSampler(ctx.samplesFile, cacheRoot)
        sampler.start()
        polling = true
        event("setup", "run ${ctx.runId}, proxy ${proxy.baseUrl()}, worktree $worktree")
    }

    @After
    fun tearDownBase() {
        if (::ctx.isInitialized && !finished) {
            runCatching { finish() }
        }
    }

    // --- IDE control ---------------------------------------------------------------------

    /**
     * Starts an IDE on [projectDir] (the worktree root by default) and records its snapshots and
     * memory. Waits until the plugin is loaded and, unless [waitSmart] is false, indexing is done.
     */
    protected fun startIde(
        name: String,
        projectDir: File = worktree,
        extraProps: Map<String, String> = emptyMap(),
        uiTestFiles: List<String> = emptyList(),
        waitSmart: Boolean = true,
    ): SandboxIde {
        val options = SandboxIde.Options(
            cacheRoot = cacheRoot,
            extraProps = extraProps,
            uiTestFiles = uiTestFiles,
        )
        val ide = SandboxIde.start(name, projectDir, proxy.baseUrl(), options)
        ides += ide
        sampler.add(name, ide.pid)
        startPoller(ide)
        event("ide-start", "$name pid=${ide.pid} project=$projectDir")
        ide.awaitReady()
        if (waitSmart) ide.awaitSmartMode()
        event("ide-ready", name)
        return ide
    }

    protected fun stopIde(ide: SandboxIde) {
        event("ide-stop", ide.name)
        ide.stop()
        sampler.remove(ide.name)
    }

    private fun startPoller(ide: SandboxIde) {
        val thread = Thread({
            while (polling) {
                try {
                    ide.readBridge()?.let { snapshot ->
                        ctx.snapshot(snapshot)
                        snapshot.covdPid?.takeIf { covdRegistered.add(it) }?.let { sampler.add("covd", it) }
                    }
                } catch (_: Throwable) {
                    // A restarting or killed IDE writes half-files; ignore.
                }
                try {
                    Thread.sleep(RunContext.SNAPSHOT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "rp-poll-${ide.name}").apply { isDaemon = true; start() }
        pollers += thread
    }

    // --- git ----------------------------------------------------------------------------

    /**
     * Moves the worktree to [sha] the way a user switches branches. The IDE writes `.idea/` into
     * the worktree, so untracked files are expected; only tracked changes block the switch, and
     * nothing is ever cleaned.
     */
    protected fun switchTo(sha: String) {
        check(preparer.hasCommit(sha)) { "commit $sha is not in the local repository" }
        val tracked = runGit("status", "--porcelain", "--untracked-files=no")
        check(tracked.isBlank()) { "worktree has tracked changes, refusing to switch: ${tracked.lines().first()}" }
        runGit("checkout", "--detach", "-q", sha)
        event("checkout", sha.take(8))
    }

    protected fun head(): String = runGit("rev-parse", "HEAD").trim()

    private fun runGit(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", worktree.absolutePath) + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(300, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed: ${out.trim()}" }
        return out
    }

    // --- observation ---------------------------------------------------------------------

    protected fun event(phase: String, msg: String = "", data: JsonObject = JsonObject(emptyMap())) =
        ctx.event(scenarioName, phase, msg, data)

    protected fun eventData(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v?.toString()) })

    /** Waits until [ide] shows [sha] as its loaded, fresh coverage and no load is running. */
    protected fun awaitLoaded(ide: SandboxIde, sha: String, timeoutMs: Long = 600_000, allowStale: Boolean = false): BridgeSnapshot =
        ide.waitFor(timeoutMs, "IDE ${ide.name} did not load coverage of ${sha.take(8)}") { s ->
            s != null && s.commit == sha && s.loading != true && s.isLoaded && (allowStale || s.stale != true)
        }!!

    /** Pause that is explicit in the journal of the scenario. */
    protected fun pause(ms: Long, why: String) {
        event("pause", "$why ($ms ms)")
        Thread.sleep(ms)
    }

    protected fun baseline(): PlanEntry? = null

    /** Branches of the tour: spreads classes, only entries whose commit is present locally. */
    protected fun tour(count: Int): List<PlanEntry> = plan.branches.filter { it.localSha }.take(count)

    // --- finishing -----------------------------------------------------------------------

    /** Stops everything, collects the evidence, writes the report and fails on leaked credentials. */
    protected fun finish(): File {
        if (finished) return File(ctx.dir, "report.md")
        finished = true
        event("finish")
        polling = false
        pollers.forEach { it.interrupt() }
        ides.forEach { runCatching { it.stop() } }
        runCatching { sampler.stop() }
        ctx.stopSnapshotPolling()
        ctx.copyPerfLogs(File(cacheRoot, "perf"))
        ctx.copyCovdResponses(File(cacheRoot, "covd-responses"))
        runCatching { proxy.close() }
        val output = ReportGenerator(ctx.dir).generate()
        println("real-project report: ${File(ctx.dir, "report.md")}")
        assertTrue("credentials found in the run directory (${output.secretFindings.size} findings, see summary.json)", output.secretFindings.isEmpty())
        return File(ctx.dir, "report.md")
    }
}
