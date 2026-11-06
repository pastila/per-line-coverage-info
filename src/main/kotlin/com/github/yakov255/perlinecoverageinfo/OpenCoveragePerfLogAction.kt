package com.github.yakov255.perlinecoverageinfo

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import java.io.File

/**
 * Find Action that reveals the directory with the per-run coverage performance logs
 * (`~/.cache/coverage-plugin/perf/`).
 */
class OpenCoveragePerfLogAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val current = CoveragePerfLog.currentFile()
        val target: File = (current?.takeIf { it.exists() }?.parentFile) ?: CoveragePerfLog.directory()
        target.mkdirs()
        RevealFileAction.openFile(target)
    }
}
