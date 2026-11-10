package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * One leak finding. Deliberately carries no matched text: a report must be safe to share, so it
 * names the file, the 1-based line and the kind of secret, never the value.
 */
data class SecretFinding(val file: String, val line: Int, val type: String)

/**
 * Scans every text file under [dir] for credentials. Used by the report itself and by the
 * harness once a run is complete, so it never writes and never modifies anything.
 *
 * Findings have five kinds:
 *  - [TYPE_GITLAB_TOKEN] — the `glpat-` prefix, GitLab personal/project access token;
 *  - [TYPE_BEARER] — an `Authorization: Bearer <value>`-style token anywhere in the text;
 *  - [TYPE_AUTHORIZATION] — any other `Authorization: <value>` header, including JSON form;
 *  - [TYPE_PRIVATE_TOKEN] — a `PRIVATE-TOKEN: <value>` header;
 *  - [TYPE_SECRET_VALUE] — a literal value from [secrets].
 *
 * The bare words `token`, `authorization` or `Bearer` without a value are not findings.
 */
class SecretScanner(
    private val dir: File,
    private val secrets: List<String> = emptyList(),
    private val exclude: Set<String> = emptySet(),
) {

    companion object {
        const val TYPE_GITLAB_TOKEN = "gitlab-token"
        const val TYPE_BEARER = "bearer-token"
        const val TYPE_AUTHORIZATION = "authorization-header"
        const val TYPE_PRIVATE_TOKEN = "private-token-header"
        const val TYPE_SECRET_VALUE = "secret-value"

        /** Files larger than this are skipped: a single log never justifies an unbounded scan. */
        private const val MAX_FILE_BYTES = 256L * 1024 * 1024
        private const val BINARY_SNIFF_BYTES = 8 * 1024

        private val BEARER = Regex(
            """(?i)\bbearer\s+[A-Za-z0-9._~+/=\-]{4,}(?=\s*(?:"|'|,|;|\}|$))""",
        )
        private val AUTHORIZATION = Regex("""(?i)\bauthorization\b["']?\s*[:=]\s*["']?[A-Za-z0-9._~+/=\-]{3,}""")
        private val PRIVATE_TOKEN = Regex("""(?i)\bprivate-token\b["']?\s*[:=]\s*["']?[A-Za-z0-9._~+/=\-]{3,}""")
    }

    fun scan(): List<SecretFinding> {
        val files = when {
            dir.isFile -> listOf(dir)
            dir.isDirectory -> dir.walkTopDown()
                .filter { it.isFile }
                .toList()
                .sortedBy { it.path }
            else -> return emptyList()
        }
        val findings = ArrayList<SecretFinding>()
        for (file in files) {
            val relative = relativePath(file)
            if (relative in exclude) continue
            scanFile(file, relative, findings)
        }
        return findings.sortedWith(compareBy({ it.file }, { it.line }, { it.type }))
    }

    private fun relativePath(file: File): String {
        if (dir.isFile) return file.name
        return try {
            dir.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        } catch (_: IllegalArgumentException) {
            file.name
        }
    }

    private fun scanFile(file: File, relative: String, out: MutableList<SecretFinding>) {
        if (!file.canRead()) return
        if (file.length() > MAX_FILE_BYTES) return
        try {
            BufferedInputStream(file.inputStream(), 64 * 1024).use { stream ->
                stream.mark(BINARY_SNIFF_BYTES)
                val head = ByteArray(BINARY_SNIFF_BYTES)
                val read = stream.read(head)
                stream.reset()
                if (read > 0 && (0 until read).any { head[it] == 0.toByte() }) return

                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var lineNumber = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        lineNumber++
                        if (line.isEmpty()) continue
                        val types = lineTypes(line)
                        for (type in types) out += SecretFinding(relative, lineNumber, type)
                    }
                }
            }
        } catch (_: Exception) {
            // An unreadable or vanishing file must not break the report.
        }
    }

    /** Deduplicated per line so one header never produces two identical rows. */
    private fun lineTypes(line: String): List<String> {
        val types = LinkedHashSet<String>()
        if (line.contains("glpat-")) types += TYPE_GITLAB_TOKEN
        if (BEARER.containsMatchIn(line)) types += TYPE_BEARER
        if (AUTHORIZATION.containsMatchIn(line)) types += TYPE_AUTHORIZATION
        if (PRIVATE_TOKEN.containsMatchIn(line)) types += TYPE_PRIVATE_TOKEN
        for (secret in secrets) {
            if (secret.length >= 4 && line.contains(secret)) types += TYPE_SECRET_VALUE
        }
        return types.toList()
    }
}
