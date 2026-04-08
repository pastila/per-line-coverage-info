package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AffectedTestsModelTest {

    @Test
    fun `displayed tests is union over checked files`() {
        val m = AffectedTestsModel(
            mapOf(
                "A.php" to setOf("t1", "t2"),
                "B.php" to setOf("t2", "t3"),
            )
        )
        assertEquals(setOf("t1", "t2", "t3"), m.displayedTests)
    }

    @Test
    fun `unchecking a file drops only its unique tests`() {
        val m = AffectedTestsModel(
            mapOf(
                "A.php" to setOf("t1", "t2"),
                "B.php" to setOf("t2", "t3"),
            )
        )
        m.setChecked("A.php", false)
        assertEquals(setOf("t2", "t3"), m.displayedTests)
    }

    @Test
    fun `removed tests are subtracted from displayed`() {
        val m = AffectedTestsModel(
            mapOf("A.php" to setOf("t1", "t2"))
        )
        m.removeTest("t1")
        assertEquals(setOf("t2"), m.displayedTests)
    }

    @Test
    fun `removed tests stay removed across uncheck and recheck`() {
        val m = AffectedTestsModel(
            mapOf("A.php" to setOf("t1", "t2"))
        )
        m.removeTest("t1")
        m.setChecked("A.php", false)
        m.setChecked("A.php", true)
        assertEquals(setOf("t2"), m.displayedTests)
    }

    @Test
    fun `delta for a single file equals its unique tests`() {
        val m = AffectedTestsModel(
            mapOf(
                "A.php" to setOf("t1", "t2"),
                "B.php" to setOf("t2", "t3"),
            )
        )
        assertEquals(1, m.deltaForFiles(setOf("A.php")))
        assertEquals(1, m.deltaForFiles(setOf("B.php")))
    }

    @Test
    fun `delta for a subtree counts shared tests once`() {
        val m = AffectedTestsModel(
            mapOf(
                "dir/A.php" to setOf("t1", "t2"),
                "dir/B.php" to setOf("t2", "t3"),
                "other/C.php" to setOf("t4"),
            )
        )
        // dir contains both A and B → unchecking the whole dir would drop t1, t2, t3 = 3
        assertEquals(3, m.deltaForFiles(setOf("dir/A.php", "dir/B.php")))
    }

    @Test
    fun `bootstrap exclusion case`() {
        // bootstrap.php covers 10000 tests, foo covers 100. 80 of foo's tests overlap with bootstrap.
        val bootstrapTests = (1..10000).map { "t$it" }.toSet()
        val fooTests = (1..100).map { "t$it" }.toSet()  // first 80 overlap with bootstrap, last 20 don't... wait all 100 overlap
        // Make foo cover 100 unique-to-foo tests
        val fooUniqueTests = (10001..10100).map { "t$it" }.toSet()
        val m = AffectedTestsModel(
            mapOf(
                "tests/bootstrap.php" to bootstrapTests,
                "src/foo.php" to fooUniqueTests,
            )
        )
        assertEquals(10100, m.displayedTests.size)
        // Excluding bootstrap drops exactly 10000 tests
        assertEquals(10000, m.deltaForFiles(setOf("tests/bootstrap.php")))
        m.setChecked("tests/bootstrap.php", false)
        assertEquals(100, m.displayedTests.size)
    }

    @Test
    fun `delta is zero when subtree contains no checked files`() {
        val m = AffectedTestsModel(
            mapOf("A.php" to setOf("t1"))
        )
        m.setChecked("A.php", false)
        assertEquals(0, m.deltaForFiles(setOf("A.php")))
    }

    @Test
    fun `check all and uncheck all`() {
        val m = AffectedTestsModel(
            mapOf("A.php" to setOf("t1"), "B.php" to setOf("t2"))
        )
        m.uncheckAll()
        assertTrue(m.displayedTests.isEmpty())
        m.checkAll()
        assertEquals(setOf("t1", "t2"), m.displayedTests)
    }

    @Test
    fun `reverse index is built correctly`() {
        val m = AffectedTestsModel(
            mapOf(
                "A.php" to setOf("t1", "t2"),
                "B.php" to setOf("t2"),
            )
        )
        assertEquals(setOf("A.php"), m.reverseIndex["t1"])
        assertEquals(setOf("A.php", "B.php"), m.reverseIndex["t2"])
    }
}
