package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Size and file count of a worktree, printed by the prepare step. */
data class WorktreeSizes(val bytes: Long, val files: Long) {
    fun megabytes(): Double = bytes / 1024.0 / 1024.0
}

/**
 * Prepares the read-only checkout the load scenarios run against.
 *
 * The user's repository (`~/projects/raketa`) is only read and, for missing commits, fetched:
 * [fetch] never touches a working tree. Branch switching happens in a dedicated detached
 * worktree (`/tmp/raketa-load`), created by [ensureWorktree] from the baseline commit.
 *
 * Paths come from `-DrealProject.repo` / `-DrealProject.worktree` (or `REAL_PROJECT_REPO` /
 * `REAL_PROJECT_WORKTREE`).
 */
class RepoPreparer(
    val repo: Path = defaultRepo(),
    val worktree: Path = defaultWorktree(),
) {
    init {
        require(Files.isDirectory(repo)) { "repository does not exist: $repo" }
        val probe = git(repo, "rev-parse", "--git-dir")
        require(probe.ok) { "not a git repository: $repo (${probe.output.trim()})" }
    }

    /** Whether [sha] is a commit object present in the local repository. */
    fun hasCommit(sha: String): Boolean = git(repo, "cat-file", "-e", "$sha^{commit}").ok

    /** Commits registered in [repo] other than [repo] itself: our worktree must be one of them. */
    fun registeredWorktrees(): List<Path> {
        val result = git(repo, "worktree", "list", "--porcelain")
        if (!result.ok) return emptyList()
        return result.output.lineSequence()
            .filter { it.startsWith("worktree ") }
            .map { Path.of(it.removePrefix("worktree ").trim()) }
            .toList()
    }

    /** Whether [worktree] is a live worktree of [repo]. Never creates or repairs anything. */
    fun isRegisteredWorktree(): Boolean {
        if (!Files.isDirectory(worktree)) return false
        val target = worktree.toRealPath()
        return registeredWorktrees().any { it.toAbsolutePath().normalize().toRealPathOrNull() == target }
    }

    /**
     * Fetches [ref] (falling back to the full branch ref and then to [sha]) into the shared
     * `.git`. No checkout, no reset, no change to the user's working copy.
     */
    fun fetch(ref: String, sha: String): Boolean {
        val refspecs = listOf("refs/heads/$ref", ref, sha)
        for (refspec in refspecs) {
            val result = git(repo, "fetch", "--no-tags", "--quiet", "origin", refspec, timeoutSeconds = 600)
            if (result.ok) return true
        }
        return false
    }

    /**
     * Makes [sha] available locally, fetching [ref] when it is missing and [fetchIfMissing] is
     * set. Returns whether the commit is present afterwards.
     */
    fun ensureLocal(ref: String, sha: String, fetchIfMissing: Boolean = true): Boolean {
        if (hasCommit(sha)) return true
        if (!fetchIfMissing) return false
        if (!fetch(ref, sha)) return false
        return hasCommit(sha)
    }

    /**
     * Creates the detached worktree at [worktree] on [sha], or reuses the existing one. Throws
     * when the path exists but is not a worktree of [repo]: foreign directories are never touched.
     */
    fun ensureWorktree(sha: String): Path {
        if (!hasCommit(sha)) {
            throw IllegalStateException("commit $sha is not in $repo; fetch it before creating the worktree")
        }
        if (Files.isDirectory(worktree)) {
            if (!isRegisteredWorktree()) {
                throw IllegalStateException(
                    "$worktree exists but is not a worktree of $repo; refusing to touch it",
                )
            }
            return worktree
        }
        require(!Files.exists(worktree)) { "$worktree exists and is not a directory" }
        worktree.parent?.let { Files.createDirectories(it) }
        val result = git(repo, "worktree", "add", "--detach", worktree.toString(), sha, timeoutSeconds = 300)
        if (!result.ok) {
            throw IllegalStateException("git worktree add $worktree $sha failed: ${result.output.trim()}")
        }
        return worktree
    }

    /**
     * Detaches the worktree at [sha]. A dirty worktree is an error unless [force] is set; a forced
     * switch discards changes only inside [worktree] (`reset --hard` plus `clean -fd`, ignored
     * files such as `vendor/` are kept).
     */
    fun checkout(sha: String, force: Boolean = false) {
        check(isRegisteredWorktree()) {
            "$worktree is not a worktree of $repo; call ensureWorktree() first"
        }
        check(hasCommit(sha)) { "commit $sha is not in $repo" }
        val status = git(worktree, "status", "--porcelain")
        val dirty = status.output.lines().filter { it.isNotBlank() }
        if (dirty.isNotEmpty()) {
            if (!force) {
                val shown = dirty.take(5).joinToString("; ")
                throw IllegalStateException(
                    "$worktree has local changes ($shown); refusing to switch to $sha",
                )
            }
            git(worktree, "reset", "--hard")
            git(worktree, "clean", "-fd")
        }
        if (head() == sha) return
        val result = git(worktree, "checkout", "--detach", sha, timeoutSeconds = 300)
        check(result.ok) { "git checkout --detach $sha failed: ${result.output.trim()}" }
    }

    /** Current HEAD of [worktree]. */
    fun head(): String {
        val result = git(worktree, "rev-parse", "HEAD")
        check(result.ok) { "cannot read HEAD of $worktree: ${result.output.trim()}" }
        return result.output.trim()
    }

    fun isClean(): Boolean = git(worktree, "status", "--porcelain").output.isBlank()

    /** On-disk size (like `du -s`) and number of regular files of [worktree]. */
    fun sizes(): WorktreeSizes {
        val files = Files.walk(worktree).use { stream ->
            stream.filter { Files.isRegularFile(it) }.count()
        }
        return WorktreeSizes(duBytes(worktree), files)
    }

    private fun duBytes(path: Path): Long {
        val result = run(listOf("du", "-sk", path.toString()))
        val kilobytes = result.output.lineSequence()
            .firstOrNull()
            ?.trim()
            ?.substringBefore('\t')
            ?.toLongOrNull()
        if (result.code == 0 && kilobytes != null) return kilobytes * 1024L
        // Fallback for systems without du: sum the file sizes.
        return Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }

    private data class GitResult(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    private fun git(dir: Path, vararg args: String, timeoutSeconds: Long = 120): GitResult =
        run(listOf("git", "-C", dir.toString()) + args, timeoutSeconds)

    private fun run(command: List<String>, timeoutSeconds: Long = 120): GitResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            throw IllegalStateException("command timed out after ${timeoutSeconds}s: ${command.joinToString(" ")}")
        }
        return GitResult(process.exitValue(), output)
    }

    companion object {
        fun defaultRepo(): Path = Path.of(
            RealProjectSettings.value(
                "realProject.repo",
                Path.of(System.getProperty("user.home"), "projects", "raketa").toString(),
            ),
        ).toAbsolutePath().normalize()

        fun defaultWorktree(): Path = Path.of(
            RealProjectSettings.value("realProject.worktree", "/tmp/raketa-load"),
        ).toAbsolutePath().normalize()
    }
}

private fun Path.toRealPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()
