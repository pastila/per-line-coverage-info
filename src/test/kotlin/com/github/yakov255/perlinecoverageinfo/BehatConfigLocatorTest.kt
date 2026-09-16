package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BehatConfigLocatorTest {

    private val monorepo = setOf(
        "/repo/core/behat.yml",
        "/repo/api/rail/behat.yml",
        "/repo/api/bus/behat.php",
    )

    private fun locate(featurePath: String, files: Set<String> = monorepo) =
        BehatConfigLocator.locate(featurePath, "/repo") { it in files }

    @Test
    fun `finds the config of the service the feature belongs to`() {
        val project = locate("/repo/core/src/Features/FeatureTest/MobileAPI/Service/AviaLegacy.feature")
        assertEquals("/repo/core/behat.yml", project?.configFile)
        assertEquals("/repo/core", project?.workingDir)
    }

    @Test
    fun `picks the nearest config, not the first one in the repo`() {
        val project = locate("/repo/api/rail/tests/Features/search.feature")
        assertEquals("/repo/api/rail/behat.yml", project?.configFile)
    }

    @Test
    fun `supports behat php configs`() {
        val project = locate("/repo/api/bus/tests/Features/book.feature")
        assertEquals("/repo/api/bus/behat.php", project?.configFile)
        assertEquals("/repo/api/bus", project?.workingDir)
    }

    @Test
    fun `stops at the project root`() {
        assertNull(locate("/repo/app/raketa/Features/a.feature"))
    }

    @Test
    fun `a config in a config subdirectory belongs to the directory above`() {
        val project = locate(
            "/repo/svc/tests/a.feature",
            setOf("/repo/svc/config/behat.yml"),
        )
        assertEquals("/repo/svc/config/behat.yml", project?.configFile)
        assertEquals("/repo/svc", project?.workingDir)
    }
}
