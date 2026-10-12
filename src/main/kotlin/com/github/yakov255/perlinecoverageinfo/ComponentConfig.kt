package com.github.yakov255.perlinecoverageinfo

import java.io.File

data class ComponentEntry(
    val serviceDir: String,
    val jobName: String,
) {
    /** CI job prefix of the legacy behat job, e.g. `test:behat:raketa`. */
    val jobPrefix: String = "test:behat:$jobName"

    /**
     * CI job prefix of the dedicated coverage job produced by the `coverage-test` stage,
     * e.g. `test:behat:coverage:raketa`.
     *
     * Coverage archives (`coverage-reports/coverage.covt.gz`) are published only by
     * these jobs. The plain `test:behat:<component>` jobs keep trace/junit artifacts
     * but have no archive.
     */
    val coverageJobPrefix: String = "test:behat:coverage:$jobName"

    /** True for the legacy `test:behat:<component>` job name (no coverage archive). */
    fun matchesLegacyJobName(name: String): Boolean =
        name.contains(jobPrefix, ignoreCase = true)

    /** True for the dedicated `test:behat:coverage:<component>` job name. */
    fun matchesCoverageJobName(name: String): Boolean =
        name.contains(coverageJobPrefix, ignoreCase = true)
}

object ComponentConfig {

    private val log = CoverageLog.get(ComponentConfig::class.java)

    private val COMPONENTS = listOf(
        ComponentEntry("api/avia", "avia"),
        ComponentEntry("raketa", "raketa"),
        ComponentEntry("api/hotels", "hotels"),
        ComponentEntry("api/transfer", "transfer"),
        ComponentEntry("api/rail", "rail"),
        ComponentEntry("api/profile-sync", "profile-sync"),
        ComponentEntry("api/queue", "queue"),
        ComponentEntry("api/aeroexpress", "aeroexpress"),
        ComponentEntry("api/contract", "contract"),
        ComponentEntry("api/bus", "bus"),
    )

    private val RAKETA = COMPONENTS.first { it.serviceDir == "raketa" }

    /**
     * Определяет компонент для проекта.
     * - Корень репо → raketa
     * - Вложенность в serviceDir → соответствующий компонент
     * - Иначе → null
     */
    fun detectComponent(projectBasePath: String, gitRoot: File): ComponentEntry? {
        if (isAtGitRoot(projectBasePath, gitRoot)) {
            log.info("ComponentConfig: project at git root — using raketa")
            return RAKETA
        }

        val gitRelative = runCatching {
            gitRoot.toPath().relativize(File(projectBasePath).toPath())
                .toString().replace(File.separatorChar, '/')
        }.getOrNull()

        if (gitRelative == null) {
            log.info("ComponentConfig: cannot relativize $projectBasePath to git root $gitRoot")
            return null
        }

        val matched = COMPONENTS.firstOrNull { entry ->
            gitRelative == entry.serviceDir || gitRelative.startsWith("${entry.serviceDir}/")
        }

        if (matched != null) {
            log.info("ComponentConfig: detected component '${matched.serviceDir}' (gitRelative=$gitRelative)")
            return matched
        }

        log.info("ComponentConfig: no component matched for gitRelative=$gitRelative")
        return null
    }

    private fun isAtGitRoot(projectBasePath: String, gitRoot: File): Boolean {
        return runCatching {
            File(projectBasePath).canonicalPath == gitRoot.canonicalPath
        }.getOrDefault(false)
    }

    fun allEntries(): List<ComponentEntry> = COMPONENTS

    /**
     * Given behat job names, returns the subset that should be downloaded for [component].
     *
     * Dedicated `test:behat:coverage:<component>` jobs take precedence; the legacy
     * `test:behat:<component>` jobs are used only when no coverage-prefixed job exists.
     */
    fun selectCoverageJobNames(jobNames: List<String>, component: ComponentEntry): List<String> {
        val behatJobs = jobNames.filter { it.contains("behat", ignoreCase = true) }
        val coverageJobs = behatJobs.filter { component.matchesCoverageJobName(it) }
        if (coverageJobs.isNotEmpty()) return coverageJobs
        return behatJobs.filter { component.matchesLegacyJobName(it) }
    }
}
