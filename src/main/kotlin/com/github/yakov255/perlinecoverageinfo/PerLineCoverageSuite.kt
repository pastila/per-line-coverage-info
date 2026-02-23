package com.github.yakov255.perlinecoverageinfo

import com.intellij.coverage.*

import com.intellij.openapi.project.Project

class PerLineCoverageSuite(
    name: String,
    project: Project,
    runner: CoverageRunner,
    fileProvider: CoverageFileProvider,
    timestamp: Long,
    engine: CoverageEngine
) : JavaCoverageSuite(name, fileProvider, null, null, timestamp, false, false, false, runner, engine, project)