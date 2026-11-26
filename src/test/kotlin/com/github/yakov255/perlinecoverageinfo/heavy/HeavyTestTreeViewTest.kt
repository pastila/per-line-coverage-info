package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.TestNodeData
import com.github.yakov255.perlinecoverageinfo.TestTreeView
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil

class HeavyTestTreeViewTest : BasePlatformTestCase() {
    fun testFolderToggleRefreshesSelectionOnceFor15115Tests() {
        val folderTestCount = 15_115
        val siblingTest = "Other\\SiblingTest::testSibling"
        val tests = (1..folderTestCount).map { "Affected\\LargeTest::test$it" } + siblingTest
        val view = TestTreeView(project)
        try {
            view.setTests(tests)
            PlatformTestUtil.waitWithEventsDispatching(
                "Test tree was not populated", { view.currentTests().size == tests.size }, 15_000,
            )
            val root = view.tree.model.root as CheckedTreeNode
            val folder = root.children().asSequence().filterIsInstance<CheckedTreeNode>()
                .single { (it.userObject as? TestNodeData.Dir)?.displayName == "Affected" }
            val status = UIUtil.findComponentsOfType(view.component, JBLabel::class.java)
                .single { it.text.orEmpty().startsWith("Ready:") }
            val selectionUpdates = mutableListOf<String>()
            status.addPropertyChangeListener("text") { selectionUpdates.add(it.newValue as String) }

            view.tree.setNodeState(folder, false)
            assertEquals(setOf(siblingTest), view.checkedTestNames())
            assertTrue(selectionUpdates.isEmpty())
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val deselectedStatus = "Ready: ${tests.size} · selected: 1 · unresolved: 0"
            assertEquals(listOf(deselectedStatus), selectionUpdates)

            view.tree.setNodeState(folder, true)
            assertEquals(tests.toSet(), view.checkedTestNames())
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val selectedStatus = "Ready: ${tests.size} · selected: ${tests.size} · unresolved: 0"
            assertEquals(listOf(deselectedStatus, selectedStatus), selectionUpdates)
        } finally {
            Disposer.dispose(view)
        }
    }
}
