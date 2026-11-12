package com.github.yakov255.perlinecoverageinfo

import java.io.File
import java.io.RandomAccessFile

/**
 * Parser for the UI-test command file (`-Dcoverage.uiTestCommands=<file>`).
 *
 * The bridge polls the file every 250 ms and runs every line exactly once. [readNew] returns
 * the complete lines after a byte offset and the offset to continue from, so a line is never
 * executed twice; a partially written line waits for its newline.
 */
internal object CoverageUiTestCommands {

    enum class Kind { RELOAD, SETTINGS_TOKEN, SETTINGS_APPLY }

    data class Command(val kind: Kind, val value: String? = null)

    /** One command line, or null when the line is unknown or blank. */
    fun parse(line: String): Command? = when (val trimmed = line.trim()) {
        "" -> null
        "reload" -> Command(Kind.RELOAD)
        "settings.apply" -> Command(Kind.SETTINGS_APPLY)
        else -> if (trimmed.startsWith(TOKEN_PREFIX)) {
            Command(Kind.SETTINGS_TOKEN, trimmed.removePrefix(TOKEN_PREFIX))
        } else {
            null
        }
    }

    /** The line as it may be logged or shown in the bridge: the token value is always masked. */
    fun displayName(line: String): String {
        val trimmed = line.trim()
        return if (trimmed.startsWith(TOKEN_PREFIX)) "$TOKEN_PREFIX***" else trimmed
    }

    /**
     * Reads the complete lines written after [offset].
     *
     * @return the lines (without the newline) and the offset after the last complete line.
     *   A file that shrank restarts from the beginning.
     */
    fun readNew(file: File, offset: Long): Pair<List<String>, Long> {
        if (!file.isFile) return emptyList<String>() to offset
        val length = file.length()
        if (length < offset) return readNew(file, 0L)
        if (length == offset) return emptyList<String>() to offset

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray((length - offset).toInt())
            raf.readFully(buffer)
            val lastNewline = buffer.lastIndexOf('\n'.code.toByte())
            if (lastNewline < 0) return emptyList<String>() to offset
            val consumed = lastNewline + 1
            val text = String(buffer, 0, consumed, Charsets.UTF_8)
            val lines = text.split('\n').dropLast(1).filter { it.isNotEmpty() }
            return lines to (offset + consumed)
        }
    }

    private const val TOKEN_PREFIX = "settings.token="
}
