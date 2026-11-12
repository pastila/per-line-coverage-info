package com.github.yakov255.perlinecoverageinfo.uitest

import com.intellij.remoterobot.fixtures.CommonContainerFixture
import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.JButtonFixture
import com.intellij.remoterobot.fixtures.JCheckboxFixture
import com.intellij.remoterobot.fixtures.JLabelFixture
import com.intellij.remoterobot.fixtures.JListFixture
import com.intellij.remoterobot.fixtures.JTextFieldFixture
import com.intellij.remoterobot.fixtures.JTreeFixture
import com.intellij.remoterobot.search.locators.byXpath
import java.time.Duration

/**
 * Drives Settings → Tools → GitLab Coverage.
 *
 * Fields are located by their (unique) value or by label+relative xpath, never by index: the
 * Settings panel is a FormBuilder grid whose component order changes with unrelated IDE updates.
 */
class CoverageSettingsDialog(private val ide: IdeRobot) {

    /**
     * A clean first run shows the modal "GitLab Coverage — Setup" token prompt, which blocks the
     * Settings action. Enter the token there and confirm, like a real user. The prompt appears
     * only after the project opens, hence the wait; absence is not an error.
     */
    fun fillFirstRunPrompt(token: String): CoverageSettingsDialog {
        val prompt = runCatching {
            ide.robot.find(
                CommonContainerFixture::class.java,
                byXpath("//div[@title='GitLab Coverage — Setup']"),
                Duration.ofSeconds(90),
            )
        }.getOrNull() ?: return this
        // JBPasswordField is a JPasswordField, not a JTextField fixture type: locate it by class.
        prompt.find(JTextFieldFixture::class.java, byXpath("//div[@class='JBPasswordField']"), Duration.ofSeconds(10))
            .text = token
        prompt.find(JButtonFixture::class.java, JButtonFixture.byText("OK")).click()
        ide.await("Token prompt did not close", Duration.ofSeconds(30)) {
            runCatching {
                ide.robot.find(
                    ComponentFixture::class.java,
                    byXpath("//div[@title='GitLab Coverage — Setup']"),
                    Duration.ofSeconds(1),
                )
            }.getOrNull() == null
        }
        return this
    }

    fun open(): CoverageSettingsDialog {
        ide.invokeAction(IdeRobot.SETTINGS_ACTION)
        // The Settings dialog is a MyDialog titled "Settings".
        ide.robot.find(
            CommonContainerFixture::class.java,
            byXpath("//div[@title='Settings' and @class='MyDialog']"),
            Duration.ofSeconds(30),
        )
        selectConfigurable()
        return this
    }

    /** Clicks the "GitLab Coverage" node in the Settings tree. */
    private fun selectConfigurable() {
        // Settings navigation is a tree (SettingsTreeView), not labels; the node sits under Tools.
        val dialog = ide.robot.find(
            CommonContainerFixture::class.java,
            byXpath("//div[@title='Settings' and @class='MyDialog']"),
            Duration.ofSeconds(30),
        )
        val tree = dialog.find(JTreeFixture::class.java, JTreeFixture.byType(), Duration.ofSeconds(30))
        // "Tools" is collapsed and expanding it through the robot times out on the IDE side.
        // Click a top-level row to focus the tree, then use the tree's speed search: typing the
        // node name expands the parents and selects the match.
        tree.clickRow(0)
        ide.keyboard.enterText("GitLab Coverage")
        ide.await("GitLab Coverage settings page did not open", Duration.ofSeconds(20)) {
            runCatching { panel() }.getOrNull()
        }
    }

    private fun panel(): CommonContainerFixture =
        ide.robot.find(CommonContainerFixture::class.java, byXpath("//div[@class='ConfigurableCardPanel']"), Duration.ofSeconds(20))

    fun enablePlugin(): CoverageSettingsDialog {
        val checkbox = panel().find(
            JCheckboxFixture::class.java,
            byXpath("//div[@class='JCheckBox' and contains(@accessiblename, 'Enable coverage plugin')]"),
            Duration.ofSeconds(20),
        )
        if (!checkbox.isSelected()) checkbox.select()
        return this
    }

    fun setDomain(domain: String): CoverageSettingsDialog {
        field("GitLab Domain:").text = domain
        return this
    }

    fun setToken(token: String): CoverageSettingsDialog {
        field("Access Token:").text = token
        return this
    }

    fun setBranch(branch: String): CoverageSettingsDialog {
        field("Coverage Branch:").text = branch
        return this
    }

    /**
     * A FormBuilder row is `[JBLabel][JBTextField]` inside one grid cell: locate the label by
     * its text and take the text field that follows it.
     */
    private fun field(label: String): JTextFieldFixture =
        panel().find(
            JTextFieldFixture::class.java,
            // Verified against the live hierarchy: the field carries its label as accessiblename.
            byXpath("//div[@class='JBTextField' and @accessiblename='$label']"),
            Duration.ofSeconds(20),
        )

    /** Opens the project search dialog, types [query] and selects [projectPath]. */
    fun selectProject(query: String, projectPath: String): CoverageSettingsDialog {
        panel().find(JButtonFixture::class.java, JButtonFixture.byText("Search...")).click()

        val dialog = ide.robot.find(
            CommonContainerFixture::class.java,
            byXpath("//div[@class='MyDialog' and @title='Select GitLab Project']"),
            Duration.ofSeconds(20),
        )

        val search = dialog.find(JTextFieldFixture::class.java, JTextFieldFixture.byType(), Duration.ofSeconds(20))
        search.text = query

        // The dialog's renderer is a bare JLabel, so the robot reads empty item texts. Read the
        // list model instead: its elements are GitLabProject data classes, whose toString()
        // carries `pathWithNamespace=<path>`. Projects arrive over the network, so poll.
        val list = dialog.find(JListFixture::class.java, JListFixture.byType(), Duration.ofSeconds(20))
        fun modelItems(): List<String> =
            list.callJs<String>(
                "var m = component.getModel(); var r = []; " +
                    "for (var i = 0; i < m.getSize(); i++) { r.push('' + m.getElementAt(i)); } " +
                    "r.join('\\n');",
            ).split('\n').filter { it.isNotBlank() }

        val index = try {
            ide.await("GitLab project '$projectPath' not found in the search dialog", Duration.ofSeconds(90), Duration.ofSeconds(2)) {
                modelItems().indexOfFirst { Regex("pathWithNamespace=${Regex.escape(projectPath)}[,)]").containsMatchIn(it) }
                    .takeIf { it >= 0 }
            }
        } catch (e: Throwable) {
            throw AssertionError("Project dialog items: ${runCatching { modelItems() }}", e)
        }

        list.clickItemAtIndex(index)
        dialog.find(JButtonFixture::class.java, JButtonFixture.byText("OK")).click()
        return this
    }

    fun applyAndClose() {
        val frame = ide.robot.find(
            CommonContainerFixture::class.java,
            byXpath("//div[@title='Settings' and @class='MyDialog']"),
            Duration.ofSeconds(20),
        )
        frame.find(JButtonFixture::class.java, JButtonFixture.byText("Apply")).click()
        frame.find(JButtonFixture::class.java, JButtonFixture.byText("OK")).click()
        ide.await("Settings dialog did not close", Duration.ofSeconds(30)) {
            runCatching {
                ide.robot.find(
                    ComponentFixture::class.java,
                    byXpath("//div[@title='Settings' and @class='MyDialog']"),
                    Duration.ofSeconds(1),
                )
            }.getOrNull() == null
        }
    }
}
