package com.github.yakov255.perlinecoverageinfo

import com.github.yakov255.perlinecoverageinfo.CoverageLog
import java.io.File

/**
 * Analyzes git changes between the coverage commit and either HEAD or the working tree
 * to compute, per file, the set of *old* line numbers (lines as they were in the coverage
 * commit) that have been touched by the change.
 *
 * Those old lines drive test selection: looking up `coverage[oldLine]` yields the tests
 * that need to re-run.
 */
object ChangedLinesAnalyzer {

    private val log = CoverageLog.get(ChangedLinesAnalyzer::class.java)

    enum class DiffMode {
        /** coverageCommit..HEAD — committed changes only. */
        COMMITTED,

        /** coverageCommit..workingTree — committed + unstaged changes (tracked files only). */
        WORKING_TREE,
    }

    data class FileChange(
        /** Path as it existed in the coverage commit. Null for newly added files. */
        val oldPath: String?,
        /** Path as it currently exists. Null for deleted files. */
        val newPath: String?,
        /** Old line numbers affected by the change. Empty for new files. */
        val affectedOldLines: Set<Int>,
        val isNew: Boolean,
        val isDeleted: Boolean,
        val isRenamed: Boolean,
    )

    /**
     * Runs `git diff` against the coverage commit and returns one [FileChange] per touched file.
     * Returns an empty list on git failure (caller can detect "no changes" the same way).
     */
    fun analyze(gitRoot: File, coverageCommit: String, mode: DiffMode): List<FileChange> {
        val args = mutableListOf("diff", "--no-color", "-U0", "-M", coverageCommit)
        if (mode == DiffMode.COMMITTED) args.add("HEAD")
        val output = runGit(gitRoot, args)
        if (output == null) {
            log.warn("ChangedLinesAnalyzer: git diff returned null, treating as no changes")
            return emptyList()
        }
        val raw = output
        return parseUnifiedDiff(raw)
    }

    /**
     * Parses a unified diff (as produced by `git diff -U0 -M`) into [FileChange] entries.
     * Public for unit tests.
     */
    fun parseUnifiedDiff(diff: String): List<FileChange> {
        if (diff.isBlank()) return emptyList()
        val result = mutableListOf<FileChange>()

        var oldPath: String? = null
        var newPath: String? = null
        var isNew = false
        var isDeleted = false
        var isRenamed = false
        val affected = mutableSetOf<Int>()
        var inHeader = false

        fun flush() {
            if (oldPath == null && newPath == null) return
            // Collapse pure additions on a brand-new file: no old lines.
            val lines = if (isNew) emptySet() else affected.toSet()
            result += FileChange(
                oldPath = if (isNew) null else oldPath,
                newPath = if (isDeleted) null else newPath,
                affectedOldLines = lines,
                isNew = isNew,
                isDeleted = isDeleted,
                isRenamed = isRenamed,
            )
            oldPath = null
            newPath = null
            isNew = false
            isDeleted = false
            isRenamed = false
            affected.clear()
        }

        for (line in diff.lineSequence()) {
            when {
                line.startsWith("diff --git ") -> {
                    flush()
                    inHeader = true
                    // Best-effort path extraction from the header. Refined by --- / +++ lines below.
                    val parts = line.removePrefix("diff --git ").split(" ")
                    if (parts.size >= 2) {
                        oldPath = parts[0].removePrefix("a/")
                        newPath = parts[1].removePrefix("b/")
                    }
                }
                inHeader && line.startsWith("new file mode") -> isNew = true
                inHeader && line.startsWith("deleted file mode") -> isDeleted = true
                inHeader && line.startsWith("rename from ") -> {
                    isRenamed = true
                    oldPath = line.removePrefix("rename from ")
                }
                inHeader && line.startsWith("rename to ") -> {
                    newPath = line.removePrefix("rename to ")
                }
                line.startsWith("--- ") -> {
                    val p = line.removePrefix("--- ")
                    if (p != "/dev/null") oldPath = p.removePrefix("a/")
                }
                line.startsWith("+++ ") -> {
                    val p = line.removePrefix("+++ ")
                    if (p != "/dev/null") newPath = p.removePrefix("b/")
                    inHeader = false
                }
                line.startsWith("@@") -> {
                    inHeader = false
                    val hunk = parseHunkHeader(line) ?: continue
                    val (oldStart, oldCount) = hunk
                    if (oldCount == 0) {
                        // Pure addition: include the lines straddling the insertion point
                        // so the surrounding function's tests get re-run.
                        if (oldStart >= 1) affected += oldStart
                        affected += (oldStart + 1)
                    } else {
                        for (l in oldStart until oldStart + oldCount) {
                            if (l >= 1) affected += l
                        }
                    }
                }
                // Body lines (+/-/ ) are ignored — hunk header carries everything we need.
            }
        }
        flush()
        return result
    }

    /**
     * Parses `@@ -oldStart[,oldCount] +newStart[,newCount] @@ ...` and returns (oldStart, oldCount).
     * Defaults oldCount to 1 when omitted (per unified diff spec).
     */
    private fun parseHunkHeader(line: String): Pair<Int, Int>? {
        val match = HUNK_RE.find(line) ?: return null
        val oldStart = match.groupValues[1].toIntOrNull() ?: return null
        val oldCount = match.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return null
        return oldStart to oldCount
    }

    private val HUNK_RE = Regex("""^@@ -(\d+)(?:,(\d+))? \+\d+(?:,\d+)? @@""")

    private fun runGit(gitRoot: File, args: List<String>): String? {
        return try {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(gitRoot)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        } catch (e: Exception) {
            log.warn("ChangedLinesAnalyzer: git diff command failed: ${e.message}")
            null
        }
    }
}
