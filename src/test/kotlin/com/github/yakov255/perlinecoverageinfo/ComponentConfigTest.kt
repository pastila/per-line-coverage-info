package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentConfigTest {

    private val raketa = ComponentEntry("raketa", "raketa")

    @Test
    fun `job prefixes are derived from the component job token`() {
        assertEquals("test:behat:raketa", raketa.jobPrefix)
        assertEquals("test:behat:coverage:raketa", raketa.coverageJobPrefix)
    }

    @Test
    fun `dedicated coverage jobs are preferred over legacy behat jobs`() {
        val names = listOf(
            "test:behat:raketa-8.5: [sprint:1]",
            "test:behat:coverage:raketa-8.5: [sprint:1]",
            "test:behat:raketa-8.5: [sprint:2]",
            "test:behat:coverage:raketa-8.5: [sprint:2]",
            "test:phpunit-8.5",
        )

        val selected = ComponentConfig.selectCoverageJobNames(names, raketa)

        assertEquals(
            listOf(
                "test:behat:coverage:raketa-8.5: [sprint:1]",
                "test:behat:coverage:raketa-8.5: [sprint:2]",
            ),
            selected,
        )
    }

    @Test
    fun `legacy behat jobs are used only when no coverage job exists`() {
        val names = listOf(
            "test:behat:raketa-8.5: [sprint:1]",
            "test:behat:raketa-8.5: [sprint:2]",
            "test:phpunit-8.5",
        )

        val selected = ComponentConfig.selectCoverageJobNames(names, raketa)

        assertEquals(
            listOf(
                "test:behat:raketa-8.5: [sprint:1]",
                "test:behat:raketa-8.5: [sprint:2]",
            ),
            selected,
        )
    }

    @Test
    fun `jobs of other components are not selected`() {
        val names = listOf(
            "test:behat:coverage:avia-8.5: [sprint:1]",
            "test:behat:raketa-8.5: [sprint:1]",
        )

        assertTrue(ComponentConfig.selectCoverageJobNames(names, raketa).isEmpty())
    }

    @Test
    fun `coverage and legacy matchers are mutually exclusive`() {
        val coverageJob = "test:behat:coverage:raketa-8.5: [sprint:1]"
        val legacyJob = "test:behat:raketa-8.5: [sprint:1]"

        assertTrue(raketa.matchesCoverageJobName(coverageJob))
        assertFalse(raketa.matchesLegacyJobName(coverageJob))

        assertTrue(raketa.matchesLegacyJobName(legacyJob))
        assertFalse(raketa.matchesCoverageJobName(legacyJob))
    }
}
