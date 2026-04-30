package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.FillingLineMarkerRenderer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent

private val COLOR_COVERED = Color(100, 180, 120)
private val COLOR_UNCOVERED = Color(210, 110, 110)

class CoverageGutterRenderer(
    private val lineNumber: Int,
    private val tests: List<String>,
    private val attrKey: TextAttributesKey
) : FillingLineMarkerRenderer, ActiveGutterRenderer {

    override fun getTextAttributesKey(): TextAttributesKey = attrKey

    override fun getMaxWidth(): Int = 20

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val g2 = g as? Graphics2D ?: return

        g2.color = if (tests.isNotEmpty()) COLOR_COVERED else COLOR_UNCOVERED
        g2.fillRect(r.x, r.y, r.width, r.height)

        if (tests.isNotEmpty()) {
            val label = minOf(tests.size, 99).toString()
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = Color.WHITE
            g2.font = Font(Font.MONOSPACED, Font.BOLD, (r.height * 0.65f).toInt().coerceIn(7, 10))
            val fm = g2.fontMetrics
            val x = r.x + (r.width - fm.stringWidth(label)) / 2
            val y = r.y + (r.height + fm.ascent - fm.descent) / 2
            g2.drawString(label, x, y)
        }
    }

    override fun getTooltipText(): String {
        return if (tests.isNotEmpty()) {
            "Line $lineNumber covered by ${tests.size} test(s)"
        } else {
            "Line $lineNumber: not covered"
        }
    }

    override fun canDoAction(editor: Editor, e: MouseEvent): Boolean {
        if (tests.isEmpty()) return false
        val component = e.component
        if (component is EditorGutterComponentEx) {
            return e.x > component.lineMarkerAreaOffset && e.x < component.iconAreaOffset
        }
        return false
    }

    override fun doAction(editor: Editor, e: MouseEvent) {
        e.consume()
        val project = editor.project ?: return
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
        val filePath = virtualFile?.path ?: ""
        CoveringLinePanel.showTestsInPanel(project, lineNumber, filePath, tests)
    }

    override fun getAccessibleName(): String {
        return if (tests.isNotEmpty()) {
            "Line $lineNumber covered by ${tests.size} tests"
        } else {
            "Line $lineNumber not covered"
        }
    }
}
