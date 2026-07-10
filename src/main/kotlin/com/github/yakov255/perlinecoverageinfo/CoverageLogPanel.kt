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
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
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

    private val allEntries = mutableListOf<CoverageLogService.LogEntry>()
    private val componentNames = mutableSetOf<String>()
    private val levelNames = mutableSetOf<String>()

    private var selectedLevel: CoverageLogService.Level? = null
    private var selectedComponent: String? = null

    private val levelCombo = JComboBox<String>()
    private val componentCombo = JComboBox<String>()
    private var updatingCombo = false

    init {
        Disposer.register(this, consoleView)

        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        levelCombo.addItem("All Levels")
        levelCombo.addActionListener { onFilterChanged() }

        componentCombo.addItem("All Components")
        componentCombo.addActionListener { onFilterChanged() }

        filterPanel.add(JLabel("Level:"))
        filterPanel.add(levelCombo)
        filterPanel.add(Box.createHorizontalStrut(10))
        filterPanel.add(JLabel("Component:"))
        filterPanel.add(componentCombo)

        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            DefaultActionGroup(ClearLogAction(), ScrollToEndAction()),
            /* horizontal = */ false,
        )
        toolbar.targetComponent = consoleView.component

        add(filterPanel, BorderLayout.NORTH)
        add(toolbar.component, BorderLayout.WEST)
        add(consoleView.component, BorderLayout.CENTER)

        // Backfill is processed in bulk, then live entries arrive one by one.
        val backfill = CoverageLogService.getInstance().subscribe(this) { entry ->
            ApplicationManager.getApplication().invokeLater { onNewEntry(entry) }
        }
        ApplicationManager.getApplication().invokeLater {
            for (entry in backfill) {
                allEntries.add(entry)
                componentNames.add(entry.loggerName)
                levelNames.add(entry.level.name)
            }
            updateComponentCombo()
            updateLevelCombo()
            for (entry in allEntries) {
                if (matchesFilter(entry)) renderEntry(entry)
            }
        }
    }

    private fun onNewEntry(entry: CoverageLogService.LogEntry) {
        allEntries.add(entry)
        if (componentNames.add(entry.loggerName)) {
            updateComponentCombo()
        }
        if (levelNames.add(entry.level.name)) {
            updateLevelCombo()
        }
        if (matchesFilter(entry)) {
            renderEntry(entry)
        }
    }

    private fun matchesFilter(entry: CoverageLogService.LogEntry): Boolean {
        if (selectedLevel != null && entry.level != selectedLevel) return false
        if (selectedComponent != null && entry.loggerName != selectedComponent) return false
        return true
    }

    private fun onFilterChanged() {
        if (updatingCombo) return
        val levelItem = levelCombo.selectedItem as? String ?: return
        selectedLevel = if (levelItem == "All Levels") null
        else CoverageLogService.Level.valueOf(levelItem)

        val compItem = componentCombo.selectedItem as? String ?: return
        selectedComponent = if (compItem == "All Components") null else compItem

        consoleView.clear()
        for (entry in allEntries) {
            if (matchesFilter(entry)) renderEntry(entry)
        }
    }

    private fun updateComponentCombo() {
        updatingCombo = true
        try {
            val prevSelected = componentCombo.selectedItem as? String
            val model = DefaultComboBoxModel<String>()
            model.addElement("All Components")
            for (name in componentNames.sorted()) {
                model.addElement(name)
            }
            componentCombo.model = model
            if (prevSelected != null && (prevSelected == "All Components" || prevSelected in componentNames)) {
                componentCombo.selectedItem = prevSelected
            }
        } finally {
            updatingCombo = false
        }
    }

    private fun updateLevelCombo() {
        updatingCombo = true
        try {
            val prevSelected = levelCombo.selectedItem as? String
            val model = DefaultComboBoxModel<String>()
            model.addElement("All Levels")
            for (name in levelNames.sorted()) {
                model.addElement(name)
            }
            levelCombo.model = model
            if (prevSelected != null && (prevSelected == "All Levels" || prevSelected in levelNames)) {
                levelCombo.selectedItem = prevSelected
            }
        } finally {
            updatingCombo = false
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

    override fun dispose() {}

    private inner class ClearLogAction : AnAction("Clear Log", "Clear plugin log buffer", AllIcons.Actions.GC) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            CoverageLogService.getInstance().clear()
            allEntries.clear()
            componentNames.clear()
            levelNames.clear()
            componentCombo.model = DefaultComboBoxModel<String>().also { it.addElement("All Components") }
            levelCombo.model = DefaultComboBoxModel<String>().also { it.addElement("All Levels") }
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
