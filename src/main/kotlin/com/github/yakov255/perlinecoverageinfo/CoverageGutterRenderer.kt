package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.FillingLineMarkerRenderer
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.github.yakov255.perlinecoverageinfo.CoverageLog
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent

enum class CoverageCategory { COVERED, UNCOVERED, FEATURE_ONLY }

private val COLOR_COVERED = Color(100, 180, 120)
private val COLOR_UNCOVERED = Color(210, 110, 110)
private val COLOR_FEATURE = Color(80, 140, 220)

class FileCoverageRenderer(
    private val filePath: String,
    private val project: com.intellij.openapi.project.Project,
    private val coverageLines: Map<Int, List<String>>,
    private val baselineLines: Map<Int, List<String>>?,
    private val hasBaseline: Boolean,
    private val warnings: CoverageWarnings?,
) : FillingLineMarkerRenderer, ActiveGutterRenderer, Disposable {

    private val log = CoverageLog.get(FileCoverageRenderer::class.java)
    private val mouseDisposable = Disposer.newDisposable()
    @Volatile
    private var hoveredLine: Int = -1

    private var debugFileId = ""

    fun install(editor: Editor) {
        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document)
        debugFileId = virtualFile?.name ?: "?"
        log.info("Coverage: FileCoverageRenderer.install file=$debugFileId path=$filePath lines=${coverageLines.size}")

        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val pos = editor.xyToLogicalPosition(e.mouseEvent.point)
                val prev = hoveredLine
                hoveredLine = pos.line + 1
                if (hoveredLine != prev) {
                    log.debug("Coverage: mouseMoved -> line ${hoveredLine}, hasData=${coverageLines.containsKey(hoveredLine)}")
                }
            }
        }, mouseDisposable)
    }

    override fun getTextAttributesKey(): TextAttributesKey = CodeInsightColors.LINE_FULL_COVERAGE

    override fun getMaxWidth(): Int = 20

    override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT

    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
        val g2 = g as? Graphics2D ?: return
        val scrollOffset = editor.scrollingModel.verticalScrollOffset
        val visibleRect = editor.scrollingModel.visibleArea
        val firstVis = editor.yToVisualLine(visibleRect.y).coerceAtLeast(0)
        val lastVis = editor.yToVisualLine(visibleRect.y + visibleRect.height)
            .coerceAtLeast(firstVis)
        val lineHeight = editor.lineHeight
        val stripWidth = if (r.width > 0) r.width else 20

        log.debug("Coverage: paint visLines=$firstVis..$lastVis scroll=$scrollOffset visRect=$visibleRect clipR=$r")

        for (visLine in firstVis..lastVis) {
            val logPos = editor.visualToLogicalPosition(VisualPosition(visLine, 0))
            val logLine = logPos.line + 1
            val tests = coverageLines[logLine] ?: continue

            val docY = editor.logicalPositionToXY(LogicalPosition(logPos.line, 0)).y
            val paintY = docY - scrollOffset

            g2.color = when (CoverageHighlighter.categorizeLine(
                tests, baselineLines?.get(logLine) ?: emptyList(), hasBaseline
            )) {
                CoverageCategory.COVERED -> COLOR_COVERED
                CoverageCategory.UNCOVERED -> COLOR_UNCOVERED
                CoverageCategory.FEATURE_ONLY -> COLOR_FEATURE
            }
            g2.fillRect(r.x, paintY, stripWidth, lineHeight)

            if (tests.isNotEmpty()) {
                val label = minOf(tests.size, 99).toString()
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = Color.WHITE
                g2.font = Font(Font.MONOSPACED, Font.BOLD, (lineHeight * 0.65f).toInt().coerceIn(7, 10))
                val fm = g2.fontMetrics
                val x = r.x + (stripWidth - fm.stringWidth(label)) / 2
                val y = paintY + (lineHeight + fm.ascent - fm.descent) / 2
                g2.drawString(label, x, y)
            }
        }
    }

    override fun getTooltipText(): String {
        val line = hoveredLine
        if (line < 0) return ""
        val tests = coverageLines[line]
        if (tests == null) {
            log.debug("Coverage: tooltip line=$line no coverage data")
            return ""
        }
        val baselineTests = baselineLines?.get(line) ?: emptyList()
        val base = if (tests.isEmpty()) {
            "Line $line: not covered"
        } else {
            val newCount = if (hasBaseline) CoverageDiff.featureOnly(tests, baselineTests).size else 0
            val suffix = if (newCount > 0) " ($newCount new on this branch)" else ""
            "Line $line covered by ${tests.size} test(s)$suffix"
        }
        val warningText = warnings?.formatPlain()
        return if (!warningText.isNullOrEmpty()) "$base\n\n⚠ $warningText" else base
    }

    override fun getAccessibleName(): String {
        val line = hoveredLine
        if (line < 0) return ""
        val tests = coverageLines[line] ?: return ""
        val baselineTests = baselineLines?.get(line) ?: emptyList()
        val base = if (tests.isEmpty()) "Line $line not covered" else {
            val newCount = if (hasBaseline) CoverageDiff.featureOnly(tests, baselineTests).size else 0
            val suffix = if (newCount > 0) " ($newCount new on this branch)" else ""
            "Line $line covered by ${tests.size} tests$suffix"
        }
        val warningText = warnings?.formatPlain()
        return if (!warningText.isNullOrEmpty()) "$base. ⚠ $warningText" else base
    }

    override fun canDoAction(editor: Editor, e: MouseEvent): Boolean {
        if (coverageLines.isEmpty()) return false
        val component = e.component
        if (component is EditorGutterComponentEx) {
            return e.x > component.lineMarkerAreaOffset && e.x < component.iconAreaOffset
        }
        return false
    }

    override fun doAction(editor: Editor, e: MouseEvent) {
        e.consume()
        val pos = editor.xyToLogicalPosition(e.point)
        val line = pos.line + 1
        log.info("Coverage: gutter click file=$debugFileId line=$line")
        val tests = coverageLines[line]
        if (tests == null) {
            log.warn("Coverage: click on line=$line but no coverage data for it")
            return
        }
        val baselineTests = baselineLines?.get(line) ?: emptyList()
        CoveringLinePanel.showTestsInPanel(project, line, filePath, tests, baselineTests, hasBaseline)
    }

    override fun dispose() {
        log.info("Coverage: FileCoverageRenderer.dispose file=$debugFileId")
        Disposer.dispose(mouseDisposable)
    }
}
