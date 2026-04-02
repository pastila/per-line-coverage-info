package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.FillingLineMarkerRenderer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.awt.event.MouseEvent

class CoverageGutterRenderer(
    private val lineNumber: Int,
    private val tests: List<String>,
    private val attrKey: TextAttributesKey
) : FillingLineMarkerRenderer, ActiveGutterRenderer {

    override fun getTextAttributesKey(): TextAttributesKey = attrKey

    override fun getMaxWidth(): Int = 8

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

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
        CoverageTestsPanel.showTestsInPanel(project, lineNumber, filePath, tests)
    }

    override fun getAccessibleName(): String {
        return if (tests.isNotEmpty()) {
            "Line $lineNumber covered by ${tests.size} tests"
        } else {
            "Line $lineNumber not covered"
        }
    }
}
