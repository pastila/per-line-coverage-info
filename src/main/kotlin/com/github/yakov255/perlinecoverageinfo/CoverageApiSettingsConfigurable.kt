package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class CoverageApiSettingsConfigurable : Configurable {

    private val gitlabDomainField = JBTextField()
    private val bearerTokenField = JBTextField()
    private val coverageBranchField = JBTextField()
    private val projectNameLabel = JBTextField().apply { isEditable = false }
    private val tokenLink = HyperlinkLabel("Create a Personal Access Token (scope: read_api)")

    private var selectedProjectId: Long = 0
    private var selectedProjectName: String = ""

    override fun getDisplayName(): String = "GitLab Coverage"

    override fun createComponent(): JComponent {
        updateTokenLinkUrl()

        gitlabDomainField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateTokenLinkUrl()
            override fun removeUpdate(e: DocumentEvent?) = updateTokenLinkUrl()
            override fun changedUpdate(e: DocumentEvent?) = updateTokenLinkUrl()
        })

        tokenLink.addHyperlinkListener {
            BrowserUtil.browse("https://${gitlabDomainField.text.trimEnd('/')}/-/user_settings/personal_access_tokens")
        }

        val searchButton = JButton("Search...").apply {
            addActionListener { showProjectSearchDialog() }
        }
        val projectPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            add(projectNameLabel, BorderLayout.CENTER)
            add(searchButton, BorderLayout.EAST)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("GitLab Domain:", gitlabDomainField)
            .addLabeledComponent("Access Token:", bearerTokenField)
            .addComponentToRightColumn(tokenLink)
            .addLabeledComponent("GitLab Project:", projectPanel)
            .addLabeledComponent("Coverage Branch:", coverageBranchField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun updateTokenLinkUrl() {
        val domain = gitlabDomainField.text.trimEnd('/')
        tokenLink.setHyperlinkTarget("https://$domain/-/user_settings/personal_access_tokens")
    }

    private fun showProjectSearchDialog() {
        val dialog = ProjectSearchDialog()
        if (dialog.showAndGet()) {
            dialog.selectedProject?.let { project ->
                selectedProjectId = project.id
                selectedProjectName = project.pathWithNamespace
                projectNameLabel.text = selectedProjectName
            }
        }
    }

    private inner class ProjectSearchDialog : DialogWrapper(true) {
        private val searchField = JBTextField()
        private val searchButton = JButton("Search")
        private val listModel = DefaultListModel<GitLabProject>()
        private val resultList = JBList(listModel)
        var selectedProject: GitLabProject? = null

        init {
            title = "Search GitLab Projects"
            init()

            searchButton.addActionListener { performSearch() }
            searchField.addActionListener { performSearch() }
            resultList.cellRenderer = ListCellRenderer { _, value, _, isSelected, cellHasFocus ->
                JLabel(value?.pathWithNamespace ?: "").apply {
                    isOpaque = true
                    if (isSelected) {
                        background = resultList.selectionBackground
                        foreground = resultList.selectionForeground
                    }
                    border = JBUI.Borders.empty(4, 8)
                }
            }
            resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        override fun createCenterPanel(): JComponent {
            val searchPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                add(searchField, BorderLayout.CENTER)
                add(searchButton, BorderLayout.EAST)
            }
            val scrollPane = JScrollPane(resultList).apply {
                preferredSize = Dimension(JBUI.scale(500), JBUI.scale(300))
            }
            return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(searchPanel, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
                border = JBUI.Borders.empty(8)
            }
        }

        private fun performSearch() {
            val query = searchField.text.trim()
            if (query.isEmpty()) return

            listModel.clear()
            try {
                val domain = gitlabDomainField.text.trimEnd('/')
                val client = GitLabApiClient("https://$domain", bearerTokenField.text)
                val projects = client.searchProjects(query)
                projects.forEach { listModel.addElement(it) }
            } catch (e: Exception) {
                listModel.clear()
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Search failed: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }

        override fun doOKAction() {
            selectedProject = resultList.selectedValue
            super.doOKAction()
        }

        override fun getPreferredFocusedComponent(): JComponent = searchField
    }

    override fun isModified(): Boolean {
        val settings = CoverageApiSettings.getInstance()
        return gitlabDomainField.text != settings.gitlabDomain ||
               bearerTokenField.text != settings.bearerToken ||
               selectedProjectId != settings.gitlabProjectId ||
               selectedProjectName != settings.gitlabProjectName ||
               coverageBranchField.text != settings.coverageBranch
    }

    override fun apply() {
        val settings = CoverageApiSettings.getInstance()
        settings.gitlabDomain = gitlabDomainField.text
        settings.bearerToken = bearerTokenField.text
        settings.gitlabProjectId = selectedProjectId
        settings.gitlabProjectName = selectedProjectName
        settings.coverageBranch = coverageBranchField.text
    }

    override fun reset() {
        val settings = CoverageApiSettings.getInstance()
        gitlabDomainField.text = settings.gitlabDomain
        bearerTokenField.text = settings.bearerToken
        selectedProjectId = settings.gitlabProjectId
        selectedProjectName = settings.gitlabProjectName
        projectNameLabel.text = selectedProjectName
        coverageBranchField.text = settings.coverageBranch
    }
}