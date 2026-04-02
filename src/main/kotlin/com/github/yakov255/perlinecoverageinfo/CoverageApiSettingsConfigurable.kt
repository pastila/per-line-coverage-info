package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class CoverageApiSettingsConfigurable : Configurable {

    private val apiUrlField = JBTextField()
    private val bearerTokenField = JBTextField()
    private val mergeBaseBranchField = JBTextField()
    private val apiBranchNameField = JBTextField()

    override fun getDisplayName(): String = "Coverage API"

    override fun createComponent(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("API URL:", apiUrlField)
            .addLabeledComponent("Bearer Token:", bearerTokenField)
            .addLabeledComponent("Merge Base Branch (git):", mergeBaseBranchField)
            .addLabeledComponent("API Branch Name (leave empty to use above):", apiBranchNameField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = CoverageApiSettings.getInstance()
        return apiUrlField.text != settings.apiUrl ||
               bearerTokenField.text != settings.bearerToken ||
               mergeBaseBranchField.text != settings.mergeBaseBranch ||
               apiBranchNameField.text != settings.apiBranchName
    }

    override fun apply() {
        val settings = CoverageApiSettings.getInstance()
        settings.apiUrl = apiUrlField.text
        settings.bearerToken = bearerTokenField.text
        settings.mergeBaseBranch = mergeBaseBranchField.text
        settings.apiBranchName = apiBranchNameField.text
    }

    override fun reset() {
        val settings = CoverageApiSettings.getInstance()
        apiUrlField.text = settings.apiUrl
        bearerTokenField.text = settings.bearerToken
        mergeBaseBranchField.text = settings.mergeBaseBranch
        apiBranchNameField.text = settings.apiBranchName
    }
}