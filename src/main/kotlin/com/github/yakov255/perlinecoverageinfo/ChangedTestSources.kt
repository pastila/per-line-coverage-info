package com.github.yakov255.perlinecoverageinfo

import org.jetbrains.plugins.cucumber.psi.i18n.JsonGherkinKeywordProvider

/** Historical source parsing without PHP-plugin classes or an indexed working tree. */
internal object ChangedTestSources {
    data class Test(
        val key: String,
        val name: String,
        val line: Int,
        val body: String,
        val shared: String,
        val outline: Boolean = false,
        val examples: List<Example> = emptyList(),
    )

    data class Example(val line: Int, val table: String, val cells: Map<String, String>)

    fun parse(path: String, content: String): List<Test> = when {
        path.endsWith(".feature") -> feature(path, content)
        path.endsWith(".php") -> php(content)
        else -> emptyList()
    }

    private fun feature(path: String, content: String): List<Test> {
        val lines = content.lines()
        val locale = Regex("(?m)^\\s*#\\s*language:\\s*([\\w-]+)").find(content)?.groupValues?.get(1) ?: "en"
        val table = JsonGherkinKeywordProvider.getKeywordProvider(true).getKeywordsTable(locale)
        val keywords = buildMap {
            table.scenarioKeywords.forEach { put(it, "scenario") }
            table.scenarioOutlineKeywords.forEach { put(it, "outline") }
            table.backgroundKeywords.forEach { put(it, "background") }
            table.featuresSectionKeywords.forEach { put(it, "feature") }
            table.ruleKeywords.forEach { put(it, "rule") }
        }
        data class Heading(val start: Int, val line: Int, val kind: String, val title: String)
        val headings = mutableListOf<Heading>()
        var docString: String? = null
        for ((i, raw) in lines.withIndex()) {
            val text = raw.trim()
            val delimiter = listOf("\"\"\"", "```").firstOrNull { text.startsWith(it) }
            if (delimiter != null) {
                if (docString == null) docString = delimiter else if (docString == delimiter) docString = null
                continue
            }
            if (docString != null) continue
            val keyword = text.substringBefore(':')
            val kind = keywords[keyword] ?: continue
            if (':' !in text) continue
            var start = i
            while (start > 0 && (lines[start - 1].isBlank() ||
                    lines[start - 1].trimStart().startsWith('@') || lines[start - 1].trimStart().startsWith('#'))) start--
            headings += Heading(start, i + 1, kind, text.substringAfter(':').trim())
        }
        val result = mutableListOf<Test>()
        var featureHeader = ""
        var featureBackground = ""
        var ruleHeader = ""
        var ruleBackground = ""
        var ruleTitle = ""
        var scope = ""
        val occurrences = mutableMapOf<String, Int>()
        for ((i, heading) in headings.withIndex()) {
            val end = headings.getOrNull(i + 1)?.start ?: lines.size
            val block = lines.subList(heading.start, end).joinToString("\n").trim()
            when (heading.kind) {
                "feature" -> {
                    featureHeader = block
                    featureBackground = ""
                    ruleHeader = ""
                    ruleBackground = ""
                    ruleTitle = ""
                    scope = heading.title
                }
                "rule" -> {
                    ruleHeader = block
                    ruleBackground = ""
                    ruleTitle = heading.title
                }
                "background" -> if (ruleHeader.isEmpty()) featureBackground = block else ruleBackground = block
                "scenario", "outline" -> {
                    val id = "$scope\u0000$ruleTitle\u0000${heading.title}"
                    val occurrence = occurrences.merge(id, 1, Int::plus)!!
                    result += Test("$id\u0000$occurrence", "$path:${heading.line}", heading.line, block,
                        listOf(featureHeader, featureBackground, ruleHeader, ruleBackground).joinToString("\n"),
                        outline = heading.kind == "outline",
                        examples = if (heading.kind == "outline") examples(lines, heading.line, end, table.exampleSectionKeywords) else emptyList())
                }
            }
        }
        return result
    }

    private fun examples(lines: List<String>, start: Int, end: Int, keywords: Collection<String>): List<Example> {
        val result = mutableListOf<Example>()
        var title: String? = null
        var headers: List<String>? = null
        var docString: String? = null
        for (i in start until end) {
            val text = lines[i].trim()
            val delimiter = listOf("\"\"\"", "```").firstOrNull { text.startsWith(it) }
            if (delimiter != null) {
                if (docString == null) docString = delimiter else if (docString == delimiter) docString = null
                continue
            }
            if (docString != null) continue
            if (':' in text && text.substringBefore(':') in keywords) {
                title = text.substringAfter(':').trim()
                headers = null
            } else if (title != null && text.startsWith('|')) {
                val cells = tableCells(text)
                val columns = headers
                if (columns == null) headers = cells
                else if (columns.size == cells.size && columns.distinct().size == columns.size) {
                    result += Example(i + 1, title, columns.zip(cells).toMap())
                }
            }
        }
        return result
    }

    private fun tableCells(text: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var escaped = false
        for (c in text.drop(1)) {
            if (escaped) {
                if (c !in listOf('n', '|', '\\')) cell.append('\\')
                cell.append(if (c == 'n') '\n' else c)
                escaped = false
            } else when (c) {
                '\\' -> escaped = true
                '|' -> { cells += cell.toString().trim(); cell.setLength(0) }
                else -> cell.append(c)
            }
        }
        return cells
    }

    private fun php(content: String): List<Test> {
        // Preserve offsets and newlines while hiding literals/comments from brace and declaration scans.
        val code = PHP_LITERALS.replace(content) { match ->
            match.value.map { if (it == '\n' || it == '\r') it else ' ' }.joinToString("")
        }
        val result = mutableListOf<Test>()
        for (cls in CLASS.findAll(code)) {
            val className = cls.groupValues[1]
            val open = code.indexOf('{', cls.range.last + 1)
            if (open < 0) continue
            val close = closingBrace(code, open) ?: continue
            val declaration = code.substring(cls.range.first, open)
            if (!className.endsWith("Test") && !Regex("\\bextends\\s+[\\\\\\w]*TestCase\\b").containsMatchIn(declaration)) continue
            val namespace = NAMESPACE.findAll(code.substring(0, cls.range.first)).lastOrNull()?.groupValues?.get(1).orEmpty()
            val fullClass = if (namespace.isEmpty()) className else "$namespace\\$className"
            data class Method(val name: String, val start: Int, val end: Int, val test: Boolean)
            val methods = mutableListOf<Method>()
            var boundary = open + 1
            var cursor = open + 1
            while (cursor < close) {
                val method = METHOD.find(code, cursor) ?: break
                if (method.range.first >= close) break
                // Skip nested anonymous classes/closures instead of treating their methods as tests.
                val prefix = code.substring(open + 1, method.range.first)
                if (prefix.count { it == '{' } != prefix.count { it == '}' }) {
                    cursor = method.range.last + 1
                    continue
                }
                val bodyOpen = code.indexOf('{', method.range.last + 1)
                val semicolon = code.indexOf(';', method.range.last + 1)
                val end = if (semicolon >= 0 && (bodyOpen < 0 || semicolon < bodyOpen)) semicolon + 1
                    else (closingBrace(code, bodyOpen) ?: break) + 1
                if (end > close) break
                val name = method.groupValues[1]
                // A preceding property ends at ';'; documentation and attributes belong to the method.
                val start = maxOf(boundary, code.lastIndexOf(';', method.range.first) + 1)
                val annotations = content.substring(start, method.range.first)
                val isTest = name.startsWith("test") || Regex("@test\\b|#\\[\\s*(?:[\\\\\\w]+\\\\)?Test\\s*(?:[,(\\]])")
                    .containsMatchIn(annotations)
                methods += Method(name, start, end, isTest)
                cursor = end
                boundary = end
            }
            val tests = methods.filter { it.test }
            val shared = StringBuilder(content.substring(0, open + 1))
            var offset = open + 1
            for (test in tests) {
                shared.append(content.substring(offset, test.start))
                offset = test.end
            }
            shared.append(content.substring(offset, close))
            for (test in tests) result += Test("$fullClass::${test.name}", "$fullClass::${test.name}",
                content.take(test.start).count { it == '\n' } + 1,
                content.substring(test.start, test.end).trim(), shared.toString())
        }
        return result
    }

    private fun closingBrace(code: String, open: Int): Int? {
        if (open < 0) return null
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}' && --depth == 0) return i
        }
        return null
    }

    private val CLASS = Regex("\\bclass\\s+([A-Za-z_][\\w]*)")
    private val NAMESPACE = Regex("\\bnamespace\\s+([\\w\\\\]+)\\s*[;{]")
    private val METHOD = Regex("\\bfunction\\s+&?\\s*([A-Za-z_]\\w*)\\s*\\(")
    private val PHP_LITERALS = Regex(
        """(?s)/\*.*?\*/|//[^\n]*|\#(?!\[)[^\n]*|'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`|<<<[ \t]*['"]?(\w+)['"]?[^\n]*\n.*?\n[ \t]*\1(?=;|\s|$)""",
    )
}
