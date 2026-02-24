package com.github.yakov255.perlinecoverageinfo

import com.intellij.rt.coverage.data.ProjectData
import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.LineCoverage

class CoverageDataConverter {

    fun convert(apiResponse: ApiCoverageResponse): ProjectData {
        val projectData = ProjectData()

        for (fileCoverage in apiResponse.files) {
            val filePath = fileCoverage.filePath
            val className = filePath // Use file path as class name for PHP
            val classData = projectData.getOrCreateClassData(className)

            val maxLine = fileCoverage.lines.keys.map { it.toInt() }.maxOrNull() ?: 0
            val lines = arrayOfNulls<LineData>(maxLine + 1)

            for ((lineNumberStr, testSetIndex) in fileCoverage.lines) {
                val lineNumber = lineNumberStr.toInt()
                val covered = testSetIndex != -1
                val hits = if (covered) fileCoverage.testSets.getOrNull(testSetIndex)?.size ?: 0 else 0
                val methodSignature = "" // No method signature for PHP
                val lineData = LineData(lineNumber, methodSignature)
                lineData.setStatus(if (covered) LineCoverage.FULL else LineCoverage.NONE)
                lineData.setHits(hits)
                lineData.fillArrays()
                lines[lineNumber] = lineData
            }

            classData.setLines(lines)
        }

        return projectData
    }
}