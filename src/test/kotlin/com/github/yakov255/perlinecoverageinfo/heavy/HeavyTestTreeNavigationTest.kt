package com.github.yakov255.perlinecoverageinfo.heavy

import com.github.yakov255.perlinecoverageinfo.CoverageTestIdentity
import com.github.yakov255.perlinecoverageinfo.TestNodeData
import com.github.yakov255.perlinecoverageinfo.TestTreeView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import java.awt.event.MouseEvent
import javax.swing.tree.TreePath

/** Exercises actual Swing rendering and the double-click navigation used after a gutter click. */
class HeavyTestTreeNavigationTest : BasePlatformTestCase() {
    fun testPhpUnitLeavesShowMethodNamesAndPreserveTheirNavigationTarget() {
        val name = "App\\Tests\\CalculatorTest::testAdds"
        val view = TestTreeView(project)
        for (hierarchical in listOf(true, false)) {
            view.isTreeView = hierarchical
            view.setTests(listOf(name))
            waitFor { nodes(view).any { it.userObject is TestNodeData.PhpUnitMethod } }
            val leaf = nodes(view).single { it.userObject is TestNodeData.PhpUnitMethod }
            assertEquals("testAdds", render(view, leaf))
            view.tree.selectionPath = TreePath(leaf.path)
            assertEquals(setOf(name), view.collectSelectedTestNames())
            assertEquals(setOf(name), view.checkedTestNames())
        }
    }

    fun testAnonymousCiSessionIsAnExplanationInsteadOfAnUnnavigablePhpUnitTest() {
        val view = TestTreeView(project)
        for (hierarchical in listOf(true, false)) {
            view.isTreeView = hierarchical
            view.setTests(listOf("app/raketa/1"))
            waitFor { nodes(view).any { it.userObject is TestNodeData.UnnamedCoverage } }
            val leaf = nodes(view).single { it.userObject is TestNodeData.UnnamedCoverage }
            assertEquals(CoverageTestIdentity.UNNAMED_MESSAGE, render(view, leaf))
            assertFalse(nodes(view).any { it.userObject is TestNodeData.PhpUnitGroup })
            view.tree.selectionPath = TreePath(leaf.path)
            assertTrue(view.collectSelectedTestNames().isEmpty())
            assertTrue(view.checkedTestNames().isEmpty())
        }
    }

    fun testBehatDoubleClickStillOpensTheOriginalFeatureAndScenarioLine() {
        val feature = myFixture.addFileToProject(
            "features/Navigation.feature",
            "Feature: Navigation\n\n  Scenario: opens the feature\n    Given a step\n",
        ).virtualFile
        val name = "features/Navigation.feature:3"
        val view = TestTreeView(project)
        view.setTests(listOf(name, "app/raketa/1"))
        waitFor { nodes(view).any { it.userObject is TestNodeData.BehatScenario } }
        val leaf = nodes(view).single { it.userObject is TestNodeData.BehatScenario }
        assertEquals(setOf(name), view.checkedTestNames())
        onEdt {
            view.tree.selectionPath = TreePath(leaf.path)
            val event = MouseEvent(view.tree, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 2, false)
            view.tree.mouseListeners.forEach { it.mouseClicked(event) }
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull("double-click must open the feature", editor)
        assertEquals(feature, FileDocumentManager.getInstance().getFile(editor!!.document))
        assertEquals(2, editor.caretModel.logicalPosition.line)
    }

    private fun nodes(view: TestTreeView): List<CheckedTreeNode> =
        (view.tree.model.root as CheckedTreeNode).depthFirstEnumeration().asSequence()
            .map { it as CheckedTreeNode }.toList()

    private fun render(view: TestTreeView, node: CheckedTreeNode): String {
        val renderer = view.tree.cellRenderer as CheckboxTree.CheckboxTreeCellRenderer
        renderer.getTreeCellRendererComponent(view.tree, node, false, false, true, 0, false)
        return renderer.textRenderer.toString()
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("the test tree must be populated", condition())
    }

    private fun onEdt(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) action() else app.invokeAndWait(action)
    }
}
