package com.github.yakov255.perlinecoverageinfo

import com.intellij.coverage.*
import com.intellij.rt.coverage.data.ProjectData
import java.io.File

class PerLineCoverageRunner : CoverageRunner() {

    override fun getPresentableName(): String = "Per-Line Coverage Runner"

    override fun getId(): String = "per-line-coverage-runner"

    override fun getDataFileExtension(): String = "xml"

    override fun acceptsCoverageEngine(engine: CoverageEngine): Boolean = engine is PerLineCoverageEngine

    override fun loadCoverageData(
        sessionDataFile: File,
        baseCoverageSuite: CoverageSuite?,
        reporter: CoverageLoadErrorReporter
    ): CoverageLoadingResult {
        try {
            val settings = CoverageApiSettings.getInstance()
            val apiEndpoint = settings.apiUrl
            val bearerToken = settings.bearerToken
            val apiClient = CoverageApiClient(apiEndpoint, bearerToken)
            val apiResponse = apiClient.fetchCoverage()
            if (apiResponse == null) {
                val errorMsg = "Failed to fetch coverage data from API"
                reporter.reportError(RuntimeException(errorMsg))
                return FailedCoverageLoadingResult(errorMsg, RuntimeException(errorMsg))
            }
            val converter = CoverageDataConverter()
            val data: ProjectData = converter.convert(apiResponse)
            return SuccessCoverageLoadingResult(data)
        } catch (e: Exception) {
            reporter.reportError(e)
            return FailedCoverageLoadingResult(e.message ?: "Failed to load coverage data", e)
        }
    }






}