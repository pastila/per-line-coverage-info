package com.github.yakov255.perlinecoverageinfo

import com.github.yakov255.perlinecoverageinfo.CoveringLinePanel.TestFilter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Black-box test of the filter logic in [CoveringLinePanel.computeDisplayed].
 * Validates that the Both / Master only / New on this branch radio buttons map
 * a fixed primary+baseline pair to the expected displayed test list.
 */
class CoveringLinePanelFilterTest {

    private val primary = listOf("t1", "t2", "t3")
    private val baseline = listOf("t1", "t4")

    @Test
    fun `BOTH returns the union, primary first`() {
        assertEquals(
            listOf("t1", "t2", "t3", "t4"),
            CoveringLinePanel.computeDisplayed(primary, baseline, hasBaseline = true, TestFilter.BOTH),
        )
    }

    @Test
    fun `MASTER_ONLY returns tests still on master (intersection)`() {
        assertEquals(
            listOf("t1"),
            CoveringLinePanel.computeDisplayed(primary, baseline, hasBaseline = true, TestFilter.MASTER_ONLY),
        )
    }

    @Test
    fun `FEATURE_ONLY returns tests added on this branch`() {
        assertEquals(
            listOf("t2", "t3"),
            CoveringLinePanel.computeDisplayed(primary, baseline, hasBaseline = true, TestFilter.FEATURE_ONLY),
        )
    }

    @Test
    fun `without baseline filter is ignored and primary is returned`() {
        for (filter in TestFilter.values()) {
            assertEquals(
                "filter=$filter",
                primary,
                CoveringLinePanel.computeDisplayed(primary, baseline, hasBaseline = false, filter),
            )
        }
    }
}
