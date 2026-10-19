package com.github.yakov255.perlinecoverageinfo

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Makes a Behat command line write a `.covt` into the local coverage directory (see [LocalCoverageCommand]).
 * Shared by the run configuration extensions of PhpStorm ([LocalCoverageRunExtension]) and Raketa ([RaketaLocalCoverageRunExtension]).
 */
object LocalCoverageCommandLine {

    private val log = CoverageLog.get(LocalCoverageCommandLine::class.java)

    fun patch(project: Project, configurationName: String, cmdLine: GeneralCommandLine) {
        val gitRoot = LocalCoverageService.getInstance(project).gitRootOrNull() ?: run {
            log.info("LocalCoverage run: no git root, command line left as is")
            return
        }
        val result = LocalCoverageCommand.patch(
            parameters = cmdLine.parametersList.list,
            coverageDir = CoverageApiSettings.getInstance().localCoverageDir,
            isLocalDir = { File(gitRoot, it).isDirectory },
            readLocalFile = { File(gitRoot, it).takeIf(File::isFile)?.readText() },
            environment = cmdLine.environment,
        )
        when (result) {
            is LocalCoverageCommand.Result.Patched -> {
                cmdLine.parametersList.clearAll()
                cmdLine.parametersList.addAll(result.parameters)
                log.info("LocalCoverage run: collecting coverage for ${result.serviceDir} ($configurationName)")
            }
            is LocalCoverageCommand.Result.Skipped ->
                log.info(
                    "LocalCoverage run: not collecting for $configurationName — ${result.reason}; " +
                        "exe=${cmdLine.exePath}, parameters=${cmdLine.parametersList.list}",
                )
        }
    }
}
