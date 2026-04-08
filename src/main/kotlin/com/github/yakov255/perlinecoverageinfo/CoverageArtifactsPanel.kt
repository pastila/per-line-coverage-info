package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/**
 * Tool-window tab panel that lists all locally cached coverage artifacts and
 * provides a button to check / download fresh coverage for the current commit.
 *
 * Artifact metadata (date, pipeline link, size, file count, coverage %) is
 * read from [CoverageCacheService.listArtifacts] and refreshed whenever the
 * user clicks "Refresh Coverage" or when a load completes via the callback
 * passed to [CoverageLoadService.loadFromGitLab].
 */
class CoverageArtifactsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = CoverageLog.get(CoverageArtifactsPanel::class.java)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
    private val tableModel = ArtifactsTableModel()
    private val table = JBTable(tableModel)

    init {
        setupTable()

        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_CONTENT,
            DefaultActionGroup(RefreshCoverageAction()),
            /* horizontal = */ true,
        )
        toolbar.targetComponent = table

        add(toolbar.component, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)

        refreshData()
    }

    /** Reloads the artifact list from the cache service and updates the table. */
    fun refreshData() {
        val entries = CoverageCacheService.getInstance(project).listArtifacts()
        log.info("Artifacts panel: loaded ${entries.size} cached artifact(s)")
        tableModel.setEntries(entries, dateFormat)
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

    private inner class RefreshCoverageAction :
        AnAction("Refresh Coverage", "Check and download new coverage for the current commit", AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val loadService = CoverageLoadService.getInstance(project)
            val error = loadService.validateSettings()
            if (error != null) {
                Messages.showErrorDialog(project, error, "Configuration Error")
                return
            }
            loadService.loadFromGitLab(showErrors = true, onComplete = { refreshData() })
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

    companion object {
        private const val DATE_COL = 0
        private const val COMMIT_COL = 1
        private const val PIPELINE_COL = 2
        private const val SIZE_COL = 3
        private const val FILES_COL = 4
        private const val COVERAGE_COL = 5
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
