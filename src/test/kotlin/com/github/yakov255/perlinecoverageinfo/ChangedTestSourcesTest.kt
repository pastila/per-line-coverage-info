package com.github.yakov255.perlinecoverageinfo

import org.junit.Assert.*
import org.junit.Test

class ChangedTestSourcesTest {
    private fun parse(text: String) = ChangedTestSources.parse("features/Test.feature", text.trimIndent())

    @Test fun `scenario examples and tags belong to their own test`() {
        val source = """
            Feature: numbers
              @first
              Scenario Outline: adding
                Given <number>
                Examples:
                  | number |
                  | 1      |

              @second
              Scenario: another
                Given 2
        """.trimIndent()
        val before = parse(source)
        val after = parse(source.replace("| 1      |", "| 3      |"))
        assertEquals(listOf("features/Test.feature:3", "features/Test.feature:10"), before.map { it.name })
        assertNotEquals(before[0].body, after[0].body)
        assertEquals(before[1], after[1])
        val tagged = parse(source.replace("@second", "@changed"))
        assertEquals(before[0], tagged[0])
        assertNotEquals(before[1].body, tagged[1].body)
    }

    @Test fun `background changes affect every scenario but scenario changes do not`() {
        val source = """
            Feature: accounts
              Background:
                Given login
              Scenario: first
                Then first
              Scenario: second
                Then second
        """.trimIndent()
        val before = parse(source)
        val background = parse(source.replace("Given login", "Given another login"))
        assertTrue(before.zip(background).all { (a, b) -> a.shared != b.shared })
        val scenario = parse(source.replace("Then first", "Then changed"))
        assertEquals(before[1], scenario[1])
        assertEquals(before[0].shared, scenario[0].shared)
    }

    @Test fun `localized keywords and docstrings are parsed without fake scenarios`() {
        val source = "# language: ru\nФункционал: проверка\n  Сценарий: первый\n    Допустим текст\n      \"\"\"\n      Scenario: not a test\n      \"\"\"\n  Сценарий: второй\n    Тогда всё хорошо\n"
        assertEquals(listOf("features/Test.feature:3", "features/Test.feature:8"), parse(source).map { it.name })
    }

    @Test fun `rule background is limited to its own rule`() {
        val source = """
            Feature: example
              Rule: one
                Background:
                  Given shared one
                Scenario: first
                  Then one
              Rule: two
                Scenario: second
                  Then two
        """.trimIndent()
        val before = parse(source)
        val after = parse(source.replace("shared one", "changed one"))
        assertNotEquals(before[0].shared, after[0].shared)
        assertEquals(before[1], after[1])
    }

    private val php = """
        <?php
        namespace App\Tests;
        use PHPUnit\Framework\TestCase;
        class CalculatorTest extends TestCase {
            protected function setUp(): void { prepare(); }
            public function testAdds(): void { assertSame(2, 1 + 1); }
            /** @test */
            public function subtracts(): void { assertSame(0, 1 - 1); }
            #[\PHPUnit\Framework\Attributes\Test]
            public function multiplies(): void { assertSame(1, 1 * 1); }
            public function helper(): void { work(); }
        }
    """.trimIndent()

    @Test fun `PHPUnit recognizes names annotations attributes and ignores helpers`() {
        val tests = ChangedTestSources.parse("tests/CalculatorTest.php", php)
        assertEquals(listOf("App\\Tests\\CalculatorTest::testAdds", "App\\Tests\\CalculatorTest::subtracts",
            "App\\Tests\\CalculatorTest::multiplies"), tests.map { it.name })
    }

    @Test fun `PHPUnit setup and providers affect all methods while a method edit stays local`() {
        val before = ChangedTestSources.parse("tests/CalculatorTest.php", php)
        val setup = ChangedTestSources.parse("tests/CalculatorTest.php", php.replace("prepare()", "prepareMore()"))
        assertTrue(before.zip(setup).all { (a, b) -> a.shared != b.shared })
        val edit = ChangedTestSources.parse("tests/CalculatorTest.php", php.replace("1 + 1", "2 + 0"))
        assertNotEquals(before[0].body, edit[0].body)
        assertTrue(before.zip(edit).all { (a, b) -> a.shared == b.shared })
        assertEquals(before[1], edit[1])
        assertEquals(before[2], edit[2])
    }

    @Test fun `PHP literals comments and anonymous classes cannot introduce test methods`() {
        val source = """
            <?php
            class StringsTest extends TestCase {
                public function testReal() {
                    print 'function testFake() { }';
                    // function testFakeComment() { }
                    new class { public function testNested() {} };
                    print <<<TEXT
            function testFakeHeredoc() { }
            TEXT;
                }
                public function testOther() {}
            }
        """.trimIndent()
        assertEquals(listOf("StringsTest::testReal", "StringsTest::testOther"),
            ChangedTestSources.parse("StringsTest.php", source).map { it.name })
    }
}
