package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.FillingLineMarkerRenderer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.project.Project
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent

enum class CoverageCategory { COVERED, UNCOVERED, FEATURE_ONLY, LOCAL }

private val COLOR_COVERED = Color(100, 180, 120)
private val COLOR_UNCOVERED = Color(210, 110, 110)
private val COLOR_FEATURE = Color(80, 140, 220)
/** Darker than [COLOR_FEATURE] so locally covered lines stand out from lines new on the branch. */
private val COLOR_LOCAL = Color(40, 90, 180)

class FileCoverageRenderer(
    private val filePath: String,
    private val project: Project,
    private val coverageLines: Map<Int, List<String>>,
    private val baselineLines: Map<Int, List<String>>?,
    private val hasBaseline: Boolean,
    private val warnings: CoverageWarnings?,
    /** Tests executed in local runs; their share of a line is called out in the tooltip. */
    private val localTests: Set<String> = emptySet(),
) : FillingLineMarkerRenderer, ActiveGutterRenderer {

    private val log = CoverageLog.get(FileCoverageRenderer::class.java)

    @Volatile
    private var hoveredLine: Int = -1

    override fun getTextAttributesKey(): TextAttributesKey = CodeInsightColors.LINE_FULL_COVERAGE

    override fun getMaxWidth(): Int = 20

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val g2 = g as? Graphics2D ?: return
        val clip = g.clipBounds ?: r
        val firstVis = editor.yToVisualLine(clip.y).coerceAtLeast(0)
        val lastVis = editor.yToVisualLine(clip.y + clip.height - 1).coerceAtLeast(firstVis)
        val stripWidth = if (r.width > 0) r.width else 20

        for (visLine in firstVis..lastVis) {
            val logLine = editor.visualToLogicalPosition(VisualPosition(visLine, 0)).line + 1
            val tests = coverageLines[logLine] ?: continue
            val baselineTests = baselineLines?.get(logLine) ?: emptyList()

            val paintY = editor.visualLineToY(visLine)
            val paintH = (EditorUtil.getVisualLineAreaEndY(editor, visLine) - paintY).coerceAtLeast(1)

            g2.color = when (CoverageHighlighter.categorizeLine(tests, baselineTests, hasBaseline, localTests)) {
                CoverageCategory.COVERED -> COLOR_COVERED
                CoverageCategory.UNCOVERED -> COLOR_UNCOVERED
                CoverageCategory.FEATURE_ONLY -> COLOR_FEATURE
                CoverageCategory.LOCAL -> COLOR_LOCAL
            }
            g2.fillRect(r.x, paintY, stripWidth, paintH)

            if (tests.isNotEmpty()) {
                val label = minOf(tests.size, 99).toString()
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = Color.WHITE
                g2.font = Font(Font.MONOSPACED, Font.BOLD, (paintH * 0.65f).toInt().coerceIn(7, 10))
                val fm = g2.fontMetrics
                val x = r.x + (stripWidth - fm.stringWidth(label)) / 2
                val y = paintY + (paintH + fm.ascent - fm.descent) / 2
                g2.drawString(label, x, y)
            }
        }
    }

    override fun getTooltipText(): String = textFor(hoveredLine, withWarnings = true)

    override fun getAccessibleName(): String = textFor(hoveredLine, withWarnings = false)

    override fun canDoAction(editor: Editor, e: MouseEvent): Boolean {
        if (coverageLines.isEmpty()) return false
        val component = e.component
        if (component !is EditorGutterComponentEx) return false
        if (e.x <= component.lineMarkerAreaOffset || e.x >= component.iconAreaOffset) return false
        val line = lineAtY(editor, e.y)
        val covered = line in coverageLines
        hoveredLine = if (covered) line else -1
        return covered
    }

    override fun doAction(editor: Editor, e: MouseEvent) {
        e.consume()
        val line = lineAtY(editor, e.y)
        val tests = coverageLines[line]
        if (tests == null) {
            log.warn("Coverage: gutter click on line=$line with no coverage data")
            return
        }
        val baselineTests = baselineLines?.get(line) ?: emptyList()
        CoveringLinePanel.showTestsInPanel(project, line, filePath, tests, baselineTests, hasBaseline)
    }

    private fun lineAtY(editor: Editor, y: Int): Int {
        val visLine = editor.yToVisualLine(y)
        return editor.visualToLogicalPosition(VisualPosition(visLine, 0)).line + 1
    }

    private fun textFor(line: Int, withWarnings: Boolean): String {
        if (line < 0) return ""
        val tests = coverageLines[line] ?: return ""
        val baselineTests = baselineLines?.get(line) ?: emptyList()
        val newCount = if (hasBaseline) CoverageDiff.featureOnly(tests, baselineTests).size else 0
        val localCount = if (localTests.isEmpty()) 0 else tests.count { it in localTests }
        val suffix = listOfNotNull(
            if (newCount > 0) "$newCount new on this branch" else null,
            if (localCount > 0) "$localCount from local run" else null,
        ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
        val base = if (tests.isEmpty()) {
            if (withWarnings) "Line $line: not covered" else "Line $line not covered"
        } else {
            if (withWarnings) "Line $line covered by ${tests.size} test(s)$suffix"
            else "Line $line covered by ${tests.size} tests$suffix"
        }
        val warningText = warnings?.formatPlain()
        return when {
            warningText.isNullOrEmpty() -> base
            withWarnings -> "$base\n\n⚠ $warningText"
            else -> "$base. ⚠ $warningText"
        }
    }
}
