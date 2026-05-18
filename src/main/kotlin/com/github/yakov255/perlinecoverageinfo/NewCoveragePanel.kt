package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.jetbrains.php.PhpIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class NewCoveragePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val log = CoverageLog.get(NewCoveragePanel::class.java)

    private var isTreeView = true
    private var currentModel: NewCoverageModel? = null
    private var lastCoverageGeneration: Long = -1

    @Volatile
    private var computing = false

    private val messageBusConnection = project.messageBus.connect()

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

        messageBusConnection.subscribe(
            CoverageDataService.COVERAGE_CHANGED_TOPIC,
            CoverageChangeListener { refresh() },
        )

        refresh()
    }

    override fun dispose() {
        messageBusConnection.dispose()
    }

    fun refresh() {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasBaseline()) {
            clearTree()
            statusLabel.text = "No baseline coverage loaded. Dual-coverage mode is not active."
            return
        }

        if (computing) return
        val currentGen = dataService.coverageGeneration
        if (currentGen == lastCoverageGeneration && currentModel != null) {
            return
        }

        computing = true
        statusLabel.text = "Computing new coverage..."

        object : Task.Backgroundable(project, "Computing new coverage\u2026", true) {
            override fun run(indicator: ProgressIndicator) {
                val model = computeNewCoverage(indicator)
                ApplicationManager.getApplication().invokeLater { populate(model) }
            }

            override fun onFinished() {
                computing = false
            }
        }.queue()
    }

    private fun computeNewCoverage(indicator: ProgressIndicator): NewCoverageModel {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasBaseline()) return NewCoverageModel.EMPTY

        val gitRoot = dataService.gitRoot ?: return NewCoverageModel.EMPTY
        val primaryCommit = dataService.coverageCommitHash ?: return NewCoverageModel.EMPTY
        val baselineCommit = dataService.baselineCommitHash ?: return NewCoverageModel.EMPTY
        val allFiles = dataService.allFiles()

        // ── Phase 1: find files changed between baseline and primary commits ──
        indicator.text = "Resolving changed files…"
        val changedFiles = CoverageResolver.runGitCommand(
            gitRoot, "diff", "--name-only", baselineCommit, primaryCommit,
        )?.lines()?.map { it.replace('\\', '/') }?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        log.info("NewCoverage: scanning ${allFiles.size} files (${changedFiles.size} changed between commits)")

        val result = mutableListOf<NewCoverageFile>()
        var processed = 0
        val total = allFiles.size

        for (gitRelativePath in allFiles) {
            if (indicator.isCanceled) break

            val primary = dataService.getCoverage(gitRelativePath) ?: continue
            val baseline = dataService.getBaselineCoverage(gitRelativePath) ?: emptyMap()

            val isChanged = gitRelativePath in changedFiles

            val featureOnlyLines = if (isChanged) {
                computeMappedFeatureOnly(gitRelativePath, gitRoot)
            } else {
                computeRawFeatureOnly(primary, baseline)
            }

            if (featureOnlyLines.isNotEmpty()) {
                result.add(NewCoverageFile(gitRelativePath, featureOnlyLines))
            }

            processed++
            if (processed % 1000 == 0) {
                indicator.text = "Scanning $processed / $total files"
                indicator.fraction = processed.toDouble() / total
                log.info("NewCoverage: processed $processed / $total")
            }
        }

        result.sortByDescending { it.count }
        val changedCount = result.count { it.gitRelativePath in changedFiles }
        log.info("NewCoverage: computed ${result.size} files (${changedCount} from changed, ${result.size - changedCount} from unchanged), ${result.sumOf { it.count }} feature-only lines")
        return NewCoverageModel(result, result.size, result.sumOf { it.count })
    }

    /** Fast path: raw line-number comparison for unchanged files. */
    private fun computeRawFeatureOnly(
        primary: Map<Int, List<String>>,
        baseline: Map<Int, List<String>>,
    ): List<NewCoverageLine> {
        return primary.mapNotNull { (line, tests) ->
            val baselineTests = baseline[line] ?: emptyList()
            if (baselineTests.isEmpty() && tests.isNotEmpty()) NewCoverageLine(line, tests) else null
        }
    }

    /** Slow path: line-mapped comparison for files changed between commits. */
    private fun computeMappedFeatureOnly(
        gitRelativePath: String,
        gitRoot: java.io.File,
    ): List<NewCoverageLine> {
        val absolutePath = try {
            Paths.get(gitRoot.path, gitRelativePath).toString()
        } catch (_: Exception) {
            return emptyList()
        }

        val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return emptyList()
        val currentContent = ApplicationManager.getApplication().runReadAction<String?> {
            FileDocumentManager.getInstance().getDocument(virtualFile)?.text
                ?: try {
                    String(virtualFile.contentsToByteArray())
                } catch (_: Exception) {
                    null
                }
        } ?: return emptyList()

        val lineMappingService = LineMappingService.getInstance(project)
        val mappedPrimary = lineMappingService.getMappedCoverage(virtualFile.path, currentContent) ?: return emptyList()
        val mappedBaseline = lineMappingService.getMappedBaselineCoverage(virtualFile.path, currentContent)

        if (mappedBaseline == null) {
            // New file on branch — all covered lines are feature-only
            return mappedPrimary.filter { it.value.isNotEmpty() }
                .map { NewCoverageLine(it.key, it.value) }
        }

        return mappedPrimary.mapNotNull { (line, tests) ->
            val baselineTests = mappedBaseline[line] ?: emptyList()
            if (baselineTests.isEmpty() && tests.isNotEmpty()) NewCoverageLine(line, tests) else null
        }
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

        // Map the first feature-only line from the coverage commit to the current document
        val currentContent = ApplicationManager.getApplication().runReadAction<String?> {
            FileDocumentManager.getInstance().getDocument(vf)?.text
                ?: try {
                    String(vf.contentsToByteArray())
                } catch (_: Exception) {
                    null
                }
        }

        val mappedLine = if (currentContent != null) {
            val lineMappingService = LineMappingService.getInstance(project)
            val mappedPrimary = lineMappingService.getMappedCoverage(vf.path, currentContent)
            val mappedBaseline = lineMappingService.getMappedBaselineCoverage(vf.path, currentContent)
            if (mappedPrimary != null) {
                mappedPrimary.entries
                    .filter { (line, tests) ->
                        val bt = mappedBaseline?.get(line) ?: emptyList()
                        tests.isNotEmpty() && bt.isEmpty()
                    }
                    .minOfOrNull { it.key }
            } else {
                null
            }
        } else {
            null
        } ?: file.firstFeatureOnlyLine

        val line = if (mappedLine > 0) mappedLine - 1 else 0
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
