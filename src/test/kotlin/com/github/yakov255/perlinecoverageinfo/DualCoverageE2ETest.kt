package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * End-to-end integration test for the dual-coverage data pipeline.
 *
 * It exercises the parts that can be exercised without an IDE fixture:
 * - Two distinct coverage maps are persisted to two COV4 files in a shared cache directory
 *   (simulating the on-disk layout produced by [CoverageCacheService]).
 * - Two parallel [Cov4Reader]s are opened, mimicking the primary + baseline readers
 *   that [CoverageDataService] holds in dual-coverage mode.
 * - Per-line categorization and filter math run against the data those readers serve.
 *
 * This catches the highest-risk integration concerns added by dual-coverage support:
 *   - Cache-key isolation (two .cov4 files in one directory don't trample each other).
 *   - Two readers can be open simultaneously and serve their own data.
 *   - Differential rendering math (categorization + filter) returns correct results
 *     when fed real reader output rather than hand-rolled maps.
 */
class DualCoverageE2ETest {

    private lateinit var cacheDir: File
    private lateinit var primaryFile: File
    private lateinit var baselineFile: File

    private val sourceFile = "src/Calculator.php"

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("dual-cov-e2e").toFile()
        primaryFile = File(cacheDir, "aaa1111.cov4")
        baselineFile = File(cacheDir, "bbb2222.cov4")
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun `dual coverage round-trip preserves per-line categorization and filter results`() {
        // --- Arrange: master (baseline) covered lines 1, 2 with a single shared test.
        val baselineCoverage: Map<String, Map<Int, List<String>>> = mapOf(
            sourceFile to mapOf(
                1 to listOf("CalculatorTest::testAdd"),
                2 to listOf("CalculatorTest::testAdd"),
                3 to emptyList(),
            ),
        )
        // Feature branch keeps the master test on lines 1-2 and adds a new test on line 2,
        // and adds a brand-new test on line 3 (which was uncovered on master).
        val primaryCoverage: Map<String, Map<Int, List<String>>> = mapOf(
            sourceFile to mapOf(
                1 to listOf("CalculatorTest::testAdd"),
                2 to listOf("CalculatorTest::testAdd", "CalculatorTest::testAddNegative"),
                3 to listOf("CalculatorTest::testEdgeCase"),
            ),
        )

        Cov4Writer.write(primaryCoverage, primaryFile)
        Cov4Writer.write(baselineCoverage, baselineFile)

        // --- Act: open both readers (simulating CoverageDataService primary + baseline).
        Cov4Reader(primaryFile).use { primaryReader ->
            Cov4Reader(baselineFile).use { baselineReader ->
                val primaryLines = primaryReader.getCoverage(sourceFile)
                    ?: error("primary reader returned no coverage for $sourceFile")
                val baselineLines = baselineReader.getCoverage(sourceFile)
                    ?: error("baseline reader returned no coverage for $sourceFile")

                // Sanity: the two readers serve different data.
                assertNotEquals(primaryLines, baselineLines)

                // --- Assert: per-line categorization (the gutter colouring decision).
                fun cat(line: Int) = CoverageHighlighter.categorizeLine(
                    primary = primaryLines[line] ?: emptyList(),
                    baseline = baselineLines[line] ?: emptyList(),
                    hasBaseline = true,
                )
                // Line 1: identical → COVERED (green).
                assertEquals(CoverageCategory.COVERED, cat(1))
                // Line 2: branch added a new test but master also covers → COVERED (green).
                assertEquals(CoverageCategory.COVERED, cat(2))
                // Line 3: master has no coverage, branch covers → FEATURE_ONLY (blue).
                assertEquals(CoverageCategory.FEATURE_ONLY, cat(3))

                // --- Assert: filter math against real reader data (line 2 is the interesting one).
                val line2Primary = primaryLines.getValue(2)
                val line2Baseline = baselineLines.getValue(2)

                assertEquals(
                    listOf("CalculatorTest::testAdd", "CalculatorTest::testAddNegative"),
                    CoveringLinePanel.computeDisplayed(
                        line2Primary, line2Baseline, hasBaseline = true,
                        CoveringLinePanel.TestFilter.BOTH,
                    ),
                )
                assertEquals(
                    listOf("CalculatorTest::testAdd"),
                    CoveringLinePanel.computeDisplayed(
                        line2Primary, line2Baseline, hasBaseline = true,
                        CoveringLinePanel.TestFilter.MASTER_ONLY,
                    ),
                )
                assertEquals(
                    listOf("CalculatorTest::testAddNegative"),
                    CoveringLinePanel.computeDisplayed(
                        line2Primary, line2Baseline, hasBaseline = true,
                        CoveringLinePanel.TestFilter.FEATURE_ONLY,
                    ),
                )
            }
        }
    }

    @Test
    fun `single mode (no baseline) classifies all lines without dual-mode visuals`() {
        val coverage: Map<String, Map<Int, List<String>>> = mapOf(
            sourceFile to mapOf(
                1 to listOf("Test::a"),
                2 to emptyList(),
            ),
        )
        Cov4Writer.write(coverage, primaryFile)

        Cov4Reader(primaryFile).use { reader ->
            val lines = reader.getCoverage(sourceFile)!!

            // Without a baseline FEATURE_ONLY must never appear.
            assertEquals(
                CoverageCategory.COVERED,
                CoverageHighlighter.categorizeLine(lines[1] ?: emptyList(), emptyList(), hasBaseline = false),
            )
            assertEquals(
                CoverageCategory.UNCOVERED,
                CoverageHighlighter.categorizeLine(lines[2] ?: emptyList(), emptyList(), hasBaseline = false),
            )
            // Filter is a no-op without baseline.
            assertEquals(
                lines[1],
                CoveringLinePanel.computeDisplayed(
                    lines[1] ?: emptyList(), emptyList(), hasBaseline = false,
                    CoveringLinePanel.TestFilter.FEATURE_ONLY,
                ),
            )
        }
    }

    @Test
    fun `baseline reader returns null for files only present in primary`() {
        // Realistic scenario: a new file added on the feature branch is absent from master coverage.
        val newFileOnBranch = "src/NewFeature.php"
        Cov4Writer.write(
            mapOf(newFileOnBranch to mapOf(1 to listOf("NewTest::a"))),
            primaryFile,
        )
        Cov4Writer.write(
            mapOf(sourceFile to mapOf(1 to listOf("OldTest::a"))),
            baselineFile,
        )

        Cov4Reader(primaryFile).use { primary ->
            Cov4Reader(baselineFile).use { baseline ->
                assertEquals(listOf("NewTest::a"), primary.getCoverage(newFileOnBranch)?.get(1))
                assertNull(baseline.getCoverage(newFileOnBranch))
                // Highlighter defaults baseline to emptyList() in that case → FEATURE_ONLY.
                assertEquals(
                    CoverageCategory.FEATURE_ONLY,
                    CoverageHighlighter.categorizeLine(
                        primary = primary.getCoverage(newFileOnBranch)?.get(1) ?: emptyList(),
                        baseline = baseline.getCoverage(newFileOnBranch)?.get(1) ?: emptyList(),
                        hasBaseline = true,
                    ),
                )
            }
        }
    }
}
