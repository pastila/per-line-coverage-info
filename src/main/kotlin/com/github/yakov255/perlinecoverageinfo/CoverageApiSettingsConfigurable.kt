package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
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

    // The plugin is an application service, so the switch applies to the whole IDE installation.
    private val enabledCheckBox = JCheckBox("Enable the coverage plugin for this IDE installation")
    private val gitlabDomainField = JBTextField()

    // Password field: the stored token is never shown, and a new one is never logged.
    private val bearerTokenField = JBPasswordField()
    private val clearTokenCheckBox = JCheckBox("Remove the stored token when applied")
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

        bearerTokenField.toolTipText =
            "Stored in the IDE credential store. Leave empty to keep the current token."
        clearTokenCheckBox.addItemListener {
            if (clearTokenCheckBox.isSelected) {
                bearerTokenField.text = ""
                bearerTokenField.isEnabled = false
            } else {
                bearerTokenField.isEnabled = true
            }
        }

        tokenLink.addHyperlinkListener {
            BrowserUtil.browse(tokenSettingsUrl())
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
            .addComponentToRightColumn(clearTokenCheckBox)
            .addLabeledComponent("GitLab Project:", projectPanel)
            .addLabeledComponent("Coverage Branch:", coverageBranchField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    /** Same base URL as the loader: an explicit `http://host:port` domain is kept as it is. */
    private fun tokenSettingsUrl(): String =
        "${CoverageApiSettings.baseUrlFor(gitlabDomainField.text)}/-/user_settings/personal_access_tokens"

    private fun updateTokenLinkUrl() {
        tokenLink.setHyperlinkTarget(tokenSettingsUrl())
    }

    /** What the user typed; `""` means "keep the stored token". */
    private fun typedToken(): String = String(bearerTokenField.password)

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
            val baseUrl = CoverageApiSettings.baseUrlFor(gitlabDomainField.text)
            val typed = typedToken()

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // An empty field means the user did not retype the token: use the stored one.
                    val token = typed.ifEmpty { CoverageApiSettings.getInstance().bearerToken }
                    val client = GitLabProjectsClient(baseUrl, token)
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
               tokenModified(settings) ||
               selectedProjectId != settings.gitlabProjectId ||
               selectedProjectName != settings.gitlabProjectName ||
               coverageBranchField.text != settings.coverageBranch
    }

    /**
     * The token counts as changed only when the user typed a new one or asked to remove it. The
     * stored value is never copied into the field, so an untouched page is never "modified".
     */
    private fun tokenModified(settings: CoverageApiSettings): Boolean {
        if (clearTokenCheckBox.isSelected) return true
        val typed = typedToken()
        return typed.isNotEmpty() && typed != settings.bearerToken
    }

    override fun apply() {
        val settings = CoverageApiSettings.getInstance()
        settings.enabled = enabledCheckBox.isSelected
        settings.gitlabDomain = gitlabDomainField.text
        val typed = typedToken()
        when {
            clearTokenCheckBox.isSelected -> settings.bearerToken = ""
            typed.isNotEmpty() -> settings.bearerToken = typed
        }
        // Never keep the secret in a widget after it was saved.
        bearerTokenField.text = ""
        clearTokenCheckBox.isSelected = false
        settings.gitlabProjectId = selectedProjectId
        settings.gitlabProjectName = selectedProjectName
        settings.coverageBranch = coverageBranchField.text

        triggerCoverageLoad()
    }

    private fun triggerCoverageLoad() {
        CoverageSettingsActions.afterSettingsApplied()
    }

    override fun reset() {
        val settings = CoverageApiSettings.getInstance()
        enabledCheckBox.isSelected = settings.enabled
        gitlabDomainField.text = settings.gitlabDomain
        // The stored token is not shown: the empty field means "keep it".
        bearerTokenField.text = ""
        bearerTokenField.isEnabled = true
        clearTokenCheckBox.isSelected = false
        selectedProjectId = settings.gitlabProjectId
        selectedProjectName = settings.gitlabProjectName
        projectNameLabel.text = selectedProjectName
        coverageBranchField.text = settings.coverageBranch
    }
}
