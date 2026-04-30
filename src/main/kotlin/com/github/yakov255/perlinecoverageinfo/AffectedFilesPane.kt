package com.github.yakov255.perlinecoverageinfo

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel

/**
 * The left-side files pane for the affected-tests view.
 *
 * Owns the [CheckboxTree] of affected files, its toolbar (expand/collapse/check/toggle-view),
 * and the logic to build the tree from an [AffectedTestsModel].
 *
 * [onCheckedFilesChanged] is invoked (on the EDT) whenever a checkbox state changes so the
 * owning panel can call [syncModelFromTree] and refresh the test list.
 */
internal class AffectedFilesPane(
    private val project: Project,
    private val onCheckedFilesChanged: () -> Unit,
) {

    private val filesRoot = CheckedTreeNode(null)
    private var isTreeView = true
    private var currentModel: AffectedTestsModel? = null
    private var syncPending = false

    val filesTree: CheckboxTree = @Suppress("DEPRECATION") CheckboxTree(object : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree?, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? CheckedTreeNode ?: return
            val model = currentModel ?: return
            val r = textRenderer
            when (val data = node.userObject) {
                is FileNodeData.Dir -> {
                    r.icon = AllIcons.Nodes.Folder
                    val testCount = data.files
                        .flatMapTo(linkedSetOf<String>()) { model.perFile[it] ?: emptySet() }
                        .size
                    r.append("$testCount ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val delta = model.deltaForFiles(data.files)
                    if (delta > 0) {
                        r.append("(-$delta) ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is FileNodeData.FileEntry -> {
                    r.icon = AllIcons.FileTypes.Any_type
                    val tests = model.perFile[data.path] ?: emptySet()
                    r.append("${tests.size} ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    val delta = model.deltaForFiles(setOf(data.path))
                    if (delta > 0) {
                        r.append("(-$delta) ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    r.append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }, filesRoot)

    val component: JComponent

    init {
        filesTree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                if (!syncPending) {
                    syncPending = true
                    ApplicationManager.getApplication().invokeLater {
                        syncPending = false
                        onCheckedFilesChanged()
                    }
                }
            }
        })

        val treeToggleAction = ToggleAffectedFilesViewAction(
            isTreeView = { isTreeView },
            toggle = { state ->
                isTreeView = state
                currentModel?.let { refresh(it) }
            },
        )

        val expandAllAction = object : AnAction(
            "Expand All", "Expand all nodes", AllIcons.Actions.Expandall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in 0 until filesTree.rowCount) filesTree.expandRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val collapseAllAction = object : AnAction(
            "Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                for (i in filesTree.rowCount - 1 downTo 1) filesTree.collapseRow(i)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val checkAllAction = object : AnAction(
            "Check All", "Check all files", AllIcons.Actions.Selectall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val model = currentModel ?: return
                model.checkAll()
                refresh(model)
                onCheckedFilesChanged()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val uncheckAllAction = object : AnAction(
            "Uncheck All", "Uncheck all files", AllIcons.Actions.Unselectall
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val model = currentModel ?: return
                model.uncheckAll()
                refresh(model)
                onCheckedFilesChanged()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

        val filesToolbar = ActionManager.getInstance().createActionToolbar(
            "AffectedFilesToolbar",
            DefaultActionGroup(
                treeToggleAction, expandAllAction, collapseAllAction,
                Separator.getInstance(),
                checkAllAction, uncheckAllAction,
            ),
            true
        )
        filesToolbar.targetComponent = filesTree

        val panel = JPanel(BorderLayout())
        panel.add(filesToolbar.component, BorderLayout.NORTH)
        panel.add(JBScrollPane(filesTree), BorderLayout.CENTER)
        component = panel
    }

    /** Load [model] and rebuild the tree. */
    fun populate(model: AffectedTestsModel) {
        currentModel = model
        refresh(model)
    }

    /** Rebuild the tree from the current model (e.g., after isTreeView toggle). */
    fun refresh(model: AffectedTestsModel) {
        filesRoot.removeAllChildren()

        if (isTreeView) {
            buildTreeNodes(model)
        } else {
            buildFlatNodes(model)
        }

        (filesTree.model as DefaultTreeModel).reload()

        if (isTreeView) {
            for (i in 0 until minOf(filesTree.rowCount, 100)) {
                filesTree.expandRow(i)
            }
        }
    }

    /**
     * Reads the current checkbox state of the tree and pushes it back into [model]'s
     * [AffectedTestsModel.checkedFiles]. Call this whenever a checkbox changes.
     */
    fun syncModelFromTree(model: AffectedTestsModel) {
        model.uncheckAll()

        fun walk(node: CheckedTreeNode) {
            if (node.userObject is FileNodeData.FileEntry && node.isChecked) {
                model.setChecked((node.userObject as FileNodeData.FileEntry).path, true)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i)
                if (child is CheckedTreeNode) walk(child)
            }
        }
        walk(filesRoot)

        filesTree.repaint()
    }

    // ── Tree building ──────────────────────────────────────────────

    private fun buildTreeNodes(model: AffectedTestsModel) {
        data class DirEntry(
            val children: MutableMap<String, DirEntry> = sortedMapOf(),
            val files: MutableList<String> = mutableListOf(),
        )

        val top = DirEntry()
        for (path in model.allFiles.sorted()) {
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

        fun addNodes(entry: DirEntry, parent: CheckedTreeNode) {
            for ((name, child) in entry.children) {
                // Compact middle packages: collapse single-child dirs with no files
                var collapsed = child
                var display = name
                while (collapsed.files.isEmpty() && collapsed.children.size == 1) {
                    val (cName, cChild) = collapsed.children.entries.first()
                    display = "$display/$cName"
                    collapsed = cChild
                }

                val files = allFilesUnder(collapsed)
                val dirNode = CheckedTreeNode(FileNodeData.Dir(display, files))
                addNodes(collapsed, dirNode)
                parent.add(dirNode)
            }
            for (filePath in entry.files) {
                val fileName = filePath.substringAfterLast("/")
                val node = CheckedTreeNode(FileNodeData.FileEntry(filePath, fileName))
                node.isChecked = filePath in model.checkedFiles
                parent.add(node)
            }
        }

        addNodes(top, filesRoot)
    }

    private fun buildFlatNodes(model: AffectedTestsModel) {
        val sorted = model.allFiles.sortedByDescending { (model.perFile[it] ?: emptySet()).size }
        for (path in sorted) {
            val node = CheckedTreeNode(FileNodeData.FileEntry(path, path))
            node.isChecked = path in model.checkedFiles
            filesRoot.add(node)
        }
    }
}
