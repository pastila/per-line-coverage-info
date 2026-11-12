package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.io.IOException

/** Read-only historical analysis. Every selected commit contributes independently of the others. */
internal class CommitAffectedTests(
    private val gitRoot: File,
    private val loadedCoverage: List<Coverage> = emptyList(),
    private val cachedCoverage: (String) -> Coverage? = { null },
) {
    data class Coverage(val commit: String, val read: (String) -> Map<Int, List<String>>?)
    enum class Reason(val label: String) {
        COVERAGE("Covers changed code"), ADDED("Added test"), MODIFIED("Modified test"),
    }
    data class Evidence(val commit: String, val file: String, val reason: Reason)
    data class Result(
        val commits: List<String>,
        val perFile: Map<String, Set<String>>,
        val evidence: Map<String, Set<Evidence>>,
        val removedTests: Set<String>,
        val warnings: Set<String>,
        val head: String,
        val unresolvedTests: Map<String, String>,
    )

    private val sources = mutableMapOf<Pair<String, String>, String?>()
    private val parsedTests = mutableMapOf<Pair<String, String>, List<ChangedTestSources.Test>>()
    private val paths = mutableMapOf<Pair<String, String>, Map<String, String?>>()
    private val diffs = mutableMapOf<List<String>, String>()
    private val revisionPaths = mutableMapOf<String, List<String>>()
    private val validRevisions = mutableMapOf<String, Boolean>()
    private val currentPhpTests = mutableMapOf<Pair<String, String>, String?>()
    private val warnings = linkedSetOf<String>()
    private val perFile = linkedMapOf<String, MutableSet<String>>()
    private val evidence = linkedMapOf<String, MutableSet<Evidence>>()
    private val removed = linkedSetOf<String>()
    private val unresolvedTests = linkedMapOf<String, String>()
    private lateinit var head: String
    private val references by lazy {
        BehatTestReferences(
            readSource = ::content,
            readCurrent = { path -> content(head, path) },
            currentPath = { revision, path -> pathAt(revision, head, path) },
        )
    }

    fun compute(selected: List<String>): Result {
        require(selected.isNotEmpty()) { "Select at least one commit" }
        head = git("rev-parse", "HEAD").trim()
        val commits = selected.distinct()
        for (commit in commits) {
            CoverageCancellation.check()
            require(Regex("[0-9a-fA-F]{40,64}").matches(commit)) { "Invalid commit hash" }
            val parent = git("rev-list", "--parents", "-n", "1", commit).trim().split(' ').getOrNull(1)
            val diff = if (parent == null) git(*DIFF_OPTIONS, "show", "--format=", "--root", *PATCH_OPTIONS, commit, "--")
                else patch(parent, commit)
            val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
            for (change in changes) {
                CoverageCancellation.check()
                val path = change.newPath ?: change.oldPath ?: continue
                perFile.getOrPut(path) { linkedSetOf() }
                collectChangedTests(commit, parent, change)
                if (parent != null && change.oldPath != null) {
                    collectCoverage(commit, path, parent, change.oldPath, change.affectedOldLines)
                }
                if (change.newPath != null) collectCoverage(commit, path, commit, change.newPath, change.affectedNewLines)
            }
        }
        if (git("rev-parse", "HEAD").trim() != head) throw IOException("HEAD changed during analysis; refresh the selected commits")
        return Result(commits, perFile.mapValues { it.value.toSet() }, evidence.mapValues { it.value.toSet() }, removed, warnings, head, unresolvedTests)
    }

    private fun collectChangedTests(commit: String, parent: String?, change: ChangedLinesAnalyzer.FileChange) {
        val oldTests = if (parent != null && change.oldPath != null) tests(parent, change.oldPath) else emptyList()
        val newTests = change.newPath?.let { tests(commit, it) }.orEmpty()
        val before = oldTests.associateBy { it.key }
        val after = newTests.associateBy { it.key }
        for (test in newTests) {
            val old = before[test.key]
            val reason = when {
                old == null -> Reason.ADDED
                old.body != test.body || old.shared != test.shared -> Reason.MODIFIED
                else -> continue
            }
            val current = currentTests(commit, change.newPath!!, test)
            if (current.isEmpty()) removed += test.name
            else current.forEach { add(it, Evidence(commit, change.newPath, reason)) }
        }
        for (test in oldTests.filter { it.key !in after }) {
            if (parent != null && change.oldPath != null && currentTests(parent, change.oldPath, test).isEmpty()) removed += test.name
        }
    }

    private fun collectCoverage(selectedCommit: String, displayPath: String, revision: String, path: String, lines: Set<Int>) {
        if (lines.isEmpty()) return
        val exact = cachedCoverage(revision)
        val coverage = (listOfNotNull(exact) + loadedCoverage).distinctBy { it.commit }
        if (coverage.isEmpty()) {
            warnings += "No coverage is available; only added or modified tests are included."
            return
        }
        var found = false
        val mapped = mutableSetOf<Int>()
        for (source in coverage) {
            if (!validRevisions.getOrPut(source.commit) {
                    GitCommand.runRaw(gitRoot, "cat-file", "-e", "${source.commit}^{commit}") != null
                }) {
                warnings += "Some coverage artifacts have no git revision and cannot be used for historical analysis."
                continue
            }
            val coveragePath = pathAt(revision, source.commit, path) ?: continue
            val byLine = source.read(coveragePath) ?: source.read("/$coveragePath") ?: continue
            found = true
            val mapping = if (revision == source.commit) lines.associateWith { it }
                else mapUnchangedLines(lines, patch(revision, source.commit, path, coveragePath))
            mapped += mapping.keys
            for (line in mapping.values) {
                for (test in byLine[line].orEmpty()) {
                    if (CoverageTestIdentity.isAnonymous(test)) {
                        warnings += "Some coverage has no test names; those entries cannot identify affected tests."
                        continue
                    }
                    if (CoverageTestNavigator.isBehatTest(test)) {
                        val resolved = resolveBehat(source.commit, test)
                        resolved.forEach { add(it, Evidence(selectedCommit, displayPath, Reason.COVERAGE)) }
                    } else {
                        val current = currentCoveredTest(source.commit, test)
                        if (current != null) add(current, Evidence(selectedCommit, displayPath, Reason.COVERAGE))
                        else removed += test
                    }
                }
            }
        }
        if (!found || mapped.size < lines.size) warnings += "Some changed lines have no matching coverage revision; the test list may be incomplete."
    }

    private fun currentCoveredTest(revision: String, name: String): String? {
        if ("::" !in name) {
            warnings += "Some covering test identities have no source location."
            return name
        }
        val identity = revision to name
        if (!currentPhpTests.containsKey(identity)) {
            val className = name.substringBefore("::").trimStart('\\')
            val method = name.substringAfter("::").substringBefore(" with data set")
            val basename = className.substringAfterLast('\\') + ".php"
            val candidates = classPaths(head, basename)
            val canonical = "$className::$method"
            val current = candidates.asSequence().flatMap { tests(head, it).asSequence() }
                .firstOrNull { it.name == canonical }?.name
            val declaredBefore = current == null && classPaths(revision, basename)
                .any { path -> tests(revision, path).any { it.name == canonical } }
            currentPhpTests[identity] = when {
                current != null -> current
                candidates.isNotEmpty() && !declaredBefore -> {
                    // A child may execute an inherited method; absence in its own file proves no deletion.
                    warnings += "Some PHPUnit methods may be inherited; their coverage identities are retained."
                    canonical
                }
                else -> null
            }
        }
        return currentPhpTests[identity]
    }

    private fun classPaths(revision: String, basename: String): List<String> = revisionPaths.getOrPut(revision) {
        git("ls-tree", "-r", "-z", "--name-only", revision, "--").split('\u0000')
    }.filter { it.substringAfterLast('/') == basename }

    private fun currentTests(revision: String, path: String, test: ChangedTestSources.Test): List<String> {
        if (path.endsWith(".feature")) return resolveBehat(revision, test.name)
        val currentPath = pathAt(revision, head, path) ?: return emptyList()
        return listOfNotNull(tests(head, currentPath).firstOrNull { it.key == test.key }?.name)
    }

    private fun resolveBehat(revision: String, name: String): List<String> {
        val result = references.resolve(listOf(BehatTestReferences.Request(name, revision)))
        for (problem in result.unresolved) {
            removed += problem.request.name
            unresolvedTests[problem.request.name] = problem.reason
        }
        return result.names
    }

    private fun add(test: String, item: Evidence) {
        perFile.getOrPut(item.file) { linkedSetOf() } += test
        evidence.getOrPut(test) { linkedSetOf() } += item
    }

    private fun tests(revision: String, path: String): List<ChangedTestSources.Test> =
        parsedTests.getOrPut(revision to path) { content(revision, path)?.let { ChangedTestSources.parse(path, it) }.orEmpty() }

    private fun content(revision: String, path: String): String? = sources.getOrPut(revision to path) {
        GitCommand.runRaw(gitRoot, "show", "$revision:$path")
    }

    private fun pathAt(from: String, to: String, path: String): String? {
        if (from == to) return path
        val changes = paths.getOrPut(from to to) {
            val fields = git("diff", "--no-ext-diff", "--no-textconv", "--name-status", "-z", "-M", from, to, "--").split('\u0000')
            buildMap {
                var i = 0
                while (i < fields.size && fields[i].isNotEmpty()) {
                    val status = fields[i++]
                    val old = fields.getOrNull(i++) ?: break
                    when {
                        status.startsWith('R') -> put(old, fields.getOrNull(i++) ?: break)
                        status.startsWith('C') -> i++
                        status == "D" -> put(old, null)
                    }
                }
            }
        }
        return if (changes.containsKey(path)) changes[path] else path
    }

    private fun patch(from: String, to: String, vararg paths: String): String {
        val key = listOf(from, to) + paths.distinct()
        return diffs.getOrPut(key) { git(*DIFF_OPTIONS, "diff", *PATCH_OPTIONS, from, to, "--", *paths.distinct().toTypedArray()) }
    }

    private fun git(vararg args: String): String = GitCommand.runRaw(gitRoot, *args)
        ?: throw IOException("Cannot read commit changes from git; see the coverage log")

    companion object {
        private val DIFF_OPTIONS = arrayOf("-c", "core.quotePath=false")
        private val PATCH_OPTIONS = arrayOf("--no-color", "--no-ext-diff", "--no-textconv", "--src-prefix=a/", "--dst-prefix=b/", "-U0", "-M")
        private val HUNK = Regex("(?m)^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

        /** Translate only unchanged lines; changed or deleted lines have no reliable correspondence. */
        internal fun mapUnchangedLines(lines: Set<Int>, diff: String): Map<Int, Int> {
            if (Regex("(?m)^diff --git ").findAll(diff).take(2).count() > 1) return emptyMap()
            val hunks = HUNK.findAll(diff).map {
                listOf(it.groupValues[1].toInt(), it.groupValues[2].ifEmpty { "1" }.toInt(),
                    it.groupValues[3].toInt(), it.groupValues[4].ifEmpty { "1" }.toInt())
            }.toList()
            return buildMap {
                for (line in lines) {
                    var shift = 0
                    var changed = false
                    for ((oldStart, oldCount, _, newCount) in hunks) {
                        if (oldCount == 0) {
                            if (line > oldStart) shift += newCount
                        } else if (line >= oldStart && line < oldStart + oldCount) {
                            changed = true
                            break
                        } else if (line >= oldStart + oldCount) shift += newCount - oldCount
                    }
                    if (!changed) put(line, line + shift)
                }
            }
        }
    }
}
