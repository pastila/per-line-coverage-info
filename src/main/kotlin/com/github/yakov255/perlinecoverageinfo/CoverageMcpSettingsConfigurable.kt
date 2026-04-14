package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Project-level settings UI for the MCP server.
 * Accessible at Settings → Tools → Coverage MCP Server.
 */
class CoverageMcpSettingsConfigurable(private val project: Project) : Configurable {

    private val enabledCheckbox = JBCheckBox("Enable MCP server (for Claude / Copilot CLI)")
    private val portField = JBTextField(6)
    private val statusLabel = JLabel()

    override fun getDisplayName(): String = "Coverage MCP Server"

    override fun createComponent(): JComponent {
        updateStatusLabel()

        val configSnippet = JButton("Copy config snippet").apply {
            toolTipText = "Copies the MCP server configuration JSON to the clipboard"
            addActionListener { copyConfigSnippet() }
        }

        val portPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(JLabel("Port:"), BorderLayout.WEST)
            add(portField, BorderLayout.CENTER)
        }

        val statusPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(JLabel("Status:"), BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }

        return FormBuilder.createFormBuilder()
            .addComponent(enabledCheckbox)
            .addLabeledComponent("", portPanel)
            .addLabeledComponent("", statusPanel)
            .addLabeledComponent("", configSnippet)
            .addSeparator()
            .addComponent(createHelpPanel())
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun createHelpPanel(): JComponent {
        val text = """
            <html>
            <body style="font-family: sans-serif; font-size: 11px; padding: 6px;">
            <b>How to use with Claude CLI / Copilot CLI:</b><br><br>
            1. Make sure the MCP server is enabled and coverage is loaded in the IDE.<br>
            2. Add the config snippet to your Claude Desktop config or <code>.claude.json</code>:<br>
            <pre style="background: #f5f5f5; padding: 8px; margin-top: 4px;">
{
  "mcpServers": {
    "coverage": {
      "url": "http://localhost:PORT/mcp"
    }
  }
}</pre>
            3. The LLM can then call <code>get_coverage_for_file</code> to see per-line coverage.<br>
            </body></html>
        """.trimIndent()
        return JLabel(text)
    }

    private fun updateStatusLabel() {
        val mcpServer = McpServer.getInstance(project)
        statusLabel.text = when {
            mcpServer.isRunning -> "Running on http://127.0.0.1:${mcpServer.boundPort}/mcp"
            !CoverageMcpSettings.getInstance(project).mcpEnabled -> "Disabled"
            else -> "Stopped"
        }
    }

    private fun copyConfigSnippet() {
        val port = portField.text.trim().toIntOrNull() ?: CoverageMcpSettings.getInstance(project).mcpPort
        val snippet = """{
  "mcpServers": {
    "coverage": {
      "url": "http://localhost:$port/mcp"
    }
  }
}"""
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(snippet), null)
    }

    override fun isModified(): Boolean {
        val settings = CoverageMcpSettings.getInstance(project)
        return enabledCheckbox.isSelected != settings.mcpEnabled ||
            portField.text.trim() != settings.mcpPort.toString()
    }

    override fun apply() {
        val settings = CoverageMcpSettings.getInstance(project)
        val newEnabled = enabledCheckbox.isSelected
        val newPort = portField.text.trim().toIntOrNull() ?: settings.mcpPort
        settings.mcpEnabled = newEnabled
        settings.mcpPort = newPort
        McpServer.getInstance(project).restart(newPort, newEnabled)
        updateStatusLabel()
    }

    override fun reset() {
        val settings = CoverageMcpSettings.getInstance(project)
        enabledCheckbox.isSelected = settings.mcpEnabled
        portField.text = settings.mcpPort.toString()
        updateStatusLabel()
    }
}
