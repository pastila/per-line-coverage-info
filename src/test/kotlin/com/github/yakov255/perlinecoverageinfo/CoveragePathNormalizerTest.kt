package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class CoveragePathNormalizerTest {

    private val suites = setOf("E2E", "FeatureTest", "Stubs", "Support")

    @Test
    fun `strips the new container prefix from file paths`() {
        assertEquals(
            "app/raketa/api/agency/src/AgencyApiAppFactory.php",
            CoveragePathNormalizer.normalizeRaketaFilePath(
                "app/raketa/web/app/raketa/api/agency/src/AgencyApiAppFactory.php",
            ),
        )
        assertEquals(
            "cli/scripts/clients/Foo.php",
            CoveragePathNormalizer.normalizeRaketaFilePath("app/raketa/web/cli/scripts/clients/Foo.php"),
        )
    }

    @Test
    fun `strips the legacy container prefix from file paths`() {
        assertEquals(
            "api/agency/src/Foo.php",
            CoveragePathNormalizer.normalizeRaketaFilePath("core/web/api/agency/src/Foo.php"),
        )
    }

    @Test
    fun `leaves already normalized file paths unchanged`() {
        val path = "app/raketa/raketa/Layer/UseCase/Foo.php"
        assertEquals(path, CoveragePathNormalizer.normalizeRaketaFilePath(path))
    }

    @Test
    fun `inserts features segment for behat suites`() {
        assertEquals(
            "app/raketa/features/E2E/AgencyAPI/Orders/feature.feature:99",
            CoveragePathNormalizer.normalizeRaketaBehatName(
                "app/raketa/E2E/AgencyAPI/Orders/feature.feature:99",
                suites,
            ),
        )
        assertEquals(
            "app/raketa/features/FeatureTest/Client/feature.feature:12",
            CoveragePathNormalizer.normalizeRaketaBehatName(
                "app/raketa/FeatureTest/Client/feature.feature:12",
                suites,
            ),
        )
    }

    @Test
    fun `strips container prefix for behat outside the features base`() {
        assertEquals(
            "app/raketa/raketa/BoundedContext/Client/Features/x.feature:5",
            CoveragePathNormalizer.normalizeRaketaBehatName(
                "app/raketa/web/app/raketa/raketa/BoundedContext/Client/Features/x.feature:5",
                suites,
            ),
        )
    }

    @Test
    fun `does not insert features twice`() {
        val name = "app/raketa/features/E2E/AgencyAPI/feature.feature:7"
        assertEquals(name, CoveragePathNormalizer.normalizeRaketaBehatName(name, suites))
    }

    @Test
    fun `leaves non-behat tests unchanged`() {
        val name = "App\\Tests\\FooTest::testBar"
        assertEquals(name, CoveragePathNormalizer.normalizeRaketaBehatName(name, suites))
    }

    @Test
    fun `leaves behat names without line suffix unchanged`() {
        val name = "app/raketa/E2E/AgencyAPI/feature.feature"
        assertEquals(name, CoveragePathNormalizer.normalizeRaketaBehatName(name, suites))
    }

    @Test
    fun `default feature suites are used without a git root`() {
        assertEquals(
            setOf("E2E", "FeatureTest", "Stubs", "Support"),
            CoveragePathNormalizer.raketaFeatureSuiteEntries(null),
        )
    }

    @Test
    fun `normalizes a whole coverage map`() {
        val input = mapOf(
            "app/raketa/web/app/raketa/api/agency/src/Foo.php" to mapOf(
                1 to listOf("app/raketa/E2E/Agency/feature.feature:10"),
            ),
            "app/raketa/web/cli/scripts/Bar.php" to mapOf(
                2 to listOf("app/raketa/web/app/raketa/raketa/BoundedContext/Client/Features/x.feature:5"),
            ),
        )

        val normalized = CoveragePathNormalizer.normalizeRaketaCoverage(input, null)

        assertEquals(
            setOf("app/raketa/api/agency/src/Foo.php", "cli/scripts/Bar.php"),
            normalized.keys,
        )
        assertEquals(
            listOf("app/raketa/features/E2E/Agency/feature.feature:10"),
            normalized["app/raketa/api/agency/src/Foo.php"]!![1],
        )
        assertEquals(
            listOf("app/raketa/raketa/BoundedContext/Client/Features/x.feature:5"),
            normalized["cli/scripts/Bar.php"]!![2],
        )
    }
}
