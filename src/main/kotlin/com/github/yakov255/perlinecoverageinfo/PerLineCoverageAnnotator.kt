package com.github.yakov255.perlinecoverageinfo

import com.intellij.coverage.analysis.JavaCoverageAnnotator
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class PerLineCoverageAnnotator(project: Project?) : JavaCoverageAnnotator(project) {

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PerLineCoverageAnnotator {
            return project.getService(PerLineCoverageAnnotator::class.java)
        }
    }
}