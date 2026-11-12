package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import com.intellij.openapi.progress.ProcessCanceledException

/**
 * Thin wrapper around the local `git` binary for the few read-only queries the IDE still
 * needs: HEAD, recent commits, merge-base, `git show` and the commits-behind counters.
 *
 * Both output pipes are read on their own threads (a child that fills stderr must not deadlock
 * the reader) and every call has a timeout: a hung `git` is killed instead of hanging a pooled
 * thread of the IDE for ever.
 */
object GitCommand {

    private val log = CoverageLog.get(GitCommand::class.java)

    /** Test seam: production never changes it. */
    @Volatile
    internal var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    /** Runs git and returns the trimmed stdout, or null on a non-zero exit, empty output or failure. */
    fun run(gitRoot: File, vararg args: String): String? = execute(gitRoot, args.toList(), trim = true)

    /**
     * Runs git and returns stdout exactly as the child wrote it, without trimming.
     *
     * `git show` is a file-content read: leading blank lines and the final newline belong to the
     * content, so trimming them would make the result differ from the editor text even when the
     * file did not change. An empty file yields an empty string, not null; null means failure.
     */
    fun runRaw(gitRoot: File, vararg args: String): String? = execute(gitRoot, args.toList(), trim = false)

    private fun execute(gitRoot: File, args: List<String>, trim: Boolean): String? {
        var child: Process? = null
        return try {
            CoverageCancellation.check()
            val process = ProcessBuilder(listOf("git") + args)
                .directory(gitRoot)
                .redirectErrorStream(false)
                .start()
            child = process
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = drain(process.inputStream, stdout, "git-stdout")
            val errThread = drain(process.errorStream, stderr, "git-stderr")

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            var finished = false
            while (System.nanoTime() < deadline) {
                CoverageCancellation.check()
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
                outThread.join(1_000)
                errThread.join(1_000)
                log.warn("GitCommand: 'git ${args.joinToString(" ")}' timed out after $timeoutMs ms")
                return null
            }

            outThread.join(2_000)
            errThread.join(2_000)
            val exitCode = process.exitValue()
            val output = stdout.toString()
            if (exitCode == 0) {
                if (trim) output.trim().takeIf { it.isNotEmpty() } else output
            } else {
                log.warn("GitCommand: 'git ${args.joinToString(" ")}' failed (exit=$exitCode): ${stderr.toString().trim()}")
                null
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } catch (e: Exception) {
            log.warn("GitCommand: git command failed: ${e.message}")
            null
        } finally {
            child?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    private fun drain(stream: InputStream, sink: StringBuilder, name: String): Thread =
        Thread({
            try {
                stream.bufferedReader().use { reader ->
                    val buffer = CharArray(8 * 1024)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        sink.append(buffer, 0, read)
                    }
                }
            } catch (_: java.io.IOException) {
                // The process was killed (timeout) or exited: nothing left to read.
            }
        }, name).apply {
            isDaemon = true
            start()
        }

    private const val DEFAULT_TIMEOUT_MS = 30_000L
}
