package com.github.yakov255.perlinecoverageinfo.realproject.report

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecretScannerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `empty and missing directories have no findings`() {
        val dir = temporary.newFolder("empty")
        assertTrue(SecretScanner(dir, listOf("dummy")).scan().isEmpty())
        assertTrue(SecretScanner(File(dir, "missing"), listOf("dummy")).scan().isEmpty())
    }

    @Test
    fun `words without values are not findings`() {
        val dir = temporary.newFolder("words")
        File(dir, "readme.md").writeText(
            """
            The token is obtained from the settings page.
            Send the request with an Authorization header.
            Bearer tokens are accepted.
            glpat tokens start with a prefix.
            authorization
            token=dummy
            """.trimIndent() + "\n",
        )

        assertTrue(SecretScanner(dir).scan().isEmpty())
    }

    @Test
    fun `finds each credential kind with file and line but no value`() {
        val dir = temporary.newFolder("leaks")
        File(dir, "nested").mkdirs()
        File(dir, "a.log").writeText(
            "line one\n" +
                "Authorization: Bearer abcdefghijk\n" +
                "\"PRIVATE-TOKEN\": \"privateValue99\"\n" +
                "left glpat-ABCDEFGHIJKLMNOP right\n",
        )
        File(dir, "nested/b.json").writeText("{\"authorization\":\"tokenValue123\"}\n")

        val findings = SecretScanner(dir, secrets = listOf("secretValue77")).scan()

        assertEquals(5, findings.size)
        val byType = findings.groupBy { it.type }
        assertTrue(byType.containsKey(SecretScanner.TYPE_AUTHORIZATION))
        assertTrue(byType.containsKey(SecretScanner.TYPE_BEARER))
        assertTrue(byType.containsKey(SecretScanner.TYPE_PRIVATE_TOKEN))
        assertTrue(byType.containsKey(SecretScanner.TYPE_GITLAB_TOKEN))
        val bearer = findings.first { it.type == SecretScanner.TYPE_BEARER }
        assertEquals("a.log", bearer.file)
        assertEquals(2, bearer.line)
        assertTrue(findings.any { it.type == SecretScanner.TYPE_AUTHORIZATION && it.file == "nested/b.json" })

        val text = findings.toString()
        for (value in listOf("abcdefghijk", "privateValue99", "ABCDEFGHIJKLMNOP", "tokenValue123", "secretValue77")) {
            assertFalse("finding exposed $value", text.contains(value))
        }
    }

    @Test
    fun `configured secret values are found and short values are ignored`() {
        val dir = temporary.newFolder("configured")
        File(dir, "run.json").writeText("{\"token\":\"SuperSecret42\",\"x\":\"ab\"}\n")

        val findings = SecretScanner(dir, secrets = listOf("SuperSecret42", "ab", "")).scan()

        assertEquals(1, findings.size)
        assertEquals(SecretScanner.TYPE_SECRET_VALUE, findings.first().type)
        assertEquals(1, findings.first().line)
        assertFalse(findings.toString().contains("SuperSecret42"))
    }

    @Test
    fun `binary files and excluded outputs are skipped`() {
        val dir = temporary.newFolder("binary")
        File(dir, "cache.cov4").writeBytes(byteArrayOf(0, 1, 2) + "glpat-ABCDEFGHIJKLMNOP".toByteArray())
        File(dir, "report.md").writeText("glpat-ABCDEFGHIJKLMNOP\n")
        File(dir, "keep.log").writeText("glpat-ABCDEFGHIJKLMNOP\n")

        val findings = SecretScanner(dir, exclude = setOf("report.md")).scan()

        assertEquals(1, findings.size)
        assertEquals("keep.log", findings.first().file)
    }

    @Test
    fun `single file directory is scanned relative to itself`() {
        val file = temporary.newFile("one.log")
        file.writeText("Bearer abcdefghijk\n")

        val findings = SecretScanner(file).scan()

        assertEquals(1, findings.size)
        assertEquals("one.log", findings.first().file)
    }
}
