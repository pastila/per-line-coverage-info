package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.plugins.cucumber.psi.GherkinFile
import org.jetbrains.plugins.cucumber.psi.GherkinStepsHolder

internal object CoverageTestNavigator {

    private val BEHAT_TEST_PATTERN = Regex("""^.+\.feature:\d+$""")

    fun isBehatTest(testName: String): Boolean = BEHAT_TEST_PATTERN.matches(testName)

    fun navigateToSourceLine(project: Project, filePath: String, lineNumber: Int) {
        if (filePath.isEmpty()) return
        val projectDir = project.guessProjectDir() ?: return
        val vf = VfsUtil.findRelativeFile(filePath, projectDir) ?: return
        val line = if (lineNumber > 0) lineNumber - 1 else 0
        FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, vf, line, 0), true)
    }

    fun navigateToTest(project: Project, testName: String) {
        if (isBehatTest(testName)) {
            navigateToBehatTest(project, testName)
            return
        }

        val methodName = testName.substringAfterLast("::", testName).substringAfterLast("\\", testName)
        val className = testName.substringBeforeLast("::", "").substringAfterLast("\\", "")

        if (className.isNotEmpty()) {
            @Suppress("DEPRECATION")
            val files = FilenameIndex.getFilesByName(project, "$className.php", GlobalSearchScope.projectScope(project))
            if (files.isNotEmpty()) {
                val psiFile = files.first()
                val vf = psiFile.virtualFile ?: return
                val document = psiFile.viewProvider.document ?: return
                val text = document.text
                val methodPattern = "function $methodName"
                val offset = text.indexOf(methodPattern)
                if (offset >= 0) {
                    FileEditorManager.getInstance(project)
                        .openTextEditor(OpenFileDescriptor(project, vf, offset), true)
                } else {
                    FileEditorManager.getInstance(project)
                        .openTextEditor(OpenFileDescriptor(project, vf, 0), true)
                }
            }
        }
    }

    fun navigateToBehatTest(project: Project, testName: String) {
        val featurePath = testName.substringBeforeLast(":", "")
        val lineStr = testName.substringAfterLast(":", "")
        val lineNumber = lineStr.toIntOrNull() ?: 0

        val projectDir = project.guessProjectDir() ?: return
        val vf = VfsUtil.findRelativeFile(featurePath, projectDir) ?: return

        val line = if (lineNumber > 0) lineNumber - 1 else 0
        FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, vf, line, 0), true)
    }

    /** Resolves a scenario name from a feature file path and line number string. */
    fun resolveScenarioName(project: Project, featurePath: String, lineStr: String): String? {
        val lineNumber = lineStr.toIntOrNull() ?: return null
        val projectDir = project.guessProjectDir() ?: return null
        val vf = VfsUtil.findRelativeFile(featurePath, projectDir) ?: return null
        return ReadAction.compute<String?, Throwable> {
            val psiFile = PsiManager.getInstance(project).findFile(vf) as? GherkinFile ?: return@compute null
            findScenarioAtLine(psiFile, lineNumber)
        }
    }

    /** Finds the scenario name at a given 1-based line number in a Gherkin file. */
    private fun findScenarioAtLine(gherkinFile: GherkinFile, lineNumber: Int): String? {
        val document = gherkinFile.viewProvider.document ?: return null
        val features = gherkinFile.features
        for (feature in features) {
            for (scenario in feature.scenarios) {
                if (scenario is GherkinStepsHolder) {
                    val scenarioLine = document.getLineNumber(scenario.textOffset) + 1
                    if (scenarioLine == lineNumber) {
                        return scenario.scenarioName
                    }
                }
            }
        }
        // Fallback: find the closest scenario at or before the line
        var closest: GherkinStepsHolder? = null
        for (feature in features) {
            for (scenario in feature.scenarios) {
                if (scenario is GherkinStepsHolder) {
                    val scenarioLine = document.getLineNumber(scenario.textOffset) + 1
                    if (scenarioLine <= lineNumber) {
                        closest = scenario
                    }
                }
            }
        }
        return closest?.scenarioName
    }
}
