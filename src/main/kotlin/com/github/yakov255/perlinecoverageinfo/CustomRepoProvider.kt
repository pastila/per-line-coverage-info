package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.updateSettings.impl.UpdateSettingsProvider

class CustomRepoProvider : UpdateSettingsProvider {
    override fun getPluginRepositories(): List<String> =
        listOf("https://pastila.github.io/per-line-coverage-info/updatePlugins.xml")
}
