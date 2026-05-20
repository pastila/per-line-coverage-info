package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageWarningTest {

    @Test
    fun `CoverageWarnings hasAny false when both null`() {
        val w = CoverageWarnings(null, null)
        assertFalse(w.hasAny)
    }

    @Test
    fun `CoverageWarnings hasAny true when primaryMismatch present`() {
        val w = CoverageWarnings(WarningDetail("aaa", "bbb", 3), null)
        assertTrue(w.hasAny)
    }

    @Test
    fun `CoverageWarnings hasAny true when baselineMismatch present`() {
        val w = CoverageWarnings(null, WarningDetail("ccc", "ddd", 1))
        assertTrue(w.hasAny)
    }

    @Test
    fun `CoverageWarnings hasAny true when both present`() {
        val w = CoverageWarnings(
            WarningDetail("aaa", "bbb", 3),
            WarningDetail("ccc", "ddd", 5),
        )
        assertTrue(w.hasAny)
    }

    @Test
    fun `WarningDetail fields are preserved`() {
        val d = WarningDetail(expected = "abcdef01", actual = "1234567f", behindBy = 7)
        assertEquals("abcdef01", d.expected)
        assertEquals("1234567f", d.actual)
        assertEquals(7, d.behindBy)
    }

    @Test
    fun `WarningDetail behindBy can be null`() {
        val d = WarningDetail(expected = "abcdef01", actual = "1234567f", behindBy = null)
        assertNull(d.behindBy)
    }

    @Test
    fun `WarningDetail formatPrimaryText with behindBy`() {
        val d = WarningDetail("abc12345", "def67890", 3)
        assertEquals("Feature coverage is from def67890, not HEAD (abc12345) (3 commit(s) behind)", d.formatPrimaryText())
    }

    @Test
    fun `WarningDetail formatPrimaryText without behindBy`() {
        val d = WarningDetail("abc12345", "def67890", null)
        assertEquals("Feature coverage is from def67890, not HEAD (abc12345)", d.formatPrimaryText())
    }

    @Test
    fun `WarningDetail formatPrimaryText with behindBy 0 omits suffix`() {
        val d = WarningDetail("abc12345", "def67890", 0)
        assertEquals("Feature coverage is from def67890, not HEAD (abc12345)", d.formatPrimaryText())
    }

    @Test
    fun `WarningDetail formatBaselineText with behindBy`() {
        val d = WarningDetail("abc12345", "def67890", 5)
        assertEquals("Baseline coverage is from def67890, not merge-base (abc12345) (5 commit(s) behind)", d.formatBaselineText())
    }

    @Test
    fun `WarningDetail formatBaselineText with behindBy 0 omits suffix`() {
        val d = WarningDetail("abc12345", "def67890", 0)
        assertEquals("Baseline coverage is from def67890, not merge-base (abc12345)", d.formatBaselineText())
    }

    @Test
    fun `WarningDetail formatPrimaryHtml`() {
        val d = WarningDetail("abc12345", "def67890", 2)
        assertEquals("Feature coverage is from <b>def67890</b>, not HEAD (<b>abc12345</b>) (2 commit(s) behind)", d.formatPrimaryHtml())
    }

    @Test
    fun `WarningDetail formatPrimaryHtml with behindBy 0 omits suffix`() {
        val d = WarningDetail("abc12345", "def67890", 0)
        assertEquals("Feature coverage is from <b>def67890</b>, not HEAD (<b>abc12345</b>)", d.formatPrimaryHtml())
    }

    @Test
    fun `WarningDetail formatBaselineHtml without behindBy`() {
        val d = WarningDetail("abc12345", "def67890", null)
        assertEquals("Baseline coverage is from <b>def67890</b>, not merge-base (<b>abc12345</b>)", d.formatBaselineHtml())
    }

    @Test
    fun `WarningDetail formatBaselineHtml with behindBy 0 omits suffix`() {
        val d = WarningDetail("abc12345", "def67890", 0)
        assertEquals("Baseline coverage is from <b>def67890</b>, not merge-base (<b>abc12345</b>)", d.formatBaselineHtml())
    }

    @Test
    fun `CoverageWarnings formatPlain combines both`() {
        val w = CoverageWarnings(
            WarningDetail("aaa", "bbb", 1),
            WarningDetail("ccc", "ddd", null),
        )
        val plain = w.formatPlain()
        assertTrue(plain.contains("Feature coverage is from bbb, not HEAD (aaa) (1 commit(s) behind)"))
        assertTrue(plain.contains("Baseline coverage is from ddd, not merge-base (ccc)"))
    }

    @Test
    fun `CoverageWarnings formatPlain with no warnings is empty`() {
        val w = CoverageWarnings(null, null)
        assertEquals("", w.formatPlain())
    }

    @Test
    fun `CoverageWarnings formatHtml combines both`() {
        val w = CoverageWarnings(
            WarningDetail("aaa", "bbb", 1),
            WarningDetail("ccc", "ddd", null),
        )
        val html = w.formatHtml()
        assertTrue(html.contains("<br>"))
        assertTrue(html.contains("<b>bbb</b>"))
        assertTrue(html.contains("<b>ddd</b>"))
    }
}