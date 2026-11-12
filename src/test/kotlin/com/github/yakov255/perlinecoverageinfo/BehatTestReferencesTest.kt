package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.*
import org.junit.Test

class BehatTestReferencesTest {
    private val path = "features/Test.feature"
    private val text = """
        Feature: testing
          Background:
            Given a shared step
          Scenario Outline: template
            Given <value>
            Then a result
            Examples: values
              | value | description |
              | one   | first       |
              | two   | second      |
          Scenario: regular
            Given regular
    """.trimIndent()

    private fun resolve(
        names: List<String>, source: String = text, current: String = text,
        targetPath: String? = path,
    ): BehatTestReferences.Result = BehatTestReferences(
        readSource = { _, _ -> source }, readCurrent = { current }, currentPath = { _, _ -> targetPath },
    ).resolve(names.map { BehatTestReferences.Request("$path:$it", "old") })

    @Test fun `one row remains one row and never expands to the whole outline`() {
        val result = resolve(listOf("9"))
        assertEquals(listOf("$path:9"), result.names)
        assertTrue(result.unresolved.isEmpty())
        assertTrue(result.resolved.single().label.contains("value=one"))
    }

    @Test fun `rows follow added lines and reordered examples by cell values`() {
        val current = "\n\n" + text.replace("| one   | first       |\n      | two   | second      |",
            "| two   | second      |\n      | one   | first       |")
        val result = resolve(listOf("9"), current = current)
        assertEquals(listOf("$path:12"), result.names)
    }

    @Test fun `renaming an examples table preserves a unique data row`() {
        assertEquals(listOf("$path:9"), resolve(listOf("9"), current = text.replace("Examples: values", "Examples: renamed")).names)
    }

    @Test fun `a number that now points to a different valid row is mapped from its original identity`() {
        val current = text.replace("| one   | first       |\n      | two   | second      |",
            "| two   | second      |\n      | one   | first       |")
        assertEquals(listOf("$path:10"), resolve(listOf("9"), current = current).names)
    }

    @Test fun `whole outline expands to concrete rows and overlaps with an explicitly selected row only once`() {
        val result = resolve(listOf("4", "9"))
        assertEquals(listOf("$path:9", "$path:10"), result.names)
        assertTrue(result.resolved.all { it.origin.name.substringAfterLast(':') in setOf("9", "10") })
    }

    @Test fun `an outline selection does not include rows added after coverage was collected`() {
        val current = text.replace("  Scenario: regular", "      | three | new         |\n  Scenario: regular")
        assertEquals(listOf("$path:9", "$path:10"), resolve(listOf("4"), current = current).names)
    }

    @Test fun `table headers steps backgrounds and docstrings are not executable references`() {
        val result = resolve(listOf("1", "2", "3", "5", "7", "8"))
        assertTrue(result.names.isEmpty())
        assertEquals(6, result.unresolved.size)
        assertTrue(result.unresolved.all { it.reason.contains("not a scenario") })
    }

    @Test fun `deleted or edited row is unresolved instead of another row or the whole outline`() {
        val current = text.replace("      | one   | first       |\n", "")
        val removed = resolve(listOf("9"), current = current)
        assertTrue(removed.names.isEmpty())
        assertTrue(removed.unresolved.single().reason.contains("deleted or changed"))
        assertTrue(resolve(listOf("9"), current = text.replace("| one", "| changed")).names.isEmpty())
    }

    @Test fun `identical duplicate rows are allowed only when their source has not changed`() {
        val duplicate = text.replace("| two   | second      |", "| one   | first       |")
        assertEquals(listOf("$path:9"), resolve(listOf("9"), source = duplicate, current = duplicate).names)
        val changed = resolve(listOf("9"), source = duplicate, current = "\n$duplicate")
        assertTrue(changed.names.isEmpty())
        assertTrue(changed.unresolved.single().reason.contains("ambiguous"))
    }

    @Test fun `duplicate scenario names after editing are unresolved`() {
        val current = text + "\n  Scenario: regular\n    Given another\n"
        val result = resolve(listOf("11"), current = current)
        assertTrue(result.names.isEmpty())
        assertTrue(result.unresolved.single().reason.contains("ambiguous"))
    }

    @Test fun `renamed feature keeps the concrete example target`() {
        assertEquals(listOf("renamed.feature:9"), resolve(listOf("9"), targetPath = "renamed.feature").names)
        assertTrue(resolve(listOf("9"), targetPath = null).unresolved.single().reason.contains("deleted"))
    }

    @Test fun `missing historical source cannot use a coincidentally valid current line`() {
        val result = BehatTestReferences({ _, _ -> null }, { text }).resolve(listOf(BehatTestReferences.Request("$path:9", "old")))
        assertTrue(result.names.isEmpty())
        assertTrue(result.unresolved.single().reason.contains("coverage revision"))
    }

    @Test fun `a current request pins its source for revalidation after edits`() {
        val first = BehatTestReferences({ _, _ -> null }, { text }).resolve(listOf(BehatTestReferences.Request("$path:9")))
        val moved = BehatTestReferences({ _, _ -> null }, { "\n\n$text" }).resolve(first.resolved.map { it.origin })
        assertEquals(listOf("$path:11"), moved.names)
    }

    @Test fun `localized examples with escaped pipes and newlines retain their identity`() {
        val old = "# language: ru\nФункционал: тест\n  Шаблон сценария: пример\n    Дано <значение>\n    Примеры:\n      | значение |\n      | a\\|b\\nc |\n"
        val result = resolve(listOf("7"), source = old, current = "\n$old")
        assertEquals(listOf("$path:8"), result.names)
        assertTrue(result.resolved.single().label.contains("a|b\nc"))
    }

    @Test fun `whole outline revalidation preserves a users selected subset of examples`() {
        val all = resolve(listOf("4"))
        val secondOnly = all.resolved.filter { it.target.endsWith(":10") }.map { it.origin }
        val result = BehatTestReferences({ _, _ -> text }, { "\n$text" }).resolve(secondOnly)
        assertEquals(listOf("$path:11"), result.names)
    }

    @Test fun `same raw reference from primary and baseline retains both source identities`() {
        val baseline = text.replace("| one   | first       |\n      | two   | second      |",
            "| two   | second      |\n      | one   | first       |")
        val result = BehatTestReferences({ revision, _ -> if (revision == "primary") text else baseline }, { text })
            .resolve(listOf(BehatTestReferences.Request("$path:9", "primary"), BehatTestReferences.Request("$path:9", "baseline")))
        assertEquals(setOf("$path:9", "$path:10"), result.names.toSet())
    }

    @Test fun `converted scenario is explicitly unresolved instead of running every new example`() {
        val old = "Feature: testing\n  Scenario: template\n    Given fixed\n"
        val result = resolve(listOf("2"), source = old)
        assertTrue(result.names.isEmpty())
        assertEquals("Scenario changed to a Scenario Outline", result.unresolved.single().reason)
    }

    @Test fun `moving a heading after an examples docstring does not create fake executable rows`() {
        val content = "Feature: test\n  Scenario Outline: actual\n    Given text\n      \"\"\"\n      Examples:\n        | x |\n        | a |\n      \"\"\"\n    Examples:\n      | x |\n      | real |\n"
        val result = resolve(listOf("8"), source = content, current = content)
        assertTrue(result.names.isEmpty())
    }
}
