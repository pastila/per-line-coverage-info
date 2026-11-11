package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point of the inventory prepare step (see `scripts/real-project-prepare.sh`).
 *
 * Reads the anonymous GitLab API, fetches missing commits into the shared repository, creates the
 * detached worktree on the baseline commit and writes `plan.json`. It never prints or stores a
 * credential: the inventory API is anonymous and only `git fetch` talks to the remote.
 */
object InventoryCli {
    private const val EXIT_NO_COVERAGE = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val options = CliOptions.parse(args)
        if (options.help) {
            printUsage()
            return
        }
        val startedAt = System.nanoTime()
        val planPath = options.planPath
        val repo = Path.of(options.repo).toAbsolutePath().normalize()
        val worktree = Path.of(options.worktree).toAbsolutePath().normalize()

        val inventory = GitLabInventory(
            client = GitLabClient(defaultGitLabUrl(), pauseMs = options.pauseMs),
            progress = { println("  $it") },
        )
        val snapshot = inventory.scan(options.pipelines)
        val withCoverage = snapshot.coverage.count { it.components.isNotEmpty() }
        println("pipelines: ${snapshot.pipelinesScanned} read, $withCoverage with coverage jobs")
        println("components: ${snapshot.components.joinToString(", ").ifEmpty { "none" }}")

        val baseline = CoverageInventory.selectBaseline(snapshot.coverage)
        if (baseline == null) {
            System.err.println(
                "no ${COVERAGE_BRANCH} push pipeline with ${RAKETA_COMPONENT} coverage among the scanned pipelines",
            )
            kotlin.system.exitProcess(EXIT_NO_COVERAGE)
        }

        val preparer = RepoPreparer(repo, worktree)
        val requested = prepareCommits(snapshot, baseline, preparer, options)
        if (!options.dryRun) {
            preparer.ensureWorktree(baseline.pipeline.sha)
        }

        val plan = InventoryPlan.build(
            coverage = snapshot.coverage,
            repo = repo.toString(),
            worktree = worktree.toString(),
            coverageBranch = options.coverageBranch,
            branchLimit = options.branchLimit,
            hotelsLimit = options.hotelsLimit,
            localSha = { sha -> preparer.hasCommit(sha) },
        )

        println(
            "baseline: ${plan.baseline?.ref} ${shortSha(plan.baseline?.sha)} " +
                "pipeline ${plan.baseline?.pipelineId} shards ${plan.baseline?.shards} " +
                "bytes ${plan.baseline?.bytes}",
        )
        val byClass = plan.branches.groupingBy { it.sizeClass }.eachCount()
        println(
            "branches: ${plan.branches.size} (${byClass.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" }}); " +
                "hotels branches: ${plan.hotelsBranches.size}",
        )
        println("commits: ${requested.available} available, ${requested.fetched} fetched, ${requested.missing} missing")
        if (options.noFetch) println("fetch: disabled (--no-fetch)")

        if (options.dryRun) {
            println("dry run: nothing fetched, no worktree, plan not written")
            return
        }

        val sizes = preparer.sizes()
        println(
            "worktree: $worktree HEAD ${shortSha(preparer.head())} " +
                "${sizes.files} files, ${"%.1f".format(sizes.megabytes())} MB",
        )
        InventoryPlan.write(plan, planPath)
        println("plan: $planPath")
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000_000
        println("done in ${elapsed}s")
    }

    private data class PreparedCommits(val available: Int, val fetched: Int, val missing: Int)

    private fun prepareCommits(
        snapshot: InventorySnapshot,
        baseline: PipelineCoverage,
        preparer: RepoPreparer,
        options: CliOptions,
    ): PreparedCommits {
        val selection = InventoryPlan.select(
            snapshot.coverage,
            options.coverageBranch,
            options.branchLimit,
            options.hotelsLimit,
        )
        val wanted = LinkedHashMap<String, String>()
        wanted[baseline.pipeline.sha] = baseline.pipeline.ref
        for ((ref, sha) in InventoryPlan.commitsToPrepare(selection)) {
            wanted.putIfAbsent(sha, ref)
        }

        var available = 0
        var fetched = 0
        var missing = 0
        for ((sha, ref) in wanted) {
            if (preparer.hasCommit(sha)) {
                available++
                continue
            }
            if (options.dryRun || options.noFetch) {
                missing++
                continue
            }
            if (preparer.ensureLocal(ref, sha, fetchIfMissing = true)) {
                fetched++
            } else {
                missing++
                System.err.println("fetch failed: $ref $sha")
            }
        }
        return PreparedCommits(available, fetched, missing)
    }

    private fun shortSha(sha: String?): String = sha?.take(8) ?: "?"

    private data class CliOptions(
        val dryRun: Boolean,
        val noFetch: Boolean,
        val pipelines: Int,
        val planPath: Path,
        val repo: String,
        val worktree: String,
        val coverageBranch: String,
        val branchLimit: Int,
        val hotelsLimit: Int,
        val pauseMs: Long,
        val help: Boolean,
    ) {
        companion object {
            fun parse(args: Array<String>): CliOptions {
                var dryRun = false
                var noFetch = false
                var pipelines = RealProjectSettings.int("realProject.pipelines", 200)
                var planPath = InventoryPlan.defaultPath()
                var repo = RepoPreparer.defaultRepo().toString()
                var worktree = RepoPreparer.defaultWorktree().toString()
                var coverageBranch = COVERAGE_BRANCH
                var branchLimit = InventoryPlan.DEFAULT_BRANCH_LIMIT
                var hotelsLimit = InventoryPlan.DEFAULT_HOTELS_LIMIT
                var pauseMs = RealProjectSettings.long("realProject.pauseMs", 150)
                var help = false

                var index = 0
                while (index < args.size) {
                    val arg = args[index]
                    val (name, inlineValue) = arg.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                    fun value(): String = inlineValue ?: args.getOrNull(++index)
                        ?: throw IllegalArgumentException("missing value for $name")
                    when (name) {
                        "--dry-run" -> dryRun = true
                        "--no-fetch" -> noFetch = true
                        "--help", "-h" -> help = true
                        "--pipelines" -> pipelines = value().toInt()
                        "--plan" -> planPath = Path.of(value()).toAbsolutePath().normalize()
                        "--repo" -> repo = Path.of(value()).toAbsolutePath().normalize().toString()
                        "--worktree" -> worktree = Path.of(value()).toAbsolutePath().normalize().toString()
                        "--branch" -> coverageBranch = value()
                        "--branches" -> branchLimit = value().toInt()
                        "--hotels" -> hotelsLimit = value().toInt()
                        "--pause-ms" -> pauseMs = value().toLong()
                        else -> throw IllegalArgumentException("unknown option: $arg")
                    }
                    index++
                }
                return CliOptions(
                    dryRun = dryRun,
                    noFetch = noFetch,
                    pipelines = pipelines,
                    planPath = planPath,
                    repo = repo,
                    worktree = worktree,
                    coverageBranch = coverageBranch,
                    branchLimit = branchLimit,
                    hotelsLimit = hotelsLimit,
                    pauseMs = pauseMs,
                    help = help,
                )
            }
        }
    }

    private fun printUsage() {
        println(
            """
            Usage: real-project-prepare.sh [options]
              --dry-run         read the API and print the plan summary; no fetch, no worktree, no plan file
              --no-fetch        use only commits already present in the local repository
              --pipelines N     number of successful pipelines to scan (default 200)
              --plan PATH       plan file (default: ${'$'}realProject.plan or <outputDir>/plan.json)
              --repo PATH       local repository (default: ~/projects/raketa)
              --worktree PATH   detached worktree (default: /tmp/raketa-load)
              --branch NAME     coverage branch used as baseline (default: master)
              --branches N      feature branches written to the plan (default 24)
              --hotels N        hotels branches written to the plan (default 12)
              --pause-ms N      pause before each GitLab request (default 150)
            """.trimIndent(),
        )
    }
}
