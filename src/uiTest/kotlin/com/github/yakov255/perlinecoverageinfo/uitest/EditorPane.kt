package com.github.yakov255.perlinecoverageinfo.uitest

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.search.locators.byXpath
import java.awt.Point
import java.time.Duration

/**
 * A PHP file open in the real editor, plus the coverage gutter.
 *
 * The plugin paints its gutter through a range highlighter whose `lineMarkerRenderer` is
 * `FileCoverageRenderer`. That renderer is not a Swing component, so RemoteRobot cannot see it:
 * the per-line classification is asserted through the bridge snapshot, while this class covers
 * what is genuinely visible — the file is open, the gutter exists, and a click lands on it.
 */
class EditorPane(private val ide: IdeRobot) {

    private val editorLocator = byXpath("//div[@class='EditorComponentImpl']")
    private val gutterLocator = byXpath("//div[contains(@class,'EditorGutterComponentImpl')]")

    /**
     * Opens a file by name through Go to File. The project is large and indexes for a while after
     * start, so the search can come back empty: retry until an editor shows up.
     */
    fun openFile(gitRelativePath: String) {
        val fileName = gitRelativePath.substringAfterLast('/')
        ide.await("$fileName did not open in an editor", Duration.ofMinutes(4), Duration.ofSeconds(1)) {
            val alreadyOpen = runCatching {
                ide.robot.find(ComponentFixture::class.java, editorLocator, Duration.ofSeconds(1))
            }.getOrNull()
            if (alreadyOpen != null) return@await alreadyOpen

            ide.invokeAction("GotoFile")
            // Wait for the popup instead of a fixed pause; if its class differs on this platform
            // version the wait just times out and typing proceeds.
            runCatching {
                ide.robot.find(
                    ComponentFixture::class.java,
                    byXpath("//div[contains(@class,'SearchEverywhere')]"),
                    Duration.ofSeconds(5),
                )
            }
            ide.keyboard.enterText(fileName)
            // The result list is rebuilt asynchronously and has no stable locator: this pause is
            // the one remaining sleep, retried by the surrounding await when it is too short.
            Thread.sleep(3000)
            ide.keyboard.enter()
            runCatching { ide.robot.find(ComponentFixture::class.java, editorLocator, Duration.ofSeconds(10)) }.getOrNull()
        }
    }

    /** The editor component of the currently active file. */
    fun editor(): ComponentFixture =
        ide.robot.find(ComponentFixture::class.java, editorLocator, Duration.ofSeconds(30))

    /** The gutter strip painted at the left of the editor. */
    fun gutter(): ComponentFixture =
        ide.robot.find(ComponentFixture::class.java, gutterLocator, Duration.ofSeconds(20))

    /** Number of icons RemoteRobot can see in the gutter (the plugin paints, so this is usually 0). */
    fun gutterIconCount(): Int = runCatching {
        val gutter = ide.robot.find(
            com.intellij.remoterobot.fixtures.GutterFixture::class.java,
            byXpath("//div[contains(@class,'EditorGutterComponentImpl')]"),
            Duration.ofSeconds(10),
        )
        gutter.getIcons().size
    }.getOrDefault(0)

    /** Geometry of the gutter, read from the live Swing component. */
    fun gutterGeometry(): GutterGeometry {
        val height = (gutter().retrieve<Number>("component.getHeight()") ?: 0).toInt()
        // The gutter component has no getLineHeight(); the Editor behind the editor component does.
        val editor = editor()
        val lineHeight = (editor.retrieve<Number>("component.getEditor().getLineHeight()", true) ?: 0).toInt()
        val lineCount = (editor.retrieve<Number>("component.getEditor().getDocument().getLineCount()", true) ?: 0).toInt()
        return GutterGeometry(height, lineHeight, lineCount)
    }

    /** Y of the vertical middle of [line] (1-based) in gutter coordinates, scroll offset included. */
    private fun lineCenterY(line: Int, lineHeight: Int): Int {
        val top = editor().retrieve<Number>(
            "component.getEditor().visualLineToY(${line - 1}) - " +
                "component.getEditor().getScrollingModel().getVerticalScrollOffset()",
            true,
        )?.toInt() ?: ((line - 1) * lineHeight)
        return top + lineHeight / 2
    }

    /**
     * Clicks the gutter at [line] (1-based). The x coordinate sits inside the marker strip the
     * plugin paints, which is where the renderer's hit-test expects the click.
     */
    fun clickGutterAtLine(line: Int) {
        val geometry = gutterGeometry()
        val lineHeight = geometry.lineHeight.takeIf { it > 0 } ?: 18
        val y = lineCenterY(line, lineHeight)
        gutter().click(Point(4, y.coerceIn(1, geometry.height.coerceAtLeast(2) - 1)))
    }

    /** Name of the file currently shown in the editor. */
    fun openFileName(): String =
        ide.ideFrame().findAllText()
            .map { it.text }
            .firstOrNull { it.endsWith(".php") }
            .orEmpty()

    data class GutterGeometry(val height: Int, val lineHeight: Int, val lineCount: Int)
}
