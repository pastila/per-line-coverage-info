package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
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
import javax.swing.JButton
import java.io.File

/**
 * Tool-window tab showing tests affected by the user's local changes
 * relative to the coverage commit (HEAD diff or working-tree diff).
 */
class AffectedTestsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = CoverageLog.get(AffectedTestsPanel::class.java)

    /** True after [dispose]: a late background answer must not touch Swing. */
    @Volatile
    private var disposed = false

    private val testTree = TestTreeView(project)
    private val warningPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
    private val warningLabel = JBLabel()
    private val selectionLabel = JBLabel()
    private val removedButton = JButton()
    private val commitWarning = JBLabel()
    private val affectedFilesPane = AffectedFilesPane(
        project,
        onCheckedFilesChanged = ::onAffectedFilesCheckedChanged,
        onRefresh = ::refreshAction,
        isRefreshEnabled = { CoverageDataService.getInstance(project).hasData() || lastDiffMode != null || lastCommits != null },
    )

    private var affectedModel: AffectedTestsModel? = null
    private var lastDiffMode: ChangedLinesAnalyzer.DiffMode? = null
    private var lastCommits: Pair<File, List<String>>? = null
    private var commitResult: CommitAffectedTests.Result? = null
    private var requestTicket = 0L
    private var testSourceRevision: String? = null
    private var testRoot: File? = null

    init {
        val splitter = OnePixelSplitter(false, 0.35f)
        splitter.firstComponent = affectedFilesPane.component
        splitter.secondComponent = testTree.component

        warningPanel.add(JBLabel(AllIcons.General.Warning))
        warningPanel.add(warningLabel)
        warningLabel.foreground = Color(0xCC7832)
        warningPanel.isVisible = false

        val header = JPanel().apply { layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS) }
        val selectionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        selectionPanel.add(JButton("Find HEAD").apply {
            addActionListener { findAffectedTests(ChangedLinesAnalyzer.DiffMode.COMMITTED) }
        })
        selectionPanel.add(JButton("Find Working Tree").apply {
            addActionListener { findAffectedTests(ChangedLinesAnalyzer.DiffMode.WORKING_TREE) }
        })
        selectionPanel.add(removedButton)
        removedButton.isVisible = false
        removedButton.addActionListener {
            val removed = commitResult?.removedTests.orEmpty()
            Messages.showInfoMessage(project, removed.sorted().joinToString("\n") { name ->
                commitResult?.unresolvedTests?.get(name)?.let { "$name — $it" } ?: name
            }, "Tests Unavailable at HEAD")
        }
        header.add(selectionPanel)
        header.add(selectionLabel)
        header.add(warningPanel)
        commitWarning.isVisible = false
        header.add(commitWarning)
        add(header, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        testTree.setEmptyMessage("No affected tests")
        testTree.setStatusText("Click \"Refresh\" to start.")

        updateWarning()

        project.messageBus.connect(this).subscribe(
            CoverageDataService.COVERAGE_CHANGED_TOPIC,
            CoverageChangeListener {
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !disposed) updateWarning()
                }
            },
        )

        registerKeyboardAction(
            { refreshAction() },
            KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
        )
    }

    /** The tool-window content registers this panel as its disposable; the bus connection ends with it. */
    override fun dispose() {
        disposed = true
        com.intellij.openapi.util.Disposer.dispose(testTree)
    }

    private fun refreshAction() {
        lastCommits?.let { (root, commits) -> showCommits(root, commits); return }
        val mode = lastDiffMode ?: ChangedLinesAnalyzer.DiffMode.WORKING_TREE
        findAffectedTests(mode)
    }

    internal fun showCommits(root: File, commits: List<String>) {
        lastCommits = root to commits.toList()
        lastDiffMode = null
        updateWarning()
        commitResult = null
        affectedModel = AffectedTestsModel(emptyMap())
        affectedFilesPane.populate(affectedModel!!)
        testTree.setEmptyMessage("Finding affected tests…")
        testTree.setTests(emptyList())
        removedButton.isVisible = false
        commitWarning.isVisible = false
        selectionLabel.text = "${commitSummary(commits)} — finding tests…"
        selectionLabel.toolTipText = commits.joinToString(", ")
        val ticket = ++requestTicket
        object : Task.Backgroundable(project, "Finding tests affected by selected commits…", true) {
            override fun run(indicator: ProgressIndicator) {
                if (disposed) return
                val result = CoverageCancellation.withIndicator(indicator) {
                    AffectedTestsService.getInstance(project).computeCommits(root, commits)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || disposed || ticket != requestTicket) return@invokeLater
                    commitResult = result
                    testSourceRevision = result.head
                    testRoot = root
                    testTree.setEmptyMessage("No affected tests")
                    affectedModel = AffectedTestsModel(result.perFile)
                    affectedFilesPane.populate(affectedModel!!)
                    selectionLabel.text = commitSummary(result.commits)
                    removedButton.text = "Tests unavailable at HEAD (${result.removedTests.size})"
                    removedButton.isVisible = result.removedTests.isNotEmpty()
                    commitWarning.text = "<html>${result.warnings.joinToString("<br>")}</html>"
                    commitWarning.isVisible = result.warnings.isNotEmpty()
                    updateTestsFromModel()
                }
            }

            override fun onThrowable(error: Throwable) = showError(error, ticket)

            override fun onCancel() {
                if (!project.isDisposed && !disposed && ticket == requestTicket) {
                    selectionLabel.text = "Analysis canceled — Refresh to retry"
                    testTree.setEmptyMessage("Analysis canceled")
                }
            }
        }.queue()
    }

    private fun showError(error: Throwable, ticket: Long) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || disposed || ticket != requestTicket) return@invokeLater
            selectionLabel.text = "Analysis failed — Refresh to retry"
            testTree.setEmptyMessage("Analysis failed")
            Messages.showErrorDialog(project, error.message ?: "Unknown error", "Affected Tests")
        }
    }

    private fun commitSummary(commits: List<String>): String =
        "Selected commits (${commits.size}): ${commits.take(5).joinToString(", ") { it.take(8) }}" +
            if (commits.size > 5) " … (+${commits.size - 5})" else ""

    private fun updateWarning() {
        if (lastCommits != null) {
            warningPanel.isVisible = false
            return
        }
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
        lastCommits = null
        updateWarning()
        commitResult = null
        selectionLabel.text = "Changes relative to loaded coverage ($mode)"
        removedButton.isVisible = false
        commitWarning.isVisible = false
        val ticket = ++requestTicket
        lastDiffMode = mode
        log.info("Finding affected tests (mode=$mode)")
        object : Task.Backgroundable(project, "Finding affected tests…", true) {
            override fun run(indicator: ProgressIndicator) {
                if (disposed) return
                val result = CoverageCancellation.withIndicator(indicator) {
                    AffectedTestsService.getInstance(project).compute(mode)
                }
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed && !disposed && ticket == requestTicket) populateAffected(result)
                }
            }

            override fun onThrowable(error: Throwable) {
                showError(error, ticket)
            }
        }.queue()
    }

    private fun populateAffected(result: AffectedTestsService.AffectedTests) {
        testSourceRevision = result.sourceCommit
        testRoot = result.gitRoot
        testTree.setEmptyMessage("No affected tests")
        val model = AffectedTestsModel(result.perFile.mapValues { (_, v) -> v })
        affectedModel = model

        affectedFilesPane.populate(model)
        updateTestsFromModel()

        log.info("Affected tests populated: ${model.displayedTests.size} tests across ${model.allFiles.size} files")
    }

    private fun updateTestsFromModel() {
        val model = affectedModel ?: return
        val displayed = model.displayedTests
        val result = commitResult
        val annotations = result?.evidence?.mapValues { (_, items) ->
            items.map { it.reason.label }.distinct().joinToString(", ")
        }.orEmpty()
        val details = result?.evidence?.mapValues { (_, items) ->
            items.joinToString("; ") { "${it.reason.label}: ${it.commit.take(8)} (${it.file})" }
        }.orEmpty()
        testTree.setTests(displayed.toList(), annotations, details, testRoot,
            sourceRevisions = displayed.associateWith { setOf(testSourceRevision) })
    }

    companion object {
        const val TAB_TITLE = "Affected by Changes"
    }
}
