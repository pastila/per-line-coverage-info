package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
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

    private val log = CoverageLog.get(CoverageApiSettingsConfigurable::class.java)

    private val enabledCheckBox = JCheckBox("Enable coverage plugin for this project")
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
            .addComponent(enabledCheckBox)
            .addSeparator()
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
        private val listModel = DefaultListModel<GitLabProject>()
        private val resultList = JBList(listModel)
        private val statusLabel = JLabel("Loading projects...")
        private var allProjects: List<GitLabProject> = emptyList()
        var selectedProject: GitLabProject? = null

        init {
            title = "Select GitLab Project"
            init()

            searchField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = filterList()
                override fun removeUpdate(e: DocumentEvent?) = filterList()
                override fun changedUpdate(e: DocumentEvent?) = filterList()
            })

            resultList.cellRenderer = ListCellRenderer { _, value, _, isSelected, _ ->
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

            loadProjects()
        }

        override fun createCenterPanel(): JComponent {
            val scrollPane = JScrollPane(resultList).apply {
                preferredSize = Dimension(JBUI.scale(500), JBUI.scale(300))
            }
            return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(searchField, BorderLayout.NORTH)
                add(scrollPane, BorderLayout.CENTER)
                add(statusLabel, BorderLayout.SOUTH)
                border = JBUI.Borders.empty(8)
            }
        }

        private fun loadProjects() {
            val domain = gitlabDomainField.text.trimEnd('/')
            val token = bearerTokenField.text

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val client = GitLabApiClient("https://$domain", token)
                    val projects = client.listMemberProjects()
                    log.debug("listMemberProjects returned ${projects.size} results")

                    SwingUtilities.invokeLater {
                        allProjects = projects
                        filterList()
                        statusLabel.text = "${projects.size} project(s) loaded"
                    }
                } catch (e: Exception) {
                    log.warn("load projects failed: ${e::class.simpleName}: ${e.message}", e)
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Failed to load: ${e.message}"
                    }
                }
            }
        }

        private fun filterList() {
            val query = searchField.text.trim().lowercase()
            listModel.clear()
            val filtered = if (query.isEmpty()) {
                allProjects
            } else {
                allProjects.filter { it.pathWithNamespace.lowercase().contains(query) || it.name.lowercase().contains(query) }
            }
            filtered.forEach { listModel.addElement(it) }
        }

        override fun doOKAction() {
            selectedProject = resultList.selectedValue
            super.doOKAction()
        }

        override fun getPreferredFocusedComponent(): JComponent = searchField
    }

    override fun isModified(): Boolean {
        val settings = CoverageApiSettings.getInstance()
        return enabledCheckBox.isSelected != settings.enabled ||
               gitlabDomainField.text != settings.gitlabDomain ||
               bearerTokenField.text != settings.bearerToken ||
               selectedProjectId != settings.gitlabProjectId ||
               selectedProjectName != settings.gitlabProjectName ||
               coverageBranchField.text != settings.coverageBranch
    }

    override fun apply() {
        val settings = CoverageApiSettings.getInstance()
        settings.enabled = enabledCheckBox.isSelected
        settings.gitlabDomain = gitlabDomainField.text
        settings.bearerToken = bearerTokenField.text
        settings.gitlabProjectId = selectedProjectId
        settings.gitlabProjectName = selectedProjectName
        settings.coverageBranch = coverageBranchField.text

        triggerCoverageLoad()
    }

    private fun triggerCoverageLoad() {
        val settings = CoverageApiSettings.getInstance()
        if (!settings.enabled) return
        if (settings.gitlabDomain.isBlank() || settings.bearerToken.isBlank() || settings.gitlabProjectId <= 0) return

        invokeLater {
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) {
                    CoverageLoadService.getInstance(project).loadOfflineFirst()
                }
            }
        }
    }

    override fun reset() {
        val settings = CoverageApiSettings.getInstance()
        enabledCheckBox.isSelected = settings.enabled
        gitlabDomainField.text = settings.gitlabDomain
        bearerTokenField.text = settings.bearerToken
        selectedProjectId = settings.gitlabProjectId
        selectedProjectName = settings.gitlabProjectName
        projectNameLabel.text = selectedProjectName
        coverageBranchField.text = settings.coverageBranch
    }
}