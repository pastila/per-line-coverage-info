package com.github.yakov255.perlinecoverageinfo.uitest

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JTableFixture
import com.intellij.remoterobot.search.locators.byXpath
import java.time.Duration

/**
 * The "Coverage Tests" tool window with its five tabs.
 *
 * Tab switches are driven through RemoteRobot's generated component ids (the same trick
 * RemoteRobot's own fixtures use for tabs) rather than through absolute screen coordinates.
 */
class CoverageToolWindow(private val ide: IdeRobot) {

    fun open(): CoverageToolWindow {
        // The new UI shows only an icon in the stripe, so open the window by its activate action
        // (the platform derives "Activate<IdWithoutSpaces>ToolWindow" from the tool window id).
        ide.invokeAction("ActivateCoverageTestsToolWindow")
        ide.await("Coverage Tests tool window did not open", Duration.ofSeconds(30)) {
            runCatching { toolWindow() }.getOrNull()
        }
        return this
    }

    fun toolWindow(): CommonContainerFixture =
        ide.robot.find(
            CommonContainerFixture::class.java,
            // Identified by its own tabs: the window title is not a ContentTabLabel in the new UI.
            byXpath("//div[@class='InternalDecoratorImpl'][.//div[@class='ContentTabLabel' and @text='Covering Line']]"),
            Duration.ofSeconds(20),
        )

    /** Tab titles currently shown by the tool window. */
    fun tabTitles(): List<String> {
        val frame = toolWindow()
        return frame.findAllText()
            .map { it.text }
            .filter { it in EXPECTED_TABS }
            .distinct()
    }

    fun selectTab(title: String) {
        val tab = toolWindow().find(
            ComponentFixture::class.java,
            byXpath(".//div[@class='ContentTabLabel' and @text='$title']"),
            Duration.ofSeconds(20),
        )
        tab.click()
    }

    /** Rows of the Artifacts table (header excluded). */
    fun artifactRows(): List<List<String>> {
        selectTab("Artifacts")
        val table = toolWindow().find(JTableFixture::class.java, JTableFixture.byType(), Duration.ofSeconds(20))
        return table.collectItems()
            .filter { it.isNotEmpty() && !it[0].equals("File", ignoreCase = true) }
    }

    fun clickButton(text: String) {
        toolWindow().find(JButtonFixture::class.java, JButtonFixture.byText(text)).click()
    }

    companion object {
        val EXPECTED_TABS = listOf(
            "Covering Line",
            "Affected by Changes",
            "New Coverage",
            "Artifacts",
            "Log",
        )

        const val ID = "Coverage Tests"
    }
}
