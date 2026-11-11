package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number/time formatting for the Markdown report; every formatter accepts a missing value. */
internal object Fmt {

    private const val DASH = "—"

    private val TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun ms(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.0f", it) } ?: DASH

    fun msLong(value: Long?): String = value?.toString() ?: DASH

    fun num(value: Double?, digits: Int = 1): String =
        value?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: DASH

    fun bytes(value: Long?): String = if (value == null) DASH else "${num(value / 1_048_576.0)} МБ"

    fun rate(value: Double?): String = value?.let { "${num(it, 2)} МБ/с" } ?: DASH

    fun commit(value: String?): String =
        value?.takeIf { it.isNotBlank() }?.take(8) ?: DASH

    fun time(tMs: Long?): String = tMs?.let { TIME.format(Instant.ofEpochMilli(it)) } ?: DASH

    fun iso(tMs: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(tMs))

    fun yesNo(value: Boolean?): String = when (value) {
        true -> "да"
        false -> "нет"
        null -> DASH
    }

    /** Escapes a Markdown table cell: a pipe or a line break would break the row. */
    fun cell(value: String?): String =
        value.orEmpty().replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ").replace("\r", " ")
}

/**
 * Renders a Markdown table, or the "нет данных" line when there is nothing to show. Headers and
 * cells are passed as plain strings; callers format values beforehand.
 */
internal fun mdTable(headers: List<String>, rows: List<List<String>>): String {
    if (rows.isEmpty()) return "нет данных\n"
    return buildString {
        append("| ").append(headers.joinToString(" | ") { Fmt.cell(it) }).append(" |\n")
        append("|").append(headers.joinToString("|") { "---" }).append("|\n")
        for (row in rows) {
            append("| ").append(row.joinToString(" | ") { Fmt.cell(it) }).append(" |\n")
        }
    }
}

/** Shortens long free-form text (event messages, data blobs) for a table cell. */
internal fun shorten(value: String?, limit: Int = 120): String {
    val text = value.orEmpty().trim()
    return if (text.length <= limit) text else text.take(limit - 1) + "…"
}
