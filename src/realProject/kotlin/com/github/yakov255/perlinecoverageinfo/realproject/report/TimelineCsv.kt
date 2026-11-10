package com.github.yakov255.perlinecoverageinfo.realproject.report

/**
 * `timeline.csv`: one chronological row per notable event, so a run can be inspected without the
 * perf log. Columns are fixed: `tMs,iso,source,scenario,event,commit,state,detail`.
 */
internal object TimelineCsv {

    private data class Row(
        val tMs: Long,
        val source: String,
        val scenario: String,
        val event: String,
        val commit: String,
        val state: String,
        val detail: String,
    )

    fun build(data: RunData): String {
        val rows = ArrayList<Row>()
        data.scenarioEvents.filter { it.tMs != null }.forEach { event ->
            rows += Row(
                tMs = event.tMs!!,
                source = "scenario",
                scenario = event.scenario,
                event = event.phase,
                commit = "",
                state = "",
                detail = listOf(event.msg, event.dataText).filter { it.isNotBlank() }.joinToString(" "),
            )
        }
        data.loads.forEach { load ->
            rows += Row(
                tMs = load.tMs,
                source = "load",
                scenario = load.scenario,
                event = load.kind,
                commit = load.shortCommit,
                state = load.state,
                detail = buildString {
                    append("totalMs=").append(Fmt.ms(load.totalMs))
                    append(" cached=").append(Fmt.yesNo(load.cached))
                    append(" replaced=").append(Fmt.yesNo(load.replaced))
                    if (load.code != null) append(" code=").append(load.code)
                },
            )
        }
        data.spawns.forEach { spawn ->
            rows += Row(
                tMs = spawn.tMs,
                source = "spawn",
                scenario = "",
                event = spawn.str("how").orEmpty(),
                commit = "",
                state = "",
                detail = "pid=${spawn.long("pid") ?: "—"} instance=${spawn.str("instance") ?: "—"}",
            )
        }
        data.proxy.rows.forEach { row ->
            val failedStatus = row.status != null && row.status >= 400
            if (!failedStatus && row.fault == null) return@forEach
            rows += Row(
                tMs = row.tMs,
                source = "proxy-error",
                scenario = "",
                event = row.fault ?: "status",
                commit = "",
                state = row.status?.toString().orEmpty(),
                detail = "${row.method} ${row.path}",
            )
        }

        // Snapshot state changes only: the bridge writes one snapshot per second and the CSV
        // would otherwise be dominated by identical rows.
        var previousHash: Pair<String?, String?>? = null
        data.snapshots.forEach { snapshot ->
            val hash = snapshot.state to snapshot.commit
            if (hash != previousHash) {
                previousHash = hash
                rows += Row(
                    tMs = snapshot.tMs,
                    source = "snapshot",
                    scenario = "",
                    event = "state-change",
                    commit = snapshot.commit.orEmpty().take(8),
                    state = snapshot.state.orEmpty(),
                    detail = buildString {
                        append("stale=").append(Fmt.yesNo(snapshot.stale))
                        append(" loading=").append(Fmt.yesNo(snapshot.loading))
                        if (!snapshot.lastError.isNullOrBlank()) append(" lastError=").append(shorten(snapshot.lastError))
                    },
                )
            }
        }

        val sorted = rows.sortedWith(compareBy({ it.tMs }, { it.source }))
        return buildString {
            appendLine("tMs,iso,source,scenario,event,commit,state,detail")
            for (row in sorted) {
                appendLine(
                    listOf(
                        row.tMs.toString(),
                        Fmt.iso(row.tMs),
                        row.source,
                        row.scenario,
                        row.event,
                        row.commit,
                        row.state,
                        row.detail,
                    ).joinToString(",") { escape(it) }
                )
            }
        }
    }

    private fun escape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return '"' + value.replace("\"", "\"\"") + '"'
    }
}
