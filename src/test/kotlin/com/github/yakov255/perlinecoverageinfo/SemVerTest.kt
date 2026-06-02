package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    @Test
    fun `equal versions compare to zero`() {
        assertEquals(0, SemVer.compare("2.2.0", "2.2.0"))
    }

    @Test
    fun `patch increment is greater`() {
        assertTrue(SemVer.compare("2.2.1", "2.2.0") > 0)
    }

    @Test
    fun `minor increment is greater`() {
        assertTrue(SemVer.compare("2.3.0", "2.2.9") > 0)
    }

    @Test
    fun `major increment is greater`() {
        assertTrue(SemVer.compare("3.0.0", "2.99.99") > 0)
    }

    @Test
    fun `pre-release is older than release of same version`() {
        assertTrue(SemVer.compare("2.2.0-alpha.1", "2.2.0") < 0)
        assertTrue(SemVer.compare("2.2.0", "2.2.0-rc.1") > 0)
    }

    @Test
    fun `numeric pre-release identifiers compared numerically`() {
        assertTrue(SemVer.compare("2.2.0-alpha.10", "2.2.0-alpha.2") > 0)
    }

    @Test
    fun `numeric pre-release identifier is less than alphanumeric`() {
        assertTrue(SemVer.compare("2.2.0-1", "2.2.0-alpha") < 0)
    }

    @Test
    fun `build metadata is ignored`() {
        assertEquals(0, SemVer.compare("2.2.0+build.1", "2.2.0+build.99"))
    }

    @Test
    fun `malformed versions fall back to lexicographic compare`() {
        assertTrue(SemVer.compare("garbage", "2.2.0") > 0)
        assertEquals(0, SemVer.compare("2.2", "2.2.0"))
    }

    @Test
    fun `two-segment version treated as patch zero`() {
        assertEquals(0, SemVer.compare("2.2", "2.2.0"))
    }
}
