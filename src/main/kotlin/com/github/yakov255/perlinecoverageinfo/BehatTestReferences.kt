package com.github.yakov255.perlinecoverageinfo

/** Resolves executable identities, never the closest scenario to an arbitrary line. */
internal class BehatTestReferences(
    private val readSource: (String, String) -> String?,
    private val readCurrent: (String) -> String?,
    private val currentPath: (String, String) -> String? = { _, path -> path },
) {
    data class Request(
        val name: String,
        /** Null means current source, e.g. a list without a CI artifact. */
        val revision: String? = null,
        /** Immutable text pinned when the list was resolved (or when a local run was collected). */
        val source: String? = null,
        val requested: String = name,
    )
    data class Resolved(val target: String, val label: String, val origin: Request)
    data class Problem(val request: Request, val reason: String)
    data class Result(val resolved: List<Resolved>, val unresolved: List<Problem>) {
        val names: List<String> get() = resolved.map { it.target }.distinct()
    }

    private data class Source(val content: String, val tests: List<ChangedTestSources.Test>)
    private val historical = mutableMapOf<Pair<String, String>, Source?>()
    private val current = mutableMapOf<String, Source?>()
    private val pinnedSources = mutableMapOf<Pair<String, String>, Source>()

    fun resolve(requests: List<Request>): Result {
        val ready = mutableListOf<Resolved>()
        val problems = mutableListOf<Problem>()
        fun problem(request: Request, reason: String) { problems += Problem(request, reason) }
        for (request in requests) {
            CoverageCancellation.check()
            if (!CoverageTestNavigator.isBehatTest(request.name)) {
                ready += Resolved(request.name, request.name.substringAfterLast("::"), request)
                continue
            }
            val path = request.name.substringBeforeLast(':')
            val line = request.name.substringAfterLast(':').toIntOrNull()
            if (line == null || line < 1) { problem(request, "Invalid test line"); continue }
            val revision = request.revision
            val targetPath = if (revision == null) path else currentPath(revision, path)
            if (targetPath == null) { problem(request, "Feature file was deleted"); continue }
            val target = current.getOrPut(targetPath) { readCurrent(targetPath)?.let { source(targetPath, it) } }
            if (target == null) { problem(request, "Current feature file is unavailable"); continue }
            val old = when {
                request.source != null -> pinnedSources.getOrPut(path to request.source) { source(path, request.source) }
                revision != null -> historical.getOrPut(revision to path) { readSource(revision, path)?.let { source(path, it) } }
                else -> target
            }
            if (old == null) { problem(request, "Feature source at the coverage revision is unavailable"); continue }
            val matches = old.tests.filter { it.line == line || it.examples.any { row -> row.line == line } }
            if (matches.size != 1) { problem(request, "Line is not a scenario or an Examples data row in the source revision"); continue }
            val original = matches.single()
            val sameSource = path == targetPath && old.content == target.content
            val candidates = if (sameSource) target.tests.filter { it.line == original.line }
                else target.tests.filter { identity(it) == identity(original) }
            if (candidates.size != 1) {
                problem(request, if (candidates.isEmpty()) "Scenario was deleted or renamed" else "Scenario identity is ambiguous")
                continue
            }
            val scenario = candidates.single()
            val pinned = request.copy(source = old.content)
            if (!original.outline) {
                if (scenario.outline) { problem(request, "Scenario changed to a Scenario Outline"); continue }
                ready += Resolved("$targetPath:${scenario.line}", title(scenario), pinned)
                continue
            }
            if (!scenario.outline) { problem(request, "Scenario Outline changed to a scenario"); continue }
            val rows = if (line == original.line) original.examples else original.examples.filter { it.line == line }
            if (rows.isEmpty()) { problem(request, "Scenario Outline has no executable Examples rows"); continue }
            for (row in rows) {
                val origin = pinned.copy(name = "$path:${row.line}")
                val matchingRows = if (sameSource) scenario.examples.filter { it.line == row.line }
                    else scenario.examples.filter { it.cells == row.cells }.let { candidates ->
                        if (candidates.size > 1) candidates.filter { it.table == row.table } else candidates
                    }
                if (matchingRows.size != 1) {
                    problem(origin, if (matchingRows.isEmpty()) "Examples row was deleted or changed" else "Examples row is ambiguous")
                    continue
                }
                val matched = matchingRows.single()
                val sample = matched.cells.entries.joinToString(", ") { "${it.key}=${it.value}" }
                ready += Resolved("$targetPath:${matched.line}", "${title(scenario)} — $sample", origin)
            }
        }
        return Result(ready.distinctBy { listOf(it.target, it.origin.name, it.origin.revision, it.origin.requested) }, problems.distinct())
    }

    private fun source(path: String, text: String) = Source(text, ChangedTestSources.parse(path, text))
    private fun identity(test: ChangedTestSources.Test): List<String> = test.key.split('\u0000').drop(1).dropLast(1)
    private fun title(test: ChangedTestSources.Test): String = test.key.split('\u0000').dropLast(1).last()
}
