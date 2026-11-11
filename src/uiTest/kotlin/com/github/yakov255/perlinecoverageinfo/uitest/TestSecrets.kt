package com.github.yakov255.perlinecoverageinfo.uitest

import java.io.File

/**
 * The GitLab token for the scenario. It comes from `GITLAB_TOKEN_FILE` (preferred: a file only the
 * current user can read, so the token never sits in the process environment) or `GITLAB_TOKEN`.
 *
 * The token is typed into plain text fields, so anything that saves IDE state (a Swing hierarchy
 * dump) must go through [mask].
 */
object TestSecrets {

    val token: String by lazy {
        val fromFile = System.getenv("GITLAB_TOKEN_FILE")?.takeIf { it.isNotBlank() }
            ?.let { File(it).takeIf(File::isFile)?.readText()?.trim() }
        fromFile?.takeIf { it.isNotEmpty() }
            ?: System.getenv("GITLAB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Set GITLAB_TOKEN_FILE (path to a file with the token) or GITLAB_TOKEN")
    }

    /** Replaces every occurrence of the token in [text]; a no-op when no token is configured. */
    fun mask(text: String): String {
        val secret = runCatching { token }.getOrNull() ?: return text
        return text.replace(secret, "***")
    }
}
