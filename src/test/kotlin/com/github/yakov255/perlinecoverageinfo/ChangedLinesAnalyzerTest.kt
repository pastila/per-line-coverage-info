package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangedLinesAnalyzerTest {

    @Test
    fun `pure deletion produces affected old lines`() {
        val diff = """
            diff --git a/src/Foo.php b/src/Foo.php
            --- a/src/Foo.php
            +++ b/src/Foo.php
            @@ -10,3 +9,0 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        assertEquals(1, changes.size)
        val c = changes[0]
        assertEquals("src/Foo.php", c.oldPath)
        assertEquals("src/Foo.php", c.newPath)
        assertEquals(setOf(10, 11, 12), c.affectedOldLines)
        assertFalse(c.isNew)
        assertFalse(c.isDeleted)
        assertFalse(c.isRenamed)
    }

    @Test
    fun `pure addition adds straddling old lines`() {
        val diff = """
            diff --git a/src/Foo.php b/src/Foo.php
            --- a/src/Foo.php
            +++ b/src/Foo.php
            @@ -42,0 +43,5 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        assertEquals(setOf(42, 43), changes[0].affectedOldLines)
    }

    @Test
    fun `modify in place uses old line range`() {
        val diff = """
            diff --git a/src/Foo.php b/src/Foo.php
            --- a/src/Foo.php
            +++ b/src/Foo.php
            @@ -5,2 +5,3 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        assertEquals(setOf(5, 6), changes[0].affectedOldLines)
    }

    @Test
    fun `default oldCount is 1 when omitted`() {
        val diff = """
            diff --git a/src/Foo.php b/src/Foo.php
            --- a/src/Foo.php
            +++ b/src/Foo.php
            @@ -7 +7 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        assertEquals(setOf(7), changes[0].affectedOldLines)
    }

    @Test
    fun `multiple hunks accumulate`() {
        val diff = """
            diff --git a/src/Foo.php b/src/Foo.php
            --- a/src/Foo.php
            +++ b/src/Foo.php
            @@ -3,1 +3,1 @@
            @@ -20,2 +20,2 @@
            @@ -50,0 +51,1 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        assertEquals(setOf(3, 20, 21, 50, 51), changes[0].affectedOldLines)
    }

    @Test
    fun `new file has no old lines and null oldPath`() {
        val diff = """
            diff --git a/src/New.php b/src/New.php
            new file mode 100644
            --- /dev/null
            +++ b/src/New.php
            @@ -0,0 +1,10 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        assertEquals(1, changes.size)
        val c = changes[0]
        assertTrue(c.isNew)
        assertNull(c.oldPath)
        assertEquals("src/New.php", c.newPath)
        assertTrue(c.affectedOldLines.isEmpty())
    }

    @Test
    fun `deleted file has null newPath`() {
        val diff = """
            diff --git a/src/Old.php b/src/Old.php
            deleted file mode 100644
            --- a/src/Old.php
            +++ /dev/null
            @@ -1,5 +0,0 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        val c = changes[0]
        assertTrue(c.isDeleted)
        assertNull(c.newPath)
        assertEquals("src/Old.php", c.oldPath)
        assertEquals(setOf(1, 2, 3, 4, 5), c.affectedOldLines)
    }

    @Test
    fun `renamed file uses old path for coverage lookup`() {
        val diff = """
            diff --git a/src/Old.php b/src/New.php
            similarity index 90%
            rename from src/Old.php
            rename to src/New.php
            --- a/src/Old.php
            +++ b/src/New.php
            @@ -10,1 +10,1 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        val c = changes[0]
        assertTrue(c.isRenamed)
        assertEquals("src/Old.php", c.oldPath)
        assertEquals("src/New.php", c.newPath)
        assertEquals(setOf(10), c.affectedOldLines)
    }

    @Test
    fun `multiple files in one diff`() {
        val diff = """
            diff --git a/src/A.php b/src/A.php
            --- a/src/A.php
            +++ b/src/A.php
            @@ -1,1 +1,1 @@
            diff --git a/src/B.php b/src/B.php
            --- a/src/B.php
            +++ b/src/B.php
            @@ -10,2 +10,2 @@
        """.trimIndent()

        val changes = ChangedLinesAnalyzer.parseUnifiedDiff(diff)
        assertEquals(2, changes.size)
        assertEquals("src/A.php", changes[0].newPath)
        assertEquals(setOf(1), changes[0].affectedOldLines)
        assertEquals("src/B.php", changes[1].newPath)
        assertEquals(setOf(10, 11), changes[1].affectedOldLines)
    }

    @Test
    fun `empty diff returns empty list`() {
        assertTrue(ChangedLinesAnalyzer.parseUnifiedDiff("").isEmpty())
    }
}
