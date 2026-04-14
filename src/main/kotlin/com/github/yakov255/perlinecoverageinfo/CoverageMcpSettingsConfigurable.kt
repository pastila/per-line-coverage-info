package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * Project-level settings UI for the MCP server.
 * Accessible at Settings → Tools → GitLab Coverage → MCP Server.
 */
class CoverageMcpSettingsConfigurable(private val project: Project) : Configurable {

    private val enabledCheckbox = JBCheckBox("Enable MCP server")
    private val portField = JBTextField(6)
    private val statusLabel = JLabel()
    private val snippetArea = JTextArea().apply {
        isEditable = false
        lineWrap = false
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
        border = JBUI.Borders.empty(6)
    }

    override fun getDisplayName(): String = "MCP Server"

    override fun createComponent(): JComponent {
        updateStatusLabel()
        updateSnippet()

        val portPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(portField, BorderLayout.CENTER)
        }

        val statusPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(statusLabel, BorderLayout.CENTER)
        }

        val copyButton = JButton("Copy to Clipboard").apply {
            addActionListener { copySnippetToClipboard() }
        }

        val snippetLabel = JBLabel("Add to your mcp.json or .claude.json:")
        snippetLabel.componentStyle = UIUtil.ComponentStyle.SMALL
        snippetLabel.fontColor = UIUtil.FontColor.BRIGHTER

        val snippetPanel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(snippetLabel, BorderLayout.NORTH)
            add(JScrollPane(snippetArea).apply {
                preferredSize = java.awt.Dimension(0, JBUI.scale(120))
            }, BorderLayout.CENTER)
            add(copyButton, BorderLayout.SOUTH)
        }

        enabledCheckbox.addChangeListener { updateSnippet() }
        portField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateSnippet()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateSnippet()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateSnippet()
        })

        return FormBuilder.createFormBuilder()
            .addComponent(enabledCheckbox)
            .addLabeledComponent("Port:", portPanel)
            .addLabeledComponent("Status:", statusPanel)
            .addSeparator()
            .addComponent(snippetPanel)
            .addSeparator()
            .addComponent(createHelpPanel())
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun buildSnippet(): String {
        val port = portField.text.trim().toIntOrNull() ?: CoverageMcpSettings.getInstance(project).mcpPort
        return """{
  "mcpServers": {
    "coverage": {
      "url": "http://localhost:$port/mcp"
    }
  }
}"""
    }

    private fun updateSnippet() {
        snippetArea.text = buildSnippet()
        snippetArea.caretPosition = 0
    }

    private fun createHelpPanel(): JComponent {
        val text = """
            <html>
            <body style="font-family: sans-serif; font-size: 11px;">
            <b>How to use with Claude CLI / Copilot CLI:</b><br><br>
            1. Enable the MCP server and load coverage in the IDE.<br>
            2. Copy the snippet above into your <code>mcp.json</code>, <code>.claude.json</code>,
               or Claude Desktop config.<br>
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

    private fun copySnippetToClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(buildSnippet()), null)
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
        updateSnippet()
    }
}
