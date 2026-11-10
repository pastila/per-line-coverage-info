package com.github.yakov255.perlinecoverageinfo

import java.io.File

/**
 * Thin wrapper around the local `git` binary for the few read-only queries the IDE still
 * needs: HEAD, recent commits, merge-base and the commits-behind counters.
 */
object GitCommand {

    private val log = CoverageLog.get(GitCommand::class.java)

    fun run(gitRoot: File, vararg args: String): String? {
        return try {
            val process = ProcessBuilder("git", *args)
                .directory(gitRoot)
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val errorOutput = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotEmpty()) {
                output
            } else {
                log.warn("GitCommand: 'git ${args.joinToString(" ")}' failed (exit=$exitCode): $errorOutput")
                null
            }
        } catch (e: Exception) {
            log.warn("GitCommand: git command failed: ${e.message}")
            null
        }
    }
}
