package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.GZIPInputStream
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

/**
 * Tool-window tab panel that lists all locally cached coverage artifacts and
 * provides buttons to fetch fresh coverage, load from a local file, or clear data.
 *
 * Shows an empty-state panel when no artifacts are cached, guiding the user
 * through initial setup.
 */
class CoverageArtifactsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = CoverageLog.get(CoverageArtifactsPanel::class.java)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
    private val tableModel = ArtifactsTableModel()
    private val table = JBTable(tableModel)

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    /** Shown at the bottom when the last fetch attempt produced an error. */
    private val statusLabel = JBLabel("", AllIcons.General.Warning, SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = Color(0xC97B00)
        isVisible = false
    }

    companion object {
        private const val CARD_TABLE = "table"
        private const val CARD_EMPTY = "empty"

        private const val DATE_COL = 0
        private const val COMMIT_COL = 1
        private const val PIPELINE_COL = 2
        private const val SIZE_COL = 3
        private const val FILES_COL = 4
        private const val COVERAGE_COL = 5
    }

    init {
        setupTable()

        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            DefaultActionGroup(FetchCoverageAction(), LoadFromFileAction(), DeleteArtifactAction()),
            /* horizontal = */ true,
        )
        toolbar.targetComponent = table

        cardPanel.add(JBScrollPane(table), CARD_TABLE)
        cardPanel.add(createEmptyStatePanel(), CARD_EMPTY)

        add(toolbar.component, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        refreshData()
    }

    /** Reloads the artifact list from the cache service and updates the table. */
    fun refreshData() {
        val entries = CoverageCacheService.getInstance(project).listArtifacts()
        log.info("Artifacts panel: loaded ${entries.size} cached artifact(s)")
        tableModel.setEntries(entries, dateFormat)

        if (entries.isEmpty()) {
            cardLayout.show(cardPanel, CARD_EMPTY)
        } else {
            cardLayout.show(cardPanel, CARD_TABLE)
        }
    }

    private fun createEmptyStatePanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.CENTER
            insets = JBUI.insets(4)
        }

        val heading = JBLabel("No coverage artifacts yet").apply {
            font = font.deriveFont(font.size2D + 4f)
        }
        panel.add(heading, gbc)

        gbc.gridy++
        gbc.insets = JBUI.insets(12, 4, 4, 4)
        val fetchButton = JButton("Fetch Coverage").apply {
            addActionListener { doFetchCoverage() }
        }
        panel.add(fetchButton, gbc)

        gbc.gridy++
        gbc.insets = JBUI.insets(8, 4, 4, 4)
        val loadFileLink = JBLabel("<html><a href=''>or load from a local file…</a></html>").apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    doLoadFromFile()
                }
            })
        }
        panel.add(loadFileLink, gbc)

        return panel
    }

    /** Shows or hides the inline status label at the bottom of the panel. */
    private fun showStatus(message: String?) {
        statusLabel.text = message ?: ""
        statusLabel.isVisible = message != null
    }

    private fun doFetchCoverage() {
        val loadService = CoverageLoadService.getInstance(project)
        val error = loadService.validateSettings()
        if (error != null) {
            Messages.showErrorDialog(project, error, "Configuration Error")
            return
        }
        showStatus(null)
        loadService.loadFromGitLab(
            showErrors = false,
            onComplete = { refreshData() },
            onError = { msg -> showStatus(msg) },
        )
    }

    private fun doLoadFromFile() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Select Coverage File")
            .withDescription("Choose a .covt or .covt.gz binary coverage file")
            .withFileFilter { vf ->
                vf.name.endsWith(".covt") || vf.name.endsWith(".covt.gz")
            }

        val virtualFile = FileChooser.chooseFile(descriptor, project, null) ?: return
        val file = File(virtualFile.path)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val bytes = if (file.name.endsWith(".covt.gz")) {
                    GZIPInputStream(file.inputStream()).use { it.readBytes() }
                } else {
                    file.readBytes()
                }

                val coverage = BinaryCoverageParser.parseCovtBytes(bytes)
                log.info("Coverage: parsed ${coverage.size} files from local file ${file.name}")

                val gitRoot = findGitRoot(file.parentFile)
                val dataService = CoverageDataService.getInstance(project)
                if (gitRoot != null) {
                    dataService.setCoverageContext("local", gitRoot)
                }
                dataService.setCoverageAll(coverage)

                ApplicationManager.getApplication().invokeLater {
                    CoverageHighlighter.applyToOpenEditors(project)
                    showStatus(null)
                    refreshData()
                }
            } catch (ex: Exception) {
                log.warn("Failed to load local coverage file", ex)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to parse coverage file:\n${ex.message}",
                        "Coverage Error"
                    )
                }
            }
        }
    }

    private fun doClearCoverage() {
        CoverageDataService.getInstance(project).clear()
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project == project) {
                CoverageHighlighter.clearCoverageHighlighters(editor)
            }
        }
        refreshData()
    }

    private fun doDeleteArtifact() {
        val row = table.selectedRow
        if (row < 0) return
        val entry = tableModel.getEntry(row) ?: return

        val confirmed = Messages.showYesNoDialog(
            project,
            "Delete cached coverage artifact for commit ${entry.commitHash.take(8)} (#${entry.pipelineId})?",
            "Delete Artifact",
            Messages.getQuestionIcon(),
        )
        if (confirmed != Messages.YES) return

        val isActive = CoverageDataService.getInstance(project).coverageCommitHash == entry.commitHash
        val deleted = CoverageCacheService.getInstance(project).deleteArtifact(entry.commitHash)

        if (deleted && isActive) {
            doClearCoverage()
        } else {
            refreshData()
        }
    }

    private fun findGitRoot(dir: File?): File? {
        var current = dir
        while (current != null) {
            if (File(current, ".git").exists()) return current
            current = current.parentFile
        }
        return null
    }

    private fun setupTable() {
        table.isStriped = true
        table.setShowGrid(false)
        table.intercellSpacing = Dimension(0, 0)
        table.tableHeader.reorderingAllowed = false

        // Mouse adapter handles right-click context menus on commit and pipeline columns.
        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) handleContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) handleContextMenu(e)
            }

            private fun handleContextMenu(e: MouseEvent) {
                val col = table.columnAtPoint(e.point)
                val row = table.rowAtPoint(e.point)
                if (row < 0) return
                table.setRowSelectionInterval(row, row)
                val entry = tableModel.getEntry(row) ?: return
                when (col) {
                    COMMIT_COL -> showCommitContextMenu(entry, e)
                    PIPELINE_COL -> showPipelineContextMenu(entry, e)
                }
            }
        }
        table.addMouseListener(mouseAdapter)

        // Preferred column widths.
        table.columnModel.getColumn(DATE_COL).preferredWidth = 140
        table.columnModel.getColumn(COMMIT_COL).preferredWidth = 80
        table.columnModel.getColumn(PIPELINE_COL).preferredWidth = 80
        table.columnModel.getColumn(SIZE_COL).preferredWidth = 75
        table.columnModel.getColumn(FILES_COL).preferredWidth = 55
        table.columnModel.getColumn(COVERAGE_COL).preferredWidth = 90
    }

    private fun openPipelineUrl(entry: ArtifactInfo): String? {
        val settings = CoverageApiSettings.getInstance()
        if (settings.gitlabProjectName.isBlank()) {
            log.warn("Artifacts panel: GitLab project name not configured, cannot build pipeline URL")
            return null
        }
        return "${settings.gitlabBaseUrl}/${settings.gitlabProjectName}/-/pipelines/${entry.pipelineId}"
    }

    private fun showPipelineContextMenu(entry: ArtifactInfo, e: MouseEvent) {
        val group = DefaultActionGroup()
        group.add(OpenInBrowserAction(entry))
        val popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.UNKNOWN, group)
        popup.component.show(e.component, e.x, e.y)
    }

    private inner class OpenInBrowserAction(private val entry: ArtifactInfo) :
        AnAction("Open in Browser", "Open this pipeline in the browser", AllIcons.Ide.External_link_arrow) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val url = openPipelineUrl(entry) ?: return
            BrowserUtil.browse(url)
        }
    }

    private inner class FetchCoverageAction :
        AnAction("Fetch Coverage", "Download new coverage from GitLab for the current commit", AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            doFetchCoverage()
        }
    }

    private inner class LoadFromFileAction :
        AnAction("Load from File", "Load coverage from a local .covt or .covt.gz file", AllIcons.Actions.MenuOpen) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            doLoadFromFile()
        }
    }

    private inner class DeleteArtifactAction :
        AnAction("Delete Artifact", "Delete the selected cached artifact from disk", AllIcons.General.Remove) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRow >= 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            doDeleteArtifact()
        }
    }

    private fun showCommitContextMenu(entry: ArtifactInfo, e: MouseEvent) {
        val group = DefaultActionGroup()
        group.add(CopyHashAction(entry.commitHash))
        val popup = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.UNKNOWN, group)
        popup.component.show(e.component, e.x, e.y)
    }

    private inner class CopyHashAction(private val hash: String) :
        AnAction("Copy Hash", "Copy full commit hash to clipboard", AllIcons.Actions.Copy) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val selection = StringSelection(hash)
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        }
    }
}

private class ArtifactsTableModel : AbstractTableModel() {

    private data class Row(
        val date: String,
        val commit: String,
        val pipeline: String,
        val size: String,
        val files: String,
        val coverage: String,
        val entry: ArtifactInfo,
    )

    private var rows: List<Row> = emptyList()

    fun setEntries(entries: List<ArtifactInfo>, dateFormat: SimpleDateFormat) {
        rows = entries.map { entry ->
            Row(
                date = dateFormat.format(Date(entry.timestampMs)),
                commit = entry.commitHash.take(8),
                pipeline = "#${entry.pipelineId}",
                size = "%.2f MB".format(entry.fileSizeBytes / 1_000_000.0),
                files = if (entry.totalFiles > 0) entry.totalFiles.toString() else "—",
                coverage = entry.coveragePercent?.let { "%.1f%%".format(it) } ?: "—",
                entry = entry,
            )
        }
        fireTableDataChanged()
    }

    fun getEntry(row: Int): ArtifactInfo? = rows.getOrNull(row)?.entry

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = 6

    override fun getValueAt(row: Int, col: Int): Any {
        val r = rows[row]
        return when (col) {
            0 -> r.date
            1 -> r.commit
            2 -> r.pipeline
            3 -> r.size
            4 -> r.files
            5 -> r.coverage
            else -> ""
        }
    }

    override fun getColumnName(col: Int): String = when (col) {
        0 -> "Date"
        1 -> "Commit"
        2 -> "Pipeline"
        3 -> "Size"
        4 -> "Files"
        5 -> "Coverage"
        else -> ""
    }

    override fun isCellEditable(row: Int, col: Int): Boolean = false
}
