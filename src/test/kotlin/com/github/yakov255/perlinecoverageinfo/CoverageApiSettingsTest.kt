package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageApiSettingsTest {

    private fun baseUrl(domain: String): String {
        val settings = CoverageApiSettings()
        settings.gitlabDomain = domain
        return settings.gitlabBaseUrl
    }

    @Test
    fun `a bare domain gets https`() {
        assertEquals("https://gitlab.example.com", baseUrl("gitlab.example.com"))
    }

    @Test
    fun `trailing slashes and spaces are trimmed`() {
        assertEquals("https://gitlab.example.com", baseUrl("  gitlab.example.com/ "))
    }

    @Test
    fun `an explicit https scheme is kept`() {
        assertEquals("https://gitlab.example.com", baseUrl("https://gitlab.example.com/"))
    }

    @Test
    fun `an explicit http scheme and port are kept for a plain-HTTP GitLab`() {
        assertEquals("http://127.0.0.1:8080", baseUrl("http://127.0.0.1:8080"))
        assertEquals("HTTP://gitlab.local", baseUrl("HTTP://gitlab.local"))
    }

    @Test
    fun `a domain with a port but no scheme gets https`() {
        assertEquals("https://gitlab.example.com:8443", baseUrl("gitlab.example.com:8443"))
    }
}
