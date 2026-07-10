package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.JBUI
import javax.swing.*

class GitLabTokenDialog(project: Project?) : DialogWrapper(project) {

    private val tokenField = JBPasswordField()
    private val tokenLink = HyperlinkLabel("Create a Personal Access Token (scope: read_api)")

    val token: String get() = String(tokenField.password)

    init {
        title = "GitLab Coverage — Setup"
        init()
        val domain = CoverageApiSettings.getInstance().gitlabDomain.trimEnd('/')
        tokenLink.setHyperlinkTarget("https://$domain/-/user_settings/personal_access_tokens")
        tokenLink.addHyperlinkListener {
            BrowserUtil.browse("https://$domain/-/user_settings/personal_access_tokens")
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel()
        panel.layout = GroupLayout(panel).apply {
            setAutoCreateGaps(true)
            setAutoCreateContainerGaps(true)

            val tokenLabel = JLabel("Access Token:")

            setHorizontalGroup(
                createSequentialGroup()
                    .addComponent(tokenLabel)
                    .addGroup(
                        createParallelGroup(GroupLayout.Alignment.LEADING)
                            .addComponent(tokenField, GroupLayout.DEFAULT_SIZE, 300, Short.MAX_VALUE.toInt())
                            .addComponent(tokenLink)
                    )
            )
            setVerticalGroup(
                createSequentialGroup()
                    .addGroup(
                        createParallelGroup(GroupLayout.Alignment.CENTER)
                            .addComponent(tokenLabel)
                            .addComponent(tokenField)
                    )
                    .addComponent(tokenLink)
            )
        }
        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = tokenField
}
