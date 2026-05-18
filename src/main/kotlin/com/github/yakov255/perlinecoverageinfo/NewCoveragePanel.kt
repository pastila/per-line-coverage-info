package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.jetbrains.php.PhpIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class NewCoveragePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = CoverageLog.get(NewCoveragePanel::class.java)

    private var isTreeView = true
    private var currentModel: NewCoverageModel? = null
    private var lastCoverageGeneration: Long = -1

    private val filesRoot = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(filesRoot)
    private val fileTree = JTree(treeModel)
    private val statusLabel = JBLabel("Click Refresh or wait for coverage to load")

    init {
        fileTree.setRootVisible(false)
        fileTree.setShowsRootHandles(true)
        fileTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        fileTree.cellRenderer = FileTreeCellRenderer()

        fileTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    handleDoubleClick()
                }
            }
        })

        val toolbar = createToolbar()
        add(toolbar.component, BorderLayout.NORTH)
        add(JBScrollPane(fileTree), BorderLayout.CENTER)

        val statusPanel = JPanel(BorderLayout())
        statusPanel.border = JBUI.Borders.empty(2, 6)
        statusPanel.add(statusLabel, BorderLayout.CENTER)
        add(statusPanel, BorderLayout.SOUTH)

        project.messageBus.connect().subscribe(
            CoverageDataService.COVERAGE_CHANGED_TOPIC,
            CoverageChangeListener { refresh() },
        )

        refresh()
    }

    fun refresh() {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasBaseline()) {
            clearTree()
            statusLabel.text = "No baseline coverage loaded. Dual-coverage mode is not active."
            return
        }

        val currentGen = dataService.coverageGeneration
        if (currentGen == lastCoverageGeneration && currentModel != null) {
            return
        }

        statusLabel.text = "Computing new coverage..."

        object : Task.Backgroundable(project, "Computing new coverage\u2026", true) {
            override fun run(indicator: ProgressIndicator) {
                val model = computeNewCoverage()
                ApplicationManager.getApplication().invokeLater { populate(model) }
            }
        }.queue()
    }

    private fun computeNewCoverage(): NewCoverageModel {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasBaseline()) return NewCoverageModel.EMPTY

        val result = mutableListOf<NewCoverageFile>()

        for (filePath in dataService.allFiles()) {
            val primary = dataService.getCoverage(filePath) ?: continue
            val baseline = dataService.getBaselineCoverage(filePath) ?: continue

            val featureOnlyLines = primary.mapNotNull { (line, tests) ->
                val baselineTests = baseline[line] ?: emptyList()
                if (baselineTests.isEmpty() && tests.isNotEmpty()) NewCoverageLine(line, tests) else null
            }

            if (featureOnlyLines.isNotEmpty()) {
                result.add(NewCoverageFile(filePath, featureOnlyLines))
            }
        }

        result.sortByDescending { it.count }
        log.info("NewCoverage: computed ${result.size} files, ${result.sumOf { it.count }} feature-only lines across ${dataService.allFiles().size} total files")
        return NewCoverageModel(result, result.size, result.sumOf { it.count })
    }

    private fun populate(model: NewCoverageModel) {
        currentModel = model
        lastCoverageGeneration = CoverageDataService.getInstance(project).coverageGeneration
        rebuildTree(model)
        statusLabel.text = if (model.totalFiles > 0) {
            "${model.totalFiles} files, ${model.totalLines} lines with new coverage"
        } else {
            "No lines with new coverage found"
        }
    }

    private fun clearTree() {
        filesRoot.removeAllChildren()
        treeModel.reload()
        currentModel = null
    }

    private fun rebuildTree(model: NewCoverageModel) {
        filesRoot.removeAllChildren()

        if (isTreeView) {
            buildTreeNodes(model)
        } else {
            buildFlatNodes(model)
        }

        treeModel.reload()

        if (isTreeView) {
            for (i in 0 until minOf(fileTree.rowCount, 100)) {
                fileTree.expandRow(i)
            }
        }
    }

    private fun buildTreeNodes(model: NewCoverageModel) {
        data class DirEntry(
            val children: MutableMap<String, DirEntry> = sortedMapOf(),
            val files: MutableList<String> = mutableListOf(),
        )

        val top = DirEntry()
        for (file in model.files) {
            val path = file.gitRelativePath
            val parts = path.split("/")
            var current = top
            for (i in 0 until parts.size - 1) {
                current = current.children.getOrPut(parts[i]) { DirEntry() }
            }
            current.files.add(path)
        }

        fun allFilesUnder(entry: DirEntry): Set<String> {
            val result = mutableSetOf<String>()
            result.addAll(entry.files)
            for ((_, child) in entry.children) result.addAll(allFilesUnder(child))
            return result
        }

        fun addNodes(entry: DirEntry, parent: DefaultMutableTreeNode) {
            for ((name, child) in entry.children) {
                var collapsed = child
                var display = name
                while (collapsed.files.isEmpty() && collapsed.children.size == 1) {
                    val (cName, cChild) = collapsed.children.entries.first()
                    display = "$display/$cName"
                    collapsed = cChild
                }
                val files = allFilesUnder(collapsed)
                val dirNode = DefaultMutableTreeNode(FileNodeData.Dir(display, files))
                addNodes(collapsed, dirNode)
                parent.add(dirNode)
            }
            for (filePath in entry.files) {
                val fileName = filePath.substringAfterLast("/")
                parent.add(DefaultMutableTreeNode(FileNodeData.FileEntry(filePath, fileName)))
            }
        }

        addNodes(top, filesRoot)
    }

    private fun buildFlatNodes(model: NewCoverageModel) {
        for (file in model.files.sortedByDescending { it.count }) {
            filesRoot.add(DefaultMutableTreeNode(FileNodeData.FileEntry(file.gitRelativePath, file.displayName)))
        }
    }

    private fun handleDoubleClick() {
        val node = fileTree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject as? FileNodeData.FileEntry ?: return
        val model = currentModel ?: return
        val file = model.files.find { it.gitRelativePath == data.path } ?: return

        val projectRelativePath = CoverageTestNavigator.toProjectRelativeFeaturePath(file.gitRelativePath, project)
        val projectDir = project.guessProjectDir() ?: return
        val vf = VfsUtil.findRelativeFile(projectRelativePath, projectDir) ?: return
        val line = if (file.firstFeatureOnlyLine > 0) file.firstFeatureOnlyLine - 1 else 0
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, line, 0), true)
    }

    private fun createToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
        val refreshAction = object : AnAction(
            "Refresh", "Re-compute new coverage", AllIcons.Actions.Refresh,
        ) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val treeToggleAction = object : ToggleAction(
            "Tree View", "Toggle between tree and flat file list", AllIcons.Actions.GroupByPackage,
        ) {
            override fun isSelected(e: AnActionEvent) = isTreeView
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                isTreeView = state
                currentModel?.let { rebuildTree(it) }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val expandAllAction = object : AnAction(
            "Expand All", "Expand all nodes", AllIcons.Actions.Expandall,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in 0 until fileTree.rowCount) fileTree.expandRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val collapseAllAction = object : AnAction(
            "Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in fileTree.rowCount - 1 downTo 1) fileTree.collapseRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        return ActionManager.getInstance().createActionToolbar(
            "NewCoverageToolbar",
            DefaultActionGroup(
                refreshAction,
                Separator.getInstance(),
                treeToggleAction, expandAllAction, collapseAllAction,
            ),
            true,
        ).apply { targetComponent = fileTree }
    }

    private inner class FileTreeCellRenderer : DefaultTreeCellRenderer() {

        init {
            horizontalAlignment = SwingConstants.LEFT
        }

        override fun getTreeCellRendererComponent(
            tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            val model = currentModel ?: return this

            when (val data = node.userObject) {
                is FileNodeData.Dir -> {
                    icon = AllIcons.Nodes.Folder
                    font = font.deriveFont(java.awt.Font.BOLD)
                    val totalCount = model.files
                        .filter { data.files.contains(it.gitRelativePath) }
                        .sumOf { it.count }
                    text = "$totalCount  ${data.displayName}"
                }
                is FileNodeData.FileEntry -> {
                    icon = if (data.path.endsWith(".php")) PhpIcons.PHP_FILE else AllIcons.FileTypes.Any_type
                    val file = model.files.find { it.gitRelativePath == data.path }
                    val count = file?.count ?: 0
                    text = "$count  ${data.displayName}"
                }
            }
            return this
        }
    }

    companion object {
        const val TAB_TITLE = "New Coverage"
    }
}
