package com.github.yakov255.perlinecoverageinfo

import java.io.File

data class ComponentEntry(
    val serviceDir: String,
    val jobPrefix: String,
)

object ComponentConfig {

    private val log = CoverageLog.get(ComponentConfig::class.java)

    private val COMPONENTS = listOf(
        ComponentEntry("api/avia", "test:behat:avia"),
        ComponentEntry("raketa", "test:behat:raketa"),
        ComponentEntry("api/hotels", "test:behat:hotels"),
        ComponentEntry("api/transfer", "test:behat:transfer"),
        ComponentEntry("api/rail", "test:behat:rail"),
        ComponentEntry("api/profile-sync", "test:behat:profile-sync"),
        ComponentEntry("api/queue", "test:behat:queue"),
        ComponentEntry("api/aeroexpress", "test:behat:aeroexpress"),
        ComponentEntry("api/contract", "test:behat:contract"),
        ComponentEntry("api/bus", "test:behat:bus"),
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
}
