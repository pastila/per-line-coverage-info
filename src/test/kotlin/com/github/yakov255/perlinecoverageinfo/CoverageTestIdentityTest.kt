package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTestIdentityTest {
    @Test
    fun `numeric coverage sessions are not scenario names`() {
        for (name in listOf("1", "app/raketa/1", "api/hotels/12", "app\\raketa\\1")) {
            assertTrue(name, CoverageTestIdentity.isAnonymous(name))
        }
    }

    @Test
    fun `feature lines and PHPUnit data set names retain their identities`() {
        for (name in listOf("features/1.feature:3", "Test::testOne", "Test::testOne with data set /1", "", "features/")) {
            assertFalse(name, CoverageTestIdentity.isAnonymous(name))
        }
    }
}
