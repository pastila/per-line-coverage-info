package com.github.yakov255.perlinecoverageinfo

/**
 * One locally executed Behat run, loaded from a `.covt` file.
 *
 * Line numbers in [coverage] refer to the files as they were on disk when the run was loaded,
 * which is why every covered file keeps its [snapshots] content: it is later diffed against the
 * current editor text, the same way CI coverage is diffed against `git show <commit>:path`.
 */
class LocalCoverageRun(
    /** Every test executed in the run — also the tests whose CI data this run supersedes. */
    val tests: Set<String>,
    /** Git-root-relative path → 1-based line → covering tests (empty list = coverable, not covered). */
    val coverage: Map<String, Map<Int, List<String>>>,
    /** Git-root-relative path → file content the line numbers in [coverage] refer to. */
    val snapshots: Map<String, String>,
) {
    /** Returns this run with [superseded] tests removed, or null if no tests are left. */
    fun without(superseded: Set<String>): LocalCoverageRun? {
        if (tests.none { it in superseded }) return this
        val remaining = tests - superseded
        if (remaining.isEmpty()) return null
        val filtered = coverage.mapValues { (_, lines) ->
            lines.mapValues { (_, lineTests) -> lineTests.filter { it !in superseded } }
        }
        return LocalCoverageRun(remaining, filtered, snapshots)
    }
}

/**
 * Accumulates local runs on top of CI coverage.
 *
 * A newer run supersedes older ones test by test: if a scenario is re-run, its previous local
 * results are dropped and only the latest execution counts. Thread-safe; readers get an
 * immutable snapshot of the run list.
 */
class LocalCoverageLayer {

    @Volatile
    private var runs: List<LocalCoverageRun> = emptyList()

    fun add(run: LocalCoverageRun) {
        synchronized(this) {
            runs = runs.mapNotNull { it.without(run.tests) } + run
        }
    }

    fun clear() {
        synchronized(this) {
            runs = emptyList()
        }
    }

    fun isEmpty(): Boolean = runs.isEmpty()

    fun runs(): List<LocalCoverageRun> = runs

    /** All tests executed locally, across runs. */
    fun tests(): Set<String> = runs.flatMapTo(HashSet()) { it.tests }

    fun fileCount(): Int = runs.flatMapTo(HashSet()) { it.coverage.keys }.size
}

object LocalCoverageMerge {

    /**
     * Merges CI and local coverage of one file, both already mapped onto the current document.
     *
     * For every line: `(ci − localTests) ∪ local`. Tests executed locally replace their CI data
     * entirely, so a scenario that stopped reaching a line after a local change no longer keeps
     * it green; tests that were not run locally keep their CI data. A line is coverable if either
     * side knows it as coverable.
     *
     * @return null when neither side has data for the file.
     */
    fun merge(
        ci: Map<Int, List<String>>?,
        local: Map<Int, List<String>>?,
        localTests: Set<String>,
    ): Map<Int, List<String>>? {
        if (ci == null) return local
        if (localTests.isEmpty() && local == null) return ci

        val result = LinkedHashMap<Int, List<String>>(ci.size + (local?.size ?: 0))
        for ((line, tests) in ci) {
            result[line] = tests.filter { it !in localTests }
        }
        local?.forEach { (line, tests) ->
            result[line] = CoverageDiff.union(result[line] ?: emptyList(), tests)
        }
        return result
    }

    /** Unions per-run line maps of one file (each already mapped onto the current document). */
    fun unionRuns(perRun: List<Map<Int, List<String>>>): Map<Int, List<String>>? {
        if (perRun.isEmpty()) return null
        if (perRun.size == 1) return perRun[0]
        val result = LinkedHashMap<Int, List<String>>()
        for (lines in perRun) {
            for ((line, tests) in lines) {
                result[line] = CoverageDiff.union(result[line] ?: emptyList(), tests)
            }
        }
        return result
    }
}

object LocalCoveragePaths {

    private val WEB_PREFIX = Regex("^(?:[^/]+/)?web/(.+)$")

    /**
     * Maps a path from a `.covt` onto a git-root-relative path that exists.
     *
     * The PHP extension relativises paths against the Behat base path (`/web/core`) and then
     * prepends `--binary-coverage-root`, so files outside it come out as `core/web/app/...`
     * instead of `app/...`. Such paths are rewritten when the stripped variant exists.
     *
     * @return the path to use, or null if neither variant exists.
     */
    fun normalize(path: String, exists: (String) -> Boolean): String? {
        val trimmed = path.trimStart('/')
        if (exists(trimmed)) return trimmed
        val stripped = WEB_PREFIX.find(trimmed)?.groupValues?.get(1) ?: return null
        return stripped.takeIf(exists)
    }
}
