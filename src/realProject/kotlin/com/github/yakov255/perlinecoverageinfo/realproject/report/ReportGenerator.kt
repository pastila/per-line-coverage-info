package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Where the report was written and what it says, so a caller does not have to re-read files. */
data class ReportOutput(
    val reportFile: File,
    val summaryFile: File,
    val timelineFile: File,
    val summary: JsonObject,
    val secretFindings: List<SecretFinding>,
)

/**
 * Generates `report.md`, `summary.json` and `timeline.csv` from one run directory.
 *
 * Inputs (all optional, all defined in `docs/real-project-testing.md`):
 * `proxy.jsonl`, the perf logs, the `covd-responses` answers, `samples.csv`, `snapshots.jsonl`,
 * `scenarios.jsonl`. A missing or malformed source yields "нет данных", never a crash.
 *
 * [secrets] are literal values the scanner must look for; the report never prints a value, only
 * file, line and kind. The secret scan excludes the three files this generator writes, so a
 * second run does not report its own output.
 */
class ReportGenerator(
    private val runDir: File,
    private val secrets: List<String> = emptyList(),
) {

    fun generate(): ReportOutput {
        check(!runDir.isFile) { "runDir is a file, not a directory: ${runDir.absolutePath}" }
        runDir.mkdirs()

        val data = RunLoader.load(runDir)
        val analysis = Analysis(data)
        val generatedAtMs = System.currentTimeMillis()

        val findings = SecretScanner(
            dir = runDir,
            secrets = secrets,
            exclude = OUTPUT_FILES,
        ).scan()

        val timelineFile = File(runDir, TIMELINE_NAME)
        val reportFile = File(runDir, REPORT_NAME)
        val summaryFile = File(runDir, SUMMARY_NAME)

        timelineFile.writeText(TimelineCsv.build(data), Charsets.UTF_8)
        val summary = SummaryBuilder.build(runDir, analysis, findings, generatedAtMs)
        summaryFile.writeText(PRETTY.encodeToString(JsonElement.serializer(), summary), Charsets.UTF_8)
        reportFile.writeText(
            MarkdownReport.build(runDir, analysis, findings, generatedAtMs),
            Charsets.UTF_8,
        )

        return ReportOutput(reportFile, summaryFile, timelineFile, summary, findings)
    }

    private companion object {
        const val REPORT_NAME = "report.md"
        const val SUMMARY_NAME = "summary.json"
        const val TIMELINE_NAME = "timeline.csv"

        val OUTPUT_FILES = setOf(REPORT_NAME, SUMMARY_NAME, TIMELINE_NAME)

        val PRETTY: Json = Json { prettyPrint = true }
    }
}

/**
 * Manual run:
 *
 * ```
 * java -cp <plugin classes + kotlin stdlib + kotlinx-serialization-json> \
 *     com.github.yakov255.perlinecoverageinfo.realproject.report.ReportGeneratorKt <runDir>
 * ```
 *
 * No token is read from the environment or from arguments: the scanner covers the documented
 * credential shapes (`glpat-`, `Bearer`, `Authorization`) without any configured value.
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("usage: ReportGeneratorKt <runDir>")
        exitProcess(2)
    }
    val runDir = File(args[0])
    val output = try {
        ReportGenerator(runDir).generate()
    } catch (e: Exception) {
        System.err.println("report generation failed: ${e.message}")
        exitProcess(1)
    }
    println("report:   ${output.reportFile.absolutePath}")
    println("summary:  ${output.summaryFile.absolutePath}")
    println("timeline: ${output.timelineFile.absolutePath}")
    if (output.secretFindings.isNotEmpty()) {
        println("secret findings: ${output.secretFindings.size} (values are not printed)")
    }
}
