package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
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
    private val affectedFilesPane = AffectedFilesPane(project, ::onAffectedFilesCheckedChanged)
    private val statusLabel = JBLabel("Click \"Find HEAD\" or \"Find Working Tree\" to start.")

    private var affectedModel: AffectedTestsModel? = null
    private var lastDiffMode: ChangedLinesAnalyzer.DiffMode? = null

    init {
        val findHeadAction = object : AnAction(
            "Find HEAD", "Find tests affected by committed changes", AllIcons.Vcs.Branch
        ) {
            override fun actionPerformed(e: AnActionEvent) =
                findAffectedTests(ChangedLinesAnalyzer.DiffMode.COMMITTED)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = CoverageDataService.getInstance(project).hasData()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val findWtAction = object : AnAction(
            "Find Working Tree", "Find tests affected by all local changes", AllIcons.Vcs.Changelist
        ) {
            override fun actionPerformed(e: AnActionEvent) =
                findAffectedTests(ChangedLinesAnalyzer.DiffMode.WORKING_TREE)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = CoverageDataService.getInstance(project).hasData()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val refreshAction = object : AnAction(
            "Refresh", "Re-compute affected tests", AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val mode = lastDiffMode ?: return
                findAffectedTests(mode)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = lastDiffMode != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val toggleViewAction = object : ToggleAction(
            "Tree View", "Toggle between tree and flat test list", AllIcons.Actions.GroupByPackage
        ) {
            override fun isSelected(e: AnActionEvent): Boolean = testTree.isTreeView
            override fun setSelected(e: AnActionEvent, state: Boolean) { testTree.isTreeView = state }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val runAllAction = object : AnAction(
            "Run All", "Run all Behat tests in a single launch", AllIcons.Actions.RunAll
        ) {
            override fun actionPerformed(e: AnActionEvent) = testTree.runAllBehatBundled(debug = false)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !testTree.runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    testTree.hasAnyBehatTest()
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val runAllDebugAction = object : AnAction(
            "Run All With Debug",
            "Debug all Behat tests in a single launch",
            AllIcons.Actions.StartDebugger
        ) {
            override fun actionPerformed(e: AnActionEvent) = testTree.runAllBehatBundled(debug = true)
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !testTree.runAllInProgress &&
                    BehatTestRunner.isAvailable() &&
                    testTree.hasAnyBehatTest()
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val removeSelectedAction = object : AnAction(
            "Remove Selected", "Remove selected tests from the list", AllIcons.General.Remove
        ) {
            override fun actionPerformed(e: AnActionEvent) = removeSelectedTests()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = affectedModel != null && testTree.selectionCount() > 0
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "AffectedTestsPanelToolbar",
            DefaultActionGroup(
                findHeadAction, findWtAction, refreshAction,
                Separator.getInstance(),
                toggleViewAction,
                Separator.getInstance(),
                runAllAction, runAllDebugAction, removeSelectedAction,
            ),
            true
        )
        toolbar.targetComponent = this

        statusLabel.border = JBUI.Borders.empty(4, 6)

        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.WEST)
        top.add(statusLabel, BorderLayout.CENTER)

        val splitter = OnePixelSplitter(false, 0.35f)
        splitter.firstComponent = affectedFilesPane.component
        splitter.secondComponent = testTree.component

        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        testTree.setEmptyMessage("No affected tests")

        registerKeyboardAction(
            { lastDiffMode?.let { findAffectedTests(it) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
        )
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

        val modeStr = if (result.mode == ChangedLinesAnalyzer.DiffMode.COMMITTED) "HEAD" else "Working Tree"
        val parts = mutableListOf("${model.displayedTests.size} tests, ${model.allFiles.size} files ($modeStr)")
        if (result.newFiles.isNotEmpty()) parts += "${result.newFiles.size} new"
        if (result.deletedFiles.isNotEmpty()) parts += "${result.deletedFiles.size} deleted"
        if (result.filesWithoutCoverage.isNotEmpty()) parts += "${result.filesWithoutCoverage.size} no coverage"
        statusLabel.text = parts.joinToString(" · ")

        affectedFilesPane.populate(model)
        updateTestsFromModel()

        log.info("Affected tests populated: ${model.displayedTests.size} tests across ${model.allFiles.size} files")
    }

    private fun updateTestsFromModel() {
        val model = affectedModel ?: return
        val displayed = model.displayedTests
        val modeStr = if (lastDiffMode == ChangedLinesAnalyzer.DiffMode.COMMITTED) "HEAD" else "Working Tree"
        statusLabel.text = "${displayed.size} tests, ${model.allFiles.size} files ($modeStr)"
        testTree.setTests(displayed.toList())
    }

    private fun removeSelectedTests() {
        val model = affectedModel ?: return
        val testsToRemove = testTree.collectSelectedTestNames()
        if (testsToRemove.isEmpty()) return
        model.removeTests(testsToRemove)
        updateTestsFromModel()
        affectedFilesPane.filesTree.repaint()
    }

    companion object {
        const val TAB_TITLE = "Affected by Changes"
    }
}
