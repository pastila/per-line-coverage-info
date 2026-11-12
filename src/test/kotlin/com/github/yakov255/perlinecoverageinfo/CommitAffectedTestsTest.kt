package com.github.yakov255.perlinecoverageinfo

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CommitAffectedTestsTest {
    private lateinit var repo: File
    @Before fun setup() {
        repo = Files.createTempDirectory("commit-affected-").toFile()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.invalid")
        git("config", "commit.gpgsign", "false")
        git("config", "core.autocrlf", "false")
    }
    @After fun cleanup() { repo.deleteRecursively() }

    private val feature = "Feature: test\n  Scenario: first\n    Given original\n  Scenario: second\n    Given second\n"
    private fun write(path: String, text: String) { File(repo, path).apply { parentFile.mkdirs(); writeText(text) } }
    private fun git(vararg args: String): String = GitCommand.runRaw(repo, *args) ?: error("git failed: ${args.toList()}")
    private fun commit(): String {
        git("add", "-A")
        git("commit", "-qm", "test")
        return git("rev-parse", "HEAD").trim()
    }

    @Test fun `noncontiguous selection includes independent edits but excludes unselected commits`() {
        write("features/Test.feature", feature)
        commit()
        write("features/Test.feature", feature.replace("original", "modified"))
        val first = commit()
        write("features/Test.feature", feature.replace("original", "modified").replace("Given second", "Given unselected"))
        commit()
        write("features/New.feature", "Feature: new\n  Scenario: added\n    Given new\n")
        val last = commit()
        val result = CommitAffectedTests(repo).compute(listOf(first, last, first))
        assertEquals(setOf("features/Test.feature:2", "features/New.feature:2"), result.evidence.keys)
        assertEquals(listOf(first, last), result.commits)
        assertEquals(setOf(CommitAffectedTests.Reason.MODIFIED), result.evidence.getValue("features/Test.feature:2").map { it.reason }.toSet())
        assertFalse(result.evidence.containsKey("features/Test.feature:4"))
    }

    @Test fun `reverted changes still contribute and tests are deduplicated with both commits`() {
        write("features/Test.feature", feature)
        commit()
        write("features/Test.feature", feature.replace("original", "modified"))
        val first = commit()
        write("features/Test.feature", feature)
        val second = commit()
        val result = CommitAffectedTests(repo).compute(listOf(first, second))
        assertEquals(setOf("features/Test.feature:2"), result.evidence.keys)
        assertEquals(setOf(first, second), result.evidence.values.single().map { it.commit }.toSet())
    }

    @Test fun `root commit includes newly added tests without coverage`() {
        write("features/Test.feature", feature)
        val root = commit()
        val result = CommitAffectedTests(repo).compute(listOf(root))
        assertEquals(setOf("features/Test.feature:2", "features/Test.feature:4"), result.evidence.keys)
        assertTrue(result.evidence.values.flatten().all { it.reason == CommitAffectedTests.Reason.ADDED })
        assertTrue(result.warnings.any { it.contains("No coverage") })
    }

    @Test fun `removed tests are separate and never runnable even if modified by an earlier selected commit`() {
        write("features/Test.feature", feature)
        commit()
        write("features/Test.feature", feature.replace("original", "modified"))
        val changed = commit()
        write("features/Test.feature", "Feature: test\n  Scenario: second\n    Given second\n")
        val deleted = commit()
        val result = CommitAffectedTests(repo).compute(listOf(changed, deleted))
        assertTrue(result.evidence.isEmpty())
        assertEquals(setOf("features/Test.feature:2"), result.removedTests)
    }

    @Test fun `coverage of changed code is combined with a directly changed scenario`() {
        write("src/Foo.php", "<?php\nreturn 1;\n")
        write("features/Test.feature", feature)
        val base = commit()
        write("src/Foo.php", "<?php\nreturn 2;\n")
        write("features/Test.feature", feature.replace("Given second", "Given modified"))
        val change = commit()
        val coverage = CommitAffectedTests.Coverage(base) { path ->
            if (path == "src/Foo.php") mapOf(2 to listOf("features/Test.feature:2", "app/raketa/1")) else null
        }
        val result = CommitAffectedTests(repo, listOf(coverage)).compute(listOf(change))
        assertEquals(setOf("features/Test.feature:2", "features/Test.feature:4"), result.evidence.keys)
        assertEquals(setOf("features/Test.feature:2"), result.perFile.getValue("src/Foo.php"))
        assertEquals(setOf(CommitAffectedTests.Reason.COVERAGE), result.evidence.getValue("features/Test.feature:2").map { it.reason }.toSet())
    }

    @Test fun `historical coverage follows line shifts and scenario moves to current HEAD`() {
        write("src/Foo.php", "<?php\nreturn 1;\n")
        write("features/Test.feature", feature)
        val base = commit()
        write("src/Foo.php", "<?php\nreturn 2;\n")
        val selected = commit()
        write("src/Foo.php", "<?php\n// inserted later\nreturn 1;\n")
        write("features/Test.feature", "\n\n$feature")
        val covered = commit()
        val coverage = CommitAffectedTests.Coverage(covered) { path ->
            if (path == "src/Foo.php") mapOf(3 to listOf("features/Test.feature:4")) else null
        }
        val result = CommitAffectedTests(repo, listOf(coverage)).compute(listOf(selected))
        assertEquals(setOf("features/Test.feature:4"), result.evidence.keys)
    }

    @Test fun `deleted source files still contribute covering tests`() {
        write("src/Deleted.php", "<?php\nreturn 1;\n")
        write("features/Test.feature", feature)
        val base = commit()
        File(repo, "src/Deleted.php").delete()
        val change = commit()
        val coverage = CommitAffectedTests.Coverage(base) { path ->
            if (path == "src/Deleted.php") mapOf(2 to listOf("features/Test.feature:2")) else null
        }
        val result = CommitAffectedTests(repo, listOf(coverage)).compute(listOf(change))
        assertEquals(setOf("features/Test.feature:2"), result.evidence.keys)
    }

    @Test fun `feature renames and paths with spaces preserve current navigation targets`() {
        val path = "features/With space.feature"
        write(path, feature)
        commit()
        write(path, feature.replace("original", "modified"))
        val selected = commit()
        git("mv", path, "features/Renamed.feature")
        commit()
        val result = CommitAffectedTests(repo).compute(listOf(selected))
        assertEquals(setOf("features/Renamed.feature:2"), result.evidence.keys)
    }

    @Test fun `merge commit is compared only with its first parent`() {
        write("features/Test.feature", feature)
        commit()
        git("checkout", "-qb", "side")
        write("features/New.feature", "Feature: new\n  Scenario: merged\n    Given new\n")
        commit()
        git("checkout", "-q", "main")
        write("other.txt", "main")
        commit()
        git("merge", "--no-ff", "-qm", "merge", "side")
        val merge = git("rev-parse", "HEAD").trim()
        val result = CommitAffectedTests(repo).compute(listOf(merge))
        assertEquals(setOf("features/New.feature:2"), result.evidence.keys)
    }

    @Test fun `line mapping excludes replaced lines and accounts for insertions and deletions`() {
        val diff = "@@ -2,0 +3,2 @@\n@@ -7,2 +9 @@\n@@ -12 +13,0 @@\n"
        assertEquals(mapOf(1 to 1, 2 to 2, 3 to 5, 6 to 8, 9 to 10, 13 to 13),
            CommitAffectedTests.mapUnchangedLines(setOf(1, 2, 3, 6, 7, 8, 9, 12, 13), diff))
    }

    @Test fun `missing git revision reports an error rather than an empty successful list`() {
        write("initial", "test")
        commit()
        assertThrows(java.io.IOException::class.java) { CommitAffectedTests(repo).compute(listOf("f".repeat(40))) }
    }

    @Test fun `exact cached coverage of new lines works without loaded coverage`() {
        write("features/Test.feature", feature)
        commit()
        write("src/New.php", "<?php\nreturn 1;\n")
        val selected = commit()
        val analyzer = CommitAffectedTests(repo, cachedCoverage = { revision ->
            if (revision == selected) CommitAffectedTests.Coverage(selected) { path ->
                if (path == "src/New.php") mapOf(2 to listOf("features/Test.feature:2")) else null
            } else null
        })
        assertEquals(setOf("features/Test.feature:2"), analyzer.compute(listOf(selected)).evidence.keys)
    }

    @Test fun `unrelated replacement in the coverage revision cannot select tests by the same line number`() {
        write("features/Test.feature", feature)
        write("src/Foo.php", "<?php\nreturn 1;\n")
        commit()
        write("src/Foo.php", "<?php\nreturn 2;\n")
        val selected = commit()
        write("src/Foo.php", "<?php\nreturn 3;\n")
        val covered = commit()
        val coverage = CommitAffectedTests.Coverage(covered) { path ->
            if (path == "src/Foo.php") mapOf(2 to listOf("features/Test.feature:2")) else null
        }
        val result = CommitAffectedTests(repo, listOf(coverage)).compute(listOf(selected))
        assertTrue(result.evidence.isEmpty())
        assertTrue(result.warnings.any { it.contains("incomplete") })
    }

    @Test fun `direct and covering reasons are retained for the same test`() {
        write("features/Test.feature", feature)
        write("src/Foo.php", "<?php\nreturn 1;\n")
        val base = commit()
        write("features/Test.feature", feature.replace("original", "changed"))
        write("src/Foo.php", "<?php\nreturn 2;\n")
        val selected = commit()
        val coverage = CommitAffectedTests.Coverage(base) { path ->
            if (path == "src/Foo.php") mapOf(2 to listOf("features/Test.feature:2")) else null
        }
        val result = CommitAffectedTests(repo, listOf(coverage)).compute(listOf(selected))
        assertEquals(setOf("features/Test.feature:2"), result.evidence.keys)
        assertEquals(setOf(CommitAffectedTests.Reason.MODIFIED, CommitAffectedTests.Reason.COVERAGE),
            result.evidence.values.single().map { it.reason }.toSet())
    }

    @Test fun `PHPUnit setup includes all current methods and removed methods stay out of covering tests`() {
        val original = "<?php\nnamespace Tests;\nclass FooTest extends TestCase {\n" +
            "protected function setUp() { prepare(); }\npublic function testFirst() {}\npublic function testSecond() {}\n}\n"
        write("tests/FooTest.php", original)
        write("src/Foo.php", "<?php\nreturn 1;\n")
        val base = commit()
        write("tests/FooTest.php", original.replace("prepare()", "prepareMore()"))
        val setup = commit()
        val setupResult = CommitAffectedTests(repo).compute(listOf(setup))
        assertEquals(setOf("Tests\\FooTest::testFirst", "Tests\\FooTest::testSecond"), setupResult.evidence.keys)
        write("tests/FooTest.php", original.replace("public function testFirst() {}\n", ""))
        write("src/Foo.php", "<?php\nreturn 2;\n")
        val removed = commit()
        val coverage = CommitAffectedTests.Coverage(base) { path ->
            if (path == "src/Foo.php") mapOf(2 to listOf("Tests\\FooTest::testFirst", "Tests\\FooTest::testSecond")) else null
        }
        val result = CommitAffectedTests(repo, listOf(coverage)).compute(listOf(removed))
        assertEquals(setOf("Tests\\FooTest::testSecond"), result.evidence.keys)
        assertTrue(result.removedTests.contains("Tests\\FooTest::testFirst"))
    }

    @Test fun `imported coverage without a git revision does not prevent direct test selection`() {
        write("features/Test.feature", feature)
        commit()
        write("features/Test.feature", feature.replace("original", "changed"))
        val selected = commit()
        val result = CommitAffectedTests(repo, listOf(CommitAffectedTests.Coverage("f".repeat(64)) { emptyMap() }))
            .compute(listOf(selected))
        assertEquals(setOf("features/Test.feature:2"), result.evidence.keys)
        assertTrue(result.warnings.any { it.contains("no git revision") })
    }

    @Test fun `inherited PHPUnit identities are preserved instead of falsely reported as removed`() {
        write("tests/ChildTest.php", "<?php\nnamespace Tests;\nclass ChildTest extends ParentTest {}\n")
        write("src/Foo.php", "<?php\nreturn 1;\n")
        val base = commit()
        write("src/Foo.php", "<?php\nreturn 2;\n")
        val selected = commit()
        val coverage = CommitAffectedTests.Coverage(base) { path ->
            if (path == "src/Foo.php") mapOf(2 to listOf("Tests\\ChildTest::testInherited")) else null
        }
        val result = CommitAffectedTests(repo, listOf(coverage)).compute(listOf(selected))
        assertEquals(setOf("Tests\\ChildTest::testInherited"), result.evidence.keys)
        assertTrue(result.removedTests.isEmpty())
    }
}
