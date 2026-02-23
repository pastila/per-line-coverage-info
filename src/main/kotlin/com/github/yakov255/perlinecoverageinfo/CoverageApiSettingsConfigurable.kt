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

    override fun getDisplayName(): String = "Coverage API"

    override fun createComponent(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("API URL:", apiUrlField)
            .addLabeledComponent("Bearer Token:", bearerTokenField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = CoverageApiSettings.getInstance()
        return apiUrlField.text != settings.apiUrl ||
               bearerTokenField.text != settings.bearerToken
    }

    override fun apply() {
        val settings = CoverageApiSettings.getInstance()
        settings.apiUrl = apiUrlField.text
        settings.bearerToken = bearerTokenField.text
    }

    override fun reset() {
        val settings = CoverageApiSettings.getInstance()
        apiUrlField.text = settings.apiUrl
        bearerTokenField.text = settings.bearerToken
    }
}