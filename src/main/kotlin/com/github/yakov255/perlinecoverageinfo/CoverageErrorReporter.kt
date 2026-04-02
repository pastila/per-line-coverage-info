package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.diagnostic.SubmittedReportInfo.SubmissionStatus
import com.intellij.util.Consumer
import java.awt.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class CoverageErrorReporter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = "Report to Developer"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>
    ): Boolean {
        val event = events.firstOrNull()
        val throwable = event?.throwable
        val message = event?.message ?: throwable?.message ?: "Unknown error"

        val stackTrace = throwable?.stackTraceToString() ?: ""

        val body = buildString {
            appendLine("## Bug Report")
            appendLine()
            appendLine("**Error:** $message")
            if (!additionalInfo.isNullOrBlank()) {
                appendLine()
                appendLine("**Additional info:** $additionalInfo")
            }
            if (stackTrace.isNotBlank()) {
                appendLine()
                appendLine("**Stack trace:**")
                appendLine("```")
                appendLine(stackTrace.take(3000))
                appendLine("```")
            }
        }

        val encodedBody = URLEncoder.encode(body, StandardCharsets.UTF_8)
        val encodedTitle = URLEncoder.encode("[Bug] $message".take(100), StandardCharsets.UTF_8)
        val url = "https://github.com/yakov255/per-line-coverage-info/issues/new" +
                "?title=$encodedTitle&body=$encodedBody&labels=bug"

        BrowserUtil.browse(url)
        consumer.consume(SubmittedReportInfo(SubmissionStatus.NEW_ISSUE))
        return true
    }
}
