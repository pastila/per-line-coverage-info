package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import javax.swing.Icon
import javax.swing.UIManager

class CoverageGutterRenderer(
    private val lineNumber: Int,
    private val tests: List<String>
) : GutterIconRenderer() {

    override fun getIcon(): Icon {
        return if (tests.isNotEmpty()) CoverageIcons.COVERED else CoverageIcons.UNCOVERED
    }

    override fun getTooltipText(): String {
        return if (tests.isNotEmpty()) {
            "Line $lineNumber covered by ${tests.size} test(s):\n${tests.joinToString("\n") { "• $it" }}"
        } else {
            "Line $lineNumber: not covered"
        }
    }

    override fun getClickAction(): AnAction? {
        if (tests.isEmpty()) return null
        return object : AnAction("Show Tests Covering Line $lineNumber") {
            override fun actionPerformed(e: AnActionEvent) {
                val component = e.inputEvent?.component ?: return
                val list = JBList(tests)
                JBPopupFactory.getInstance()
                    .createListPopupBuilder(list)
                    .setTitle("Tests covering line $lineNumber (${tests.size})")
                    .createPopup()
                    .showUnderneathOf(component)
            }
        }
    }

    override fun isNavigateAction(): Boolean = tests.isNotEmpty()

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoverageGutterRenderer) return false
        return lineNumber == other.lineNumber && tests == other.tests
    }

    override fun hashCode(): Int = 31 * lineNumber + tests.hashCode()
}
