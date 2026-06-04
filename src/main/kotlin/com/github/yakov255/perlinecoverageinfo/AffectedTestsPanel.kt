package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Tool-window tab showing tests affected by the user's local changes
 * relative to the coverage commit (HEAD diff or working-tree diff).
 */
class AffectedTestsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = CoverageLog.get(AffectedTestsPanel::class.java)

    private val testTree = TestTreeView(project)
    private val warningPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
    private val warningLabel = JBLabel()
    private val affectedFilesPane = AffectedFilesPane(
        project,
        onCheckedFilesChanged = ::onAffectedFilesCheckedChanged,
        onRefresh = ::refreshAction,
        isRefreshEnabled = { CoverageDataService.getInstance(project).hasData() || lastDiffMode != null },
    )

    private var affectedModel: AffectedTestsModel? = null
    private var lastDiffMode: ChangedLinesAnalyzer.DiffMode? = null

    init {
        val splitter = OnePixelSplitter(false, 0.35f)
        splitter.firstComponent = affectedFilesPane.component
        splitter.secondComponent = testTree.component

        warningPanel.add(JBLabel(AllIcons.General.Warning))
        warningPanel.add(warningLabel)
        warningLabel.foreground = Color(0xCC7832)
        warningPanel.isVisible = false

        add(warningPanel, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        testTree.setEmptyMessage("No affected tests")
        testTree.setStatusText("Click \"Refresh\" to start.")

        updateWarning()

        project.messageBus.connect().subscribe(
            CoverageDataService.COVERAGE_CHANGED_TOPIC,
            CoverageChangeListener {
                ApplicationManager.getApplication().invokeLater { updateWarning() }
            },
        )

        registerKeyboardAction(
            { lastDiffMode?.let { findAffectedTests(it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
        )
    }

    private fun refreshAction() {
        val mode = lastDiffMode ?: ChangedLinesAnalyzer.DiffMode.WORKING_TREE
        findAffectedTests(mode)
    }

    private fun updateWarning() {
        val warnings = CoverageWarningService.getInstance(project).getWarnings()
        if (warnings.hasAny) {
            warningLabel.text = "<html>${warnings.formatHtml()}</html>"
            warningPanel.isVisible = true
        } else {
            warningPanel.isVisible = false
        }
    }

    private fun onAffectedFilesCheckedChanged() {
        val model = affectedModel ?: return
        affectedFilesPane.syncModelFromTree(model)
        updateTestsFromModel()
    }

    private fun findAffectedTests(mode: ChangedLinesAnalyzer.DiffMode) {
        lastDiffMode = mode
        log.info("Finding affected tests (mode=$mode)")
        object : Task.Backgroundable(project, "Finding affected tests…", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = AffectedTestsService.getInstance(project).compute(mode)
                ApplicationManager.getApplication().invokeLater { populateAffected(result) }
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    val title = if (error is CoverageApiException) "Affected Tests" else "Error"
                    Messages.showErrorDialog(project, error.message ?: "Unknown error", title)
                }
            }
        }.queue()
    }

    private fun populateAffected(result: AffectedTestsService.AffectedTests) {
        val model = AffectedTestsModel(result.perFile.mapValues { (_, v) -> v })
        affectedModel = model

        affectedFilesPane.populate(model)
        updateTestsFromModel()

        log.info("Affected tests populated: ${model.displayedTests.size} tests across ${model.allFiles.size} files")
    }

    private fun updateTestsFromModel() {
        val model = affectedModel ?: return
        val displayed = model.displayedTests
        testTree.setTests(displayed.toList())
    }

    companion object {
        const val TAB_TITLE = "Affected by Changes"
    }
}
