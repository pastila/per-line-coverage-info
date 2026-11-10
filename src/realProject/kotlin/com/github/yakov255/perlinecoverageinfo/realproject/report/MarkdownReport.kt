package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File

/**
 * Builds `report.md`: one section per question the real-project runs must answer. The section
 * order follows the task: time, downloaded volume, memory, cache, daemon, branch switches,
 * failures — plus the secret-scan result. Every section degrades to "нет данных".
 */
internal object MarkdownReport {

    private const val MAX_ROWS = 200

    fun build(runDir: File, analysis: Analysis, findings: List<SecretFinding>, generatedAtMs: Long): String =
        buildString {
            val data = analysis.data
            appendLine("# Отчёт прогона на реальном проекте")
            appendLine()
            appendLine("- каталог: `${runDir.absolutePath}`")
            appendLine("- сформирован: ${Fmt.iso(generatedAtMs)}")
            appendLine(
                "- источники: perf-логов ${data.perfLogs.size} (строк ${data.perfLogs.sumOf { it.lines }}, " +
                    "битых ${data.malformedPerfLines}), записей прокси ${data.proxy.rows.size} " +
                    "(битых ${data.malformedProxyRows}), ответов covd ${data.covdResponses.size}, " +
                    "строк samples.csv ${data.samples.size}, снапшотов ${data.snapshots.size}, " +
                    "событий сценариев ${data.scenarioEvents.size}, загрузок ${data.loads.size}"
            )
            appendLine()
            appendLine("Привязка загрузки к сценарию — по времени: берётся последнее событие `scenarios.jsonl` до загрузки.")
            appendLine()

            sectionTiming(analysis)
            sectionDownload(analysis)
            sectionMemory(analysis)
            sectionCache(analysis)
            sectionDaemon(analysis)
            sectionSwitches(analysis)
            sectionFailures(analysis)
            sectionSecrets(findings)
        }

    // ---- 1 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionTiming(analysis: Analysis) {
        appendLine("## 1. Время загрузок")
        appendLine()
        val loads = analysis.data.loads
        if (loads.isEmpty()) {
            appendLine("нет данных")
            appendLine()
            return
        }
        appendLine("Загрузок: ${loads.size}.")
        appendLine()
        val rows = loads.map { load ->
            listOf(
                load.scenario.ifBlank { "—" },
                Fmt.commit(load.commit),
                load.state.ifBlank { "—" },
                Fmt.yesNo(load.cached),
                Fmt.ms(load.totalMs),
                Fmt.ms(load.resolveMs),
                Fmt.ms(load.downloadMs),
                Fmt.ms(load.parseMs),
                Fmt.ms(load.mergeMs),
                Fmt.ms(load.writeMs),
                Fmt.ms(load.baselineMs),
            )
        }
        append(
            mdTable(
                listOf(
                    "Сценарий", "Коммит", "State", "Cached", "totalMs", "resolve", "download",
                    "parse", "merge", "write", "baselineMs",
                ),
                capped(rows),
            )
        )
        appendLine()
        appendLine("### Медиана / min / max по повторам")
        appendLine()
        if (analysis.timingGroups.isEmpty()) {
            appendLine("нет данных")
            appendLine()
            return
        }
        append(
            mdTable(
                listOf("Сценарий", "Коммит", "Повторов", "Медиана totalMs", "min totalMs", "max totalMs", "Медиана downloadMs"),
                analysis.timingGroups.map { group ->
                    listOf(
                        group.scenario.ifBlank { "—" },
                        Fmt.commit(group.commit),
                        group.count.toString(),
                        Fmt.ms(group.medianTotalMs),
                        Fmt.ms(group.minTotalMs),
                        Fmt.ms(group.maxTotalMs),
                        Fmt.ms(Numbers.median(group.loads.mapNotNull { it.downloadMs })),
                    )
                },
            )
        )
        appendLine()
    }

    // ---- 2 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionDownload(analysis: Analysis) {
        appendLine("## 2. Объём скачанного")
        appendLine()
        val download = analysis.download
        if (!analysis.proxyPresent && !download.tracePresent && download.perfGitlabBytes == null) {
            appendLine("нет данных")
            appendLine()
            return
        }
        appendLine("### Журнал прокси")
        appendLine()
        if (!analysis.proxyPresent) {
            appendLine("нет данных (`proxy.jsonl` отсутствует)")
        } else {
            appendLine(
                "- запросов: ${download.requests}, скачано: ${Fmt.bytes(download.bytesDown)}, " +
                    "отправлено: ${Fmt.bytes(download.bytesUp)}"
            )
            appendLine("- артефактов (уникальных job id): ${download.artifacts}")
            appendLine("- ошибок по статусам: ${if (download.errorStatuses.isEmpty()) "нет" else download.errorStatuses.entries.joinToString { "${it.key}: ${it.value}" }}")
            appendLine("- срабатываний fault: ${if (download.faults.isEmpty()) "нет" else download.faults.entries.joinToString { "${it.key}: ${it.value}" }}")
            appendLine("- запросов с заголовком авторизации (значение не сохраняется): ${download.authSeenCount}")
            appendLine(
                "- скорость: ${Fmt.rate(download.mbPerSecond)} за окно " +
                    "${download.windowMs?.let { Fmt.ms(it.toDouble()) } ?: "—"} мс; " +
                    "эффективная по сумме задержек: ${Fmt.rate(download.effectiveMbPerSecond)}"
            )
            appendLine()
        }
        if (download.errorStatuses.isNotEmpty()) {
            append(
                mdTable(
                    listOf("Статус", "Запросов"),
                    download.errorStatuses.entries.sortedBy { it.key }.map { listOf(it.key.toString(), it.value.toString()) },
                )
            )
            appendLine()
        }
        if (download.topPaths.isNotEmpty()) {
            appendLine("Топ путей по объёму:")
            appendLine()
            append(
                mdTable(
                    listOf("Путь", "Скачано"),
                    download.topPaths.map { (path, bytes) -> listOf(path, Fmt.bytes(bytes)) },
                )
            )
            appendLine()
        }
        appendLine("### trace.gitlab[] из ответов covd")
        appendLine()
        if (!download.tracePresent) {
            appendLine("нет данных")
        } else {
            appendLine(
                "- вызовов: ${download.traceCalls}, скачано: ${Fmt.bytes(download.traceBytes)}, " +
                    "ошибок: ${download.traceErrors}"
            )
            val byMethod = analysis.data.gitlabTraceCalls
                .groupBy { it.stringOrNull("method") ?: "—" }
                .toSortedMap()
                .map { (method, calls) ->
                    listOf(
                        method,
                        calls.size.toString(),
                        Fmt.bytes(calls.sumOf { it.longOrNull("bytes") ?: 0L }),
                        calls.count { it.stringOrNull("error") != null || (it.intOrNull("status") ?: 0) >= 400 }.toString(),
                    )
                }
            append(mdTable(listOf("Метод", "Вызовов", "Байт", "Ошибок"), byMethod))
        }
        appendLine()
        appendLine(
            "Сводка по perf (`gitlabCalls`/`gitlabBytes`/`gitlabErrors`): " +
                "${Fmt.msLong(download.perfGitlabCalls)} / ${Fmt.bytes(download.perfGitlabBytes)} / " +
                "${Fmt.msLong(download.perfGitlabErrors)}."
        )
        appendLine()
    }

    // ---- 3 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionMemory(analysis: Analysis) {
        appendLine("## 3. Память")
        appendLine()
        val memory = analysis.memory
        if (memory.ideSamples == 0 && memory.covd.isEmpty()) {
            appendLine("нет данных")
            appendLine()
            return
        }
        appendLine("### IDE (ev=sample из perf-лога)")
        appendLine()
        if (memory.ideSamples == 0) {
            appendLine("нет данных")
        } else {
            append(
                mdTable(
                    listOf("Параметр", "Значение"),
                    listOf(
                        listOf("сэмплов (1 Гц)", memory.ideSamples.toString()),
                        listOf("heap used max", Fmt.bytes(memory.heapUsedMaxMb?.times(1_048_576.0)?.toLong())),
                        listOf("heap used avg", Fmt.bytes(memory.heapUsedAvgMb?.times(1_048_576.0)?.toLong())),
                        listOf("потолок heap (Xmx)", Fmt.bytes(memory.heapMaxMb?.times(1_048_576.0)?.toLong())),
                        listOf("GC всего", "${Fmt.msLong(memory.gcMs)} мс / ${Fmt.msLong(memory.gcCount)} сборок"),
                        listOf("потоков max", memory.threadsMax?.toString() ?: "—"),
                        listOf("CPU процесса max / avg", "${Fmt.num(memory.cpuMaxPct)}% / ${Fmt.num(memory.cpuAvgPct)}%"),
                        listOf("длительность наблюдения", "${Fmt.ms(memory.durationMs?.toDouble())} мс"),
                        listOf("пик heap", "${Fmt.time(memory.peakHeapAtMs)} (${Fmt.bytes(memory.heapUsedMaxMb?.times(1_048_576.0)?.toLong())})"),
                    ),
                )
            )
        }
        appendLine()
        appendLine("### covd")
        appendLine()
        if (memory.covd.isEmpty()) {
            appendLine("нет данных")
        } else {
            append(
                mdTable(
                    listOf("PID", "Источник", "Сэмплов", "RSS max", "RSS avg", "CPU max", "Потоков max", "Пик RSS"),
                    memory.covd.map { process ->
                        listOf(
                            process.pid?.toString() ?: "—",
                            process.source,
                            process.samples.toString(),
                            Fmt.bytes(process.rssMaxMb?.times(1_048_576.0)?.toLong()),
                            Fmt.bytes(process.rssAvgMb?.times(1_048_576.0)?.toLong()),
                            process.cpuMaxPct?.let { "${Fmt.num(it)}%" } ?: "—",
                            process.threadsMax?.toString() ?: "—",
                            Fmt.time(process.peakRssAtMs),
                        )
                    },
                )
            )
            appendLine()
            appendLine(
                "Пик RSS covd: ${Fmt.bytes(memory.covdPeakRssMb?.times(1_048_576.0)?.toLong())}; " +
                    "пик CPU covd: ${memory.covdPeakCpuPct?.let { "${Fmt.num(it)}%" } ?: "—"}."
            )
        }
        appendLine()
        appendLine("### Пики по фазам (привязка по времени, приближённо)")
        appendLine()
        if (memory.phasePeaks.isEmpty()) {
            appendLine("нет данных")
        } else {
            append(
                mdTable(
                    listOf("Время", "Процесс", "Метрика", "Значение", "Сценарий", "Коммит", "Фаза"),
                    memory.phasePeaks.map { peak ->
                        listOf(
                            Fmt.time(peak.tMs),
                            peak.proc,
                            peak.metric,
                            if (peak.metric == "rss" || peak.metric == "heap") {
                                Fmt.bytes(peak.value.times(1_048_576.0).toLong())
                            } else {
                                Fmt.num(peak.value)
                            },
                            peak.scenario.ifBlank { "—" },
                            Fmt.commit(peak.commit),
                            peak.phase.ifBlank { "вне загрузки" },
                        )
                    },
                )
            )
        }
        appendLine()
    }

    // ---- 4 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionCache(analysis: Analysis) {
        appendLine("## 4. Кэш")
        appendLine()
        val cache = analysis.cache
        if (cache.cachedLoads.isEmpty() && cache.replacedCount == 0 && cache.cacheBytes == null &&
            cache.artifactsOnDisk.isEmpty()
        ) {
            appendLine("нет данных")
            appendLine()
            return
        }
        appendLine("- загрузок из кэша (`cached=true`): ${cache.cachedLoads.size}")
        appendLine("- перезаписей кэша (`replaced=true`): ${cache.replacedCount}")
        appendLine(
            "- запросов к GitLab при повторе: ${Fmt.msLong(cache.repeatGitlabCalls)} " +
                "(ожидалось 0, нарушений: ${cache.repeatViolations.size})"
        )
        appendLine(
            "- размер кэша: " +
                (cache.cacheBytes?.let { Fmt.bytes(it) } ?: "нет данных (каталог кэша не найден)") +
                (cache.cov4Files?.let { ", файлов .cov4: $it" } ?: "")
        )
        if (cache.cacheDir != null) appendLine("- каталог кэша: `${cache.cacheDir.absolutePath}`")
        appendLine()
        if (cache.repeatViolations.isNotEmpty()) {
            append(
                mdTable(
                    listOf("Время", "Сценарий", "Коммит", "gitlabCalls"),
                    cache.repeatViolations.map {
                        listOf(Fmt.time(it.tMs), it.scenario.ifBlank { "—" }, Fmt.commit(it.commit), Fmt.msLong(it.gitlabCalls))
                    },
                )
            )
            appendLine()
        }
        if (cache.artifactsOnDisk.isNotEmpty()) {
            appendLine("Артефакты, на которые ссылаются ответы covd:")
            appendLine()
            append(
                mdTable(
                    listOf("Файл", "Размер"),
                    cache.artifactsOnDisk.map { (path, bytes) -> listOf(File(path).name, Fmt.bytes(bytes)) },
                )
            )
            appendLine()
        }
    }

    // ---- 5 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionDaemon(analysis: Analysis) {
        appendLine("## 5. Демон")
        appendLine()
        val daemon = analysis.daemon
        if (daemon.spawns.isEmpty() && daemon.pids.isEmpty() && daemon.instances.isEmpty()) {
            appendLine("нет данных")
            appendLine()
            return
        }
        if (daemon.counts.isNotEmpty()) {
            append(
                mdTable(
                    listOf("Как запущен", "Раз"),
                    daemon.counts.entries.sortedBy { it.key }.map { listOf(it.key, it.value.toString()) },
                )
            )
            appendLine()
        }
        appendLine("- уникальных pid: ${daemon.pids.size}${if (daemon.pids.isEmpty()) "" else " (${daemon.pids.joinToString()})"}")
        appendLine("- уникальных instanceId: ${daemon.instances.size}${if (daemon.instances.isEmpty()) "" else " (${daemon.instances.joinToString()})"}")
        appendLine("- загрузок с `joinedFlight`: ${daemon.joinedFlightLoads}")
        appendLine()
        if (daemon.spawns.isEmpty()) {
            appendLine("нет событий `covd.spawn`.")
            appendLine()
            return
        }
        append(
            mdTable(
                listOf("Время", "Как", "PID", "Порт", "Instance"),
                capped(
                    daemon.spawns.map { spawn ->
                        listOf(
                            Fmt.time(spawn.tMs),
                            spawn.str("how") ?: "—",
                            spawn.long("pid")?.toString() ?: "—",
                            spawn.long("port")?.toString() ?: "—",
                            spawn.str("instance") ?: "—",
                        )
                    }
                ),
            )
        )
        appendLine()
    }

    // ---- 6 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionSwitches(analysis: Analysis) {
        appendLine("## 6. Переключение веток")
        appendLine()
        val switches = analysis.switches
        if (switches.events.isEmpty()) {
            appendLine("нет данных (`scenarios.jsonl` отсутствует или пуст)")
            appendLine()
            return
        }
        appendLine("Событий сценариев: ${switches.events.size}, из них переключений: ${switches.switches.size}.")
        appendLine()
        appendLine("### Хронология")
        appendLine()
        append(
            mdTable(
                listOf("Время", "Сценарий", "Фаза", "Сообщение", "Данные"),
                capped(
                    switches.events.filter { it.tMs != null }.map { event ->
                        listOf(
                            Fmt.time(event.tMs),
                            event.scenario.ifBlank { "—" },
                            event.phase.ifBlank { "—" },
                            shorten(event.msg),
                            shorten(event.dataText),
                        )
                    }
                ),
            )
        )
        appendLine()
        appendLine("### Задержка до нового покрытия")
        appendLine()
        if (switches.switches.isEmpty()) {
            appendLine("нет данных (события переключения не распознаны)")
            appendLine()
            return
        }
        append(
            mdTable(
                listOf("Время события", "Сценарий", "Сообщение", "Новый коммит", "Задержка", "State"),
                switches.switches.map { detection ->
                    listOf(
                        Fmt.time(detection.event.tMs),
                        detection.event.scenario.ifBlank { "—" },
                        shorten(detection.event.msg),
                        Fmt.commit(detection.newLoad?.commit),
                        detection.delayMs?.let { Fmt.ms(it.toDouble()) } ?: "—",
                        detection.newLoad?.state?.ifBlank { "—" } ?: "нет загрузки",
                    )
                },
            )
        )
        appendLine()
    }

    // ---- 7 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionFailures(analysis: Analysis) {
        appendLine("## 7. Сбои")
        appendLine()
        val failures = analysis.failures
        val hasData = failures.errorLoads.isNotEmpty() || failures.spanFailures.isNotEmpty() ||
            failures.balloons.isNotEmpty() || failures.proxyStatusErrors.isNotEmpty() ||
            failures.lastErrors.isNotEmpty() || failures.traceErrors > 0
        if (!hasData) {
            appendLine("нет данных (ошибок не зафиксировано)")
            appendLine()
            return
        }
        if (failures.errorCodes.isNotEmpty()) {
            appendLine("Ошибки по кодам:")
            appendLine()
            append(
                mdTable(
                    listOf("Код", "Загрузок"),
                    failures.errorCodes.entries.sortedBy { it.key }.map { listOf(it.key, it.value.toString()) },
                )
            )
            appendLine()
        }
        if (failures.errorLoads.isNotEmpty()) {
            append(
                mdTable(
                    listOf("Время", "Сценарий", "Коммит", "Код", "HTTP", "State"),
                    failures.errorLoads.map {
                        listOf(
                            Fmt.time(it.tMs),
                            it.scenario.ifBlank { "—" },
                            Fmt.commit(it.commit),
                            it.code ?: "—",
                            it.httpStatus?.toString() ?: "—",
                            it.state.ifBlank { "—" },
                        )
                    },
                )
            )
            appendLine()
        }
        appendLine("- повторных попыток (загрузок сверх первой на сценарий+коммит): ${failures.retries}")
        appendLine("- ошибок в `trace.errors` ответов covd: ${failures.traceErrors}")
        appendLine()
        if (failures.balloons.isNotEmpty()) {
            appendLine("Баллуны:")
            appendLine()
            append(
                mdTable(
                    listOf("Время", "Сценарий", "Фаза", "Сообщение"),
                    failures.balloons.map {
                        listOf(Fmt.time(it.tMs), it.scenario.ifBlank { "—" }, it.phase.ifBlank { "—" }, shorten(it.msg))
                    },
                )
            )
            appendLine()
        }
        if (failures.spanFailures.isNotEmpty()) {
            appendLine("Неуспешные span-события perf-лога:")
            appendLine()
            append(
                mdTable(
                    listOf("Время", "Операция", "Ошибка", "Сообщение"),
                    failures.spanFailures.map {
                        listOf(
                            Fmt.time(it.tMs),
                            it.name.ifBlank { "—" },
                            it.str("error") ?: "—",
                            shorten(it.str("message")),
                        )
                    },
                )
            )
            appendLine()
        }
        if (failures.proxyStatusErrors.isNotEmpty()) {
            appendLine(
                "HTTP-ошибки прокси: " +
                    failures.proxyStatusErrors.entries.sortedBy { it.key }
                        .joinToString { "${it.key}: ${it.value}" } + "."
            )
            appendLine()
        }
        if (failures.lastErrors.isNotEmpty()) {
            appendLine("`lastError` из снапшотов:")
            appendLine()
            append(
                mdTable(
                    listOf("Время", "Коммит", "Ошибка"),
                    failures.lastErrors.map { listOf(Fmt.time(it.tMs), Fmt.commit(it.commit), shorten(it.lastError)) },
                )
            )
            appendLine()
        }
    }

    // ---- 8 ---------------------------------------------------------------------------------

    private fun StringBuilder.sectionSecrets(findings: List<SecretFinding>) {
        appendLine("## 8. Секреты")
        appendLine()
        if (findings.isEmpty()) {
            appendLine("находок нет")
            appendLine()
            return
        }
        appendLine("Найдено: ${findings.size}. Значения намеренно не печатаются.")
        appendLine()
        append(
            mdTable(
                listOf("Файл", "Строка", "Тип"),
                findings.map { listOf(it.file, it.line.toString(), it.type) },
            )
        )
        appendLine()
    }

    private fun capped(rows: List<List<String>>): List<List<String>> =
        if (rows.size <= MAX_ROWS) rows else rows.take(MAX_ROWS)
}
