package com.github.yakov255.perlinecoverageinfo

import com.intellij.coverage.*
import com.intellij.coverage.view.DirectoryCoverageViewExtension
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class PerLineCoverageEngine : CoverageEngine() {
    override fun createCoverageSuite(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long): CoverageSuite? {
        return PerLineCoverageSuite(name, project, runner, fileProvider, timestamp, this)
    }

    override fun createCoverageSuite(name: String, project: Project, runner: CoverageRunner, fileProvider: CoverageFileProvider, timestamp: Long, config: CoverageEnabledConfiguration): CoverageSuite? {
        error("Should not be called")
    }

    override fun createEmptyCoverageSuite(coverageRunner: CoverageRunner): PerLineCoverageSuite? {
        return null
    }

    override fun coverageEditorHighlightingApplicableTo(psiFile: PsiFile) = psiFile.language.id == "PHP"

    override fun acceptedByFilters(psiFile: PsiFile, suite: CoverageSuitesBundle): Boolean {
        return psiFile.language.id == "PHP"
    }

    override fun createCoverageViewExtension(project: Project, suiteBundle: CoverageSuitesBundle?) =
        DirectoryCoverageViewExtension(project, getCoverageAnnotator(project), suiteBundle)

    override fun createSrcFileAnnotator(file: PsiFile?, editor: Editor?): CoverageEditorAnnotator = PerLineCoverageEditorAnnotator(file, editor)

    override fun isApplicableTo(conf: RunConfigurationBase<*>) = false

    override fun getPresentableText() = "Per-Line Coverage Info"

    override fun getCoverageAnnotator(project: Project) = PerLineCoverageAnnotator.getInstance(project)

    override fun getQualifiedNames(sourceFile: PsiFile) = error("Should not be called")

    override fun createCoverageEnabledConfiguration(conf: RunConfigurationBase<*>) = error("Should not be called")
}