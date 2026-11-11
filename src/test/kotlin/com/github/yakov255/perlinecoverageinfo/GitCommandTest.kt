package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Real-repository tests for [GitCommand], [ChangedLinesAnalyzer] and the content comparison in
 * [CoverageLineMapper] (review items M7/L7/L8).
 *
 * A temporary git repository is used instead of a mocked process: the point is exactly what the
 * git binary writes to its two pipes (`git show` content, a diff header, a quoted path).
 */
class GitCommandTest {

    private lateinit var repo: File

    @Before
    fun setUp() {
        assumeTrue("git is not installed", gitAvailable())
        repo = Files.createTempDirectory("git-command-").toFile()
        git("init", "-q", "-b", "main")
        git("config", "user.email", "coverage" + "@" + "test.invalid")
        git("config", "user.name", "Coverage Test")
        git("config", "commit.gpgsign", "false")
        git("config", "core.autocrlf", "false")
    }

    @After
    fun tearDown() {
        GitCommand.timeoutMs = 30_000
        if (::repo.isInitialized) repo.deleteRecursively()
    }

    // --- L7: raw output of `git show` -------------------------------------------------

    @Test
    fun `raw show keeps leading blank lines and the trailing newline`() {
        val content = "\n\n<?php\nreturn 1;\n"
        write("src/Leading.php", content)
        commit("add a file with leading blank lines")

        assertEquals(content, GitCommand.runRaw(repo, "show", "HEAD:src/Leading.php"))
        // The trimmed variant still drops them: callers that want a one-line answer keep working.
        assertEquals("<?php\nreturn 1;", GitCommand.run(repo, "show", "HEAD:src/Leading.php"))
    }

    @Test
    fun `an empty file is an empty raw result, not a failure`() {
        write("src/Empty.php", "")
        commit("add an empty file")

        assertEquals("", GitCommand.runRaw(repo, "show", "HEAD:src/Empty.php"))
        assertNull(GitCommand.run(repo, "show", "HEAD:src/Empty.php"))
    }

    @Test
    fun `a failing git command returns null`() {
        write("src/Present.php", "<?php\n")
        commit("add a file")

        assertNull(GitCommand.runRaw(repo, "show", "HEAD:src/Missing.php"))
        assertNull(GitCommand.run(repo, "rev-parse", "no-such-revision"))
    }

    @Test
    fun `a hung git command is killed after the timeout`() {
        GitCommand.timeoutMs = 300

        val started = System.nanoTime()
        val output = GitCommand.run(repo, "-c", "alias.hang=!sleep 30", "hang")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNull(output)
        assertTrue("the call took ${elapsedMs} ms", elapsedMs < 10_000)
    }

    // --- L8: content comparison ------------------------------------------------------

    @Test
    fun `content that only differs by line separators needs no mapping`() {
        assertNull(CoverageLineMapper.computeMappingFromContent("a\nb\n", "a\r\nb\r\n"))
        assertNull(CoverageLineMapper.computeMappingFromContent("a\rb\r", "a\nb\n"))
        assertNull(CoverageLineMapper.computeMappingFromContent("\n\na\n", "\r\n\r\na\r\n"))
    }

    // --- M7: changed lines in a real repository ---------------------------------------

    @Test
    fun `working-tree changes are reported with the old line numbers`() {
        write("src/Foo.php", "<?php\n\nreturn 1;\n")
        write("src/Bar.php", "<?php\nreturn 2;\n")
        commit("initial")
        val coverageCommit = git("rev-parse", "HEAD")

        write("src/Foo.php", "<?php\n\nreturn 42;\n")

        val changes = ChangedLinesAnalyzer.analyze(repo, coverageCommit, ChangedLinesAnalyzer.DiffMode.WORKING_TREE)
        val foo = changes.single { it.newPath == "src/Foo.php" }

        assertEquals("src/Foo.php", foo.oldPath)
        assertEquals(setOf(3), foo.affectedOldLines)
    }

    @Test
    fun `a committed change is reported in committed mode`() {
        write("src/Foo.php", "<?php\nreturn 1;\n")
        commit("initial")
        val coverageCommit = git("rev-parse", "HEAD")

        write("src/Foo.php", "<?php\nreturn 1;\nreturn 2;\n")
        commit("second")

        val changes = ChangedLinesAnalyzer.analyze(repo, coverageCommit, ChangedLinesAnalyzer.DiffMode.COMMITTED)

        assertEquals(listOf("src/Foo.php"), changes.mapNotNull { it.newPath })
        assertTrue("the insertion point is affected: ${changes.single().affectedOldLines}", changes.single().affectedOldLines.contains(2))
    }

    @Test
    fun `a non-ASCII path comes back as UTF-8, not as a quoted escape`() {
        val path = "src/Файл.php"
        write(path, "<?php\nreturn 1;\n")
        commit("add a file with a non-ASCII name")
        val coverageCommit = git("rev-parse", "HEAD")

        write(path, "<?php\nreturn 2;\n")

        val changes = ChangedLinesAnalyzer.analyze(repo, coverageCommit, ChangedLinesAnalyzer.DiffMode.WORKING_TREE)

        assertEquals(listOf(path), changes.mapNotNull { it.newPath })
    }

    // --- helpers ---------------------------------------------------------------------

    private fun write(relativePath: String, content: String) {
        val file = File(repo, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun commit(message: String) {
        git("add", "-A")
        git("commit", "-q", "-m", message)
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repo)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("git ${args.joinToString(" ")} failed: $output", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals("git ${args.joinToString(" ")} failed: $output", 0, process.exitValue())
        return output.trim()
    }

    private fun gitAvailable(): Boolean = try {
        val process = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Exception) {
        false
    }
}
