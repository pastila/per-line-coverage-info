package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JPanel

/**
 * Tool window panel that shows this plugin's own log output live, by
 * subscribing to [CoverageLogService]. Implemented as a thin wrapper around
 * IntelliJ's [ConsoleView] so we get scrollback, search, copy, and
 * level-colored output for free.
 */
class CoverageLogPanel(project: Project) : JPanel(BorderLayout()), Disposable {

    private val consoleView: ConsoleView =
        TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val timestampFormat = SimpleDateFormat("HH:mm:ss.SSS")

    init {
        Disposer.register(this, consoleView)

        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            DefaultActionGroup(ClearLogAction(), ScrollToEndAction()),
            /* horizontal = */ false,
        )
        toolbar.targetComponent = consoleView.component

        add(toolbar.component, BorderLayout.WEST)
        add(consoleView.component, BorderLayout.CENTER)

        // Backfill + live subscription, atomic so no entries are lost or duplicated.
        // Both backfill and live entries go through invokeLater so ConsoleView is
        // fully laid out before we print — printing into a not-yet-shown ConsoleView
        // can silently discard output (startup logs in particular are at risk because
        // the panel may be constructed while the component tree is still being built).
        val backfill = CoverageLogService.getInstance().subscribe(this) { entry ->
            ApplicationManager.getApplication().invokeLater { renderEntry(entry) }
        }
        ApplicationManager.getApplication().invokeLater {
            for (entry in backfill) renderEntry(entry)
        }
    }

    private fun renderEntry(entry: CoverageLogService.LogEntry) {
        val ts = timestampFormat.format(Date(entry.timestampMs))
        val line = "$ts [${entry.level}] ${entry.loggerName}: ${entry.message}\n"
        consoleView.print(line, contentTypeFor(entry.level))
        entry.throwable?.let { throwable ->
            consoleView.print(
                stackTraceToString(throwable) + "\n",
                ConsoleViewContentType.LOG_ERROR_OUTPUT,
            )
        }
    }

    private fun contentTypeFor(level: CoverageLogService.Level): ConsoleViewContentType = when (level) {
        CoverageLogService.Level.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
        CoverageLogService.Level.INFO -> ConsoleViewContentType.LOG_INFO_OUTPUT
        CoverageLogService.Level.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT
        CoverageLogService.Level.ERROR -> ConsoleViewContentType.LOG_ERROR_OUTPUT
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    override fun dispose() {
        // consoleView is disposed via the Disposer chain registered in init.
    }

    private inner class ClearLogAction : AnAction("Clear Log", "Clear plugin log buffer", AllIcons.Actions.GC) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            CoverageLogService.getInstance().clear()
            consoleView.clear()
        }
    }

    private inner class ScrollToEndAction :
        AnAction("Scroll to End", "Scroll to the most recent log entry", AllIcons.RunConfigurations.Scroll_down) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            consoleView.scrollTo(consoleView.contentSize)
        }
    }
}
