package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoverageUiTestCommandsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `parses the three documented commands`() {
        assertEquals(CoverageUiTestCommands.Command(CoverageUiTestCommands.Kind.RELOAD), CoverageUiTestCommands.parse("reload"))
        assertEquals(
            CoverageUiTestCommands.Command(CoverageUiTestCommands.Kind.SETTINGS_APPLY),
            CoverageUiTestCommands.parse("settings.apply"),
        )
        assertEquals(
            CoverageUiTestCommands.Command(CoverageUiTestCommands.Kind.SETTINGS_TOKEN, "abc"),
            CoverageUiTestCommands.parse("settings.token=abc"),
        )
        assertEquals(
            CoverageUiTestCommands.Command(CoverageUiTestCommands.Kind.SETTINGS_TOKEN, "a=b c"),
            CoverageUiTestCommands.parse("  settings.token=a=b c  "),
        )
    }

    @Test
    fun `ignores blank and unknown lines`() {
        assertNull(CoverageUiTestCommands.parse(""))
        assertNull(CoverageUiTestCommands.parse("   "))
        assertNull(CoverageUiTestCommands.parse("reload now"))
        assertNull(CoverageUiTestCommands.parse("settings.token"))
    }

    @Test
    fun `a token value is always masked in the bridge and the log`() {
        assertEquals("settings.token=***", CoverageUiTestCommands.displayName("settings.token=glpat-secret"))
        assertEquals("reload", CoverageUiTestCommands.displayName("  reload  "))
        assertEquals("unknown", CoverageUiTestCommands.displayName("unknown"))
    }

    @Test
    fun `reads each complete line once and keeps a partial line for the next poll`() {
        val file = File(temp.root, "commands.txt")
        file.writeText("reload\nsettings.token=x\n")

        val (first, offset) = CoverageUiTestCommands.readNew(file, 0)
        assertEquals(listOf("reload", "settings.token=x"), first)
        assertEquals(file.length(), offset)
        assertEquals(emptyList<String>(), CoverageUiTestCommands.readNew(file, offset).first)

        file.appendText("settings.appl")
        assertEquals(emptyList<String>(), CoverageUiTestCommands.readNew(file, offset).first)

        file.appendText("y\n")
        val (second, newOffset) = CoverageUiTestCommands.readNew(file, offset)
        assertEquals(listOf("settings.apply"), second)
        assertEquals(file.length(), newOffset)
        assertEquals(emptyList<String>(), CoverageUiTestCommands.readNew(file, newOffset).first)
    }

    @Test
    fun `a missing file is silent and a truncated file restarts from the beginning`() {
        val missing = File(temp.root, "missing.txt")
        assertEquals(emptyList<String>() to 0L, CoverageUiTestCommands.readNew(missing, 0))

        val file = File(temp.root, "commands.txt")
        file.writeText("reload\n")
        val (_, offset) = CoverageUiTestCommands.readNew(file, 0)
        file.writeText("apply\n")

        val (afterTruncate, newOffset) = CoverageUiTestCommands.readNew(file, offset)
        assertEquals(listOf("apply"), afterTruncate)
        assertEquals(file.length(), newOffset)
    }
}
