package com.github.yakov255.perlinecoverageinfo.realproject.inventory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * RepoPreparer against a throwaway local repository: a bare origin, a seed clone that pushes
 * commits, and a "user" clone that RepoPreparer reads and fetches into.
 */
class RepoPreparerTest {

    private lateinit var root: Path
    private lateinit var origin: Path
    private lateinit var seed: Path
    private lateinit var repo: Path

    private lateinit var commitA: String
    private lateinit var commitB: String
    private lateinit var commitC: String

    @Before
    fun setUp() {
        root = Files.createTempDirectory("repo-preparer-test")
        origin = root.resolve("origin.git")
        seed = root.resolve("seed")
        repo = root.resolve("repo")

        git(root, "init", "--bare", "--initial-branch=master", origin.toString())
        git(root, "init", "--initial-branch=master", seed.toString())
        git(seed, "config", "user.name", "Real Project Test")
        git(seed, "config", "user.email", "real-project-test@example.invalid")
        git(seed, "remote", "add", "origin", origin.toString())

        Files.writeString(seed.resolve("app.php"), "<?php // A\n")
        git(seed, "add", "app.php")
        git(seed, "commit", "-m", "A")
        commitA = git(seed, "rev-parse", "HEAD")
        git(seed, "push", "--quiet", "origin", "master")

        git(root, "clone", "--quiet", origin.toString(), repo.toString())

        Files.writeString(seed.resolve("app.php"), "<?php // B\n")
        git(seed, "commit", "-am", "B")
        commitB = git(seed, "rev-parse", "HEAD")
        git(seed, "push", "--quiet", "origin", "master")

        Files.writeString(seed.resolve("app.php"), "<?php // C\n")
        git(seed, "commit", "-am", "C")
        commitC = git(seed, "rev-parse", "HEAD")
        git(seed, "push", "--quiet", "origin", "master")
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `missing commit is fetched into the shared repository`() {
        val preparer = RepoPreparer(repo, root.resolve("wt"))

        assertTrue(preparer.hasCommit(commitA))
        assertFalse(preparer.hasCommit(commitB))

        assertTrue(preparer.ensureLocal("master", commitB))
        assertTrue(preparer.hasCommit(commitB))
        assertTrue(preparer.ensureLocal("master", commitC))
    }

    @Test
    fun `ensureLocal without fetch reports a missing commit`() {
        val preparer = RepoPreparer(repo, root.resolve("wt"))
        assertFalse(preparer.ensureLocal("master", commitB, fetchIfMissing = false))
        assertFalse(preparer.hasCommit(commitB))
    }

    @Test
    fun `worktree is created detached, reused and measured`() {
        val preparer = RepoPreparer(repo, root.resolve("wt"))
        preparer.ensureLocal("master", commitB)

        val worktree = preparer.ensureWorktree(commitB)
        assertEquals(commitB, preparer.head())
        assertTrue(preparer.isRegisteredWorktree())
        assertTrue(preparer.isClean())

        val sizes = preparer.sizes()
        assertTrue("worktree must contain the tracked file", sizes.files >= 1)
        assertTrue("du must report a non-empty worktree", sizes.bytes > 0)

        val again = RepoPreparer(repo, root.resolve("wt")).ensureWorktree(commitB)
        assertEquals(worktree.toRealPath(), again.toRealPath())
    }

    @Test
    fun `checkout moves the detached head`() {
        val preparer = RepoPreparer(repo, root.resolve("wt"))
        preparer.ensureLocal("master", commitC)
        preparer.ensureWorktree(commitA)

        preparer.checkout(commitC)
        assertEquals(commitC, preparer.head())

        preparer.checkout(commitA)
        assertEquals(commitA, preparer.head())
    }

    @Test
    fun `dirty worktree refuses a switch and force discards only local changes`() {
        val preparer = RepoPreparer(repo, root.resolve("wt"))
        preparer.ensureLocal("master", commitB)
        preparer.ensureWorktree(commitA)

        Files.writeString(root.resolve("wt/app.php"), "<?php // local edit\n")
        Files.writeString(root.resolve("wt/untracked.txt"), "scratch\n")
        assertFalse(preparer.isClean())

        val failure = assertThrows(IllegalStateException::class.java) { preparer.checkout(commitB) }
        assertTrue(failure.message!!.contains("local changes"))
        assertEquals("a refused switch must not move HEAD", commitA, preparer.head())

        preparer.checkout(commitB, force = true)
        assertEquals(commitB, preparer.head())
        assertTrue(preparer.isClean())
        assertFalse(Files.exists(root.resolve("wt/untracked.txt")))
    }

    @Test
    fun `foreign directory is never touched`() {
        val foreign = root.resolve("foreign")
        Files.createDirectories(foreign)
        Files.writeString(foreign.resolve("keep.txt"), "user data\n")

        val preparer = RepoPreparer(repo, foreign)
        val failure = assertThrows(IllegalStateException::class.java) { preparer.ensureWorktree(commitA) }

        assertTrue(failure.message!!.contains("refusing"))
        assertTrue(Files.exists(foreign.resolve("keep.txt")))
        assertFalse("no worktree metadata may be created", preparer.isRegisteredWorktree())
    }

    @Test
    fun `checkout on a directory that is not a worktree is refused`() {
        val plain = root.resolve("plain")
        Files.createDirectories(plain)
        val preparer = RepoPreparer(repo, plain)

        assertThrows(IllegalStateException::class.java) { preparer.checkout(commitA) }
    }

    @Test
    fun `unknown commit is refused`() {
        val preparer = RepoPreparer(repo, root.resolve("wt"))
        assertThrows(IllegalStateException::class.java) { preparer.ensureWorktree("0".repeat(40)) }
    }

    private fun git(cwd: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", cwd.toString()) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
        return output.trim()
    }
}
