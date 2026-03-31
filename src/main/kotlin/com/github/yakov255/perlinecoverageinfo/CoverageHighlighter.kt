package com.github.yakov255.perlinecoverageinfo

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil

object CoverageHighlighter {

    private const val COVERAGE_LAYER = HighlighterLayer.LAST + 1
    val COVERAGE_HIGHLIGHTER_KEY = Key.create<Boolean>("PER_LINE_COVERAGE_HIGHLIGHTER")

    fun applyToOpenEditors(project: Project) {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasData()) return

        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project != project) continue
            applyToEditor(editor, project)
        }
    }

    fun applyToEditor(editor: Editor, project: Project) {
        val dataService = CoverageDataService.getInstance(project)
        if (!dataService.hasData()) return

        val document = editor.document
        val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return

        val lineMappingService = LineMappingService.getInstance(project)
        val coverageLines = lineMappingService.getMappedCoverage(virtualFile.path, document.text)
            ?: findCoverageForFile(virtualFile.path, project)
            ?: return

        clearCoverageHighlighters(editor)

        val markupModel = editor.markupModel

        for (line in 0 until document.lineCount) {
            val lineNumber = line + 1 // coverage data is 1-based
            val tests = coverageLines[lineNumber] ?: continue

            val covered = tests.isNotEmpty()
            val attrKey = if (covered) CodeInsightColors.LINE_FULL_COVERAGE else CodeInsightColors.LINE_NONE_COVERAGE
            val startOffset = document.getLineStartOffset(line)
            val endOffset = document.getLineEndOffset(line)
            val highlighter = markupModel.addRangeHighlighter(
                startOffset, endOffset, COVERAGE_LAYER,
                null, HighlighterTargetArea.LINES_IN_RANGE
            )
            highlighter.lineMarkerRenderer = CoverageGutterRenderer(lineNumber, tests, attrKey)
            highlighter.putUserData(COVERAGE_HIGHLIGHTER_KEY, true)
        }
    }

    fun clearCoverageHighlighters(editor: Editor) {
        val toRemove = editor.markupModel.allHighlighters.filter {
            it.getUserData(COVERAGE_HIGHLIGHTER_KEY) == true
        }
        for (h in toRemove) {
            editor.markupModel.removeHighlighter(h)
        }
    }

    private fun findCoverageForFile(absolutePath: String, project: Project): Map<Int, List<String>>? {
        val dataService = CoverageDataService.getInstance(project)
        val basePath = project.basePath ?: return null
        val baseVf = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return null
        val fileVf = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return null
        val relativePath = VfsUtil.getRelativePath(fileVf, baseVf) ?: return null

        return dataService.getCoverage(absolutePath)
            ?: dataService.getCoverage(relativePath)
            ?: dataService.getCoverage("/$relativePath")
            ?: dataService.allFiles().firstNotNullOfOrNull { storedPath ->
                if (storedPath.endsWith(relativePath) || relativePath.endsWith(storedPath.trimStart('/'))) {
                    dataService.getCoverage(storedPath)
                } else null
            }
    }
}
