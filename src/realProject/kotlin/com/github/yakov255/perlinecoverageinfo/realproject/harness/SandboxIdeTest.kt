package com.github.yakov255.perlinecoverageinfo.realproject.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure parts of [SandboxIde]: layout, seed files, VM options and command line, plus the tolerant
 * bridge parser. No IDE process is started here; see [SandboxIdeIntegrationTest] for that.
 */
class SandboxIdeTest {

    private val temp: File = Files.createTempDirectory("rp-sandbox-test").toFile()

    private fun fakeIdeaHome(): File = File(temp, "idea-2025.2.6.2").apply {
        File(this, "bin").mkdirs()
        File(this, "lib").mkdirs()
        File(this, "bin/idea.sh").writeText("#!/bin/sh\n")
        File(this, "lib/app.jar").writeText("")
        File(this, "product-info.json").writeText("""{"name":"IntelliJ IDEA","version":"2025.2.6.2","productCode":"IU"}""")
    }

    private fun fakePlugins(): File = File(temp, "sandbox/plugins").apply {
        File(this, "per-line-coverage-info/lib").mkdirs()
        File(this, "php-impl").mkdirs()
    }

    private fun fakeLicenseRoot(): File = File(temp, "jetbrains").apply {
        File(this, "IntelliJIdea2025.2").mkdirs()
        File(this, "IntelliJIdea2025.2/idea.key").writeText("license-placeholder")
    }

    private fun options(root: File = File(temp, "instance")): SandboxIde.Options = SandboxIde.Options(
        ideaHome = fakeIdeaHome(),
        pluginsDir = fakePlugins(),
        rootDir = root,
        licenseSourceRoot = fakeLicenseRoot(),
        localGradleProperties = File(temp, "missing-gradle.properties"),
    )

    private fun prepared(root: File = File(temp, "instance")): SandboxIde.Prepared {
        val project = File(temp, "project").apply { mkdirs() }
        return SandboxIde.prepare("ide-1", project, "http://127.0.0.1:41000", options(root), temp)
    }

    @Test
    fun `settings xml carries every state field and escapes values`() {
        val xml = SandboxIde.coverageApiSettingsXml(
            gitlabDomain = "http://127.0.0.1:41000/?a=1&b=\"2\"",
            bearerToken = "dummy",
            projectId = 335,
            projectName = "raketa/raketa",
            coverageBranch = "master",
        )
        assertTrue(xml.contains("<component name=\"CoverageApiSettings\">"))
        assertTrue(xml.contains("name=\"gitlabDomain\" value=\"http://127.0.0.1:41000/?a=1&amp;b=&quot;2&quot;\""))
        assertTrue(xml.contains("name=\"bearerToken\" value=\"dummy\""))
        assertTrue(xml.contains("name=\"gitlabProjectId\" value=\"335\""))
        assertTrue(xml.contains("name=\"gitlabProjectName\" value=\"raketa/raketa\""))
        assertTrue(xml.contains("name=\"coverageBranch\" value=\"master\""))
        assertTrue(xml.contains("name=\"enabled\" value=\"true\""))
        assertTrue(xml.contains("name=\"tokenPrompted\" value=\"true\""))
        assertTrue(xml.trim().startsWith("<?xml"))
    }

    @Test
    fun `prepare writes isolated dirs seed files and vm options`() {
        val root = File(temp, "instance")
        val p = prepared(root)

        assertEquals(root, p.rootDir)
        assertTrue(p.configDir.isDirectory)
        assertTrue(p.systemDir.isDirectory)
        assertTrue(p.logDir.isDirectory)
        assertTrue(p.cacheRoot.isDirectory)
        assertTrue(p.perfDir.isDirectory)
        assertTrue(p.covdResponsesDir.isDirectory)
        assertTrue(p.settingsFile.isFile)
        assertTrue(File(p.configDir, "options/ide.general.xml").isFile)
        assertTrue(File(p.configDir, "idea.key").isFile)
        assertFalse("instance must not write a log before the IDE starts", p.logDir.listFiles().orEmpty().isNotEmpty())

        val settings = p.settingsFile.readText()
        assertTrue(settings.contains("value=\"http://127.0.0.1:41000\""))
        assertTrue(settings.contains("name=\"bearerToken\" value=\"dummy\""))
        assertTrue(settings.contains("name=\"enabled\" value=\"true\""))

        val vmOptions = p.vmOptionsFile.readText()
        assertTrue(vmOptions.lineSequence().first() == "-Xmx4g")
        assertTrue(vmOptions.contains("-XX:-HeapDumpOnOutOfMemoryError"))
        assertTrue(vmOptions.contains("-Dcoverage.cacheRoot=${p.cacheRoot.absolutePath}"))
        assertTrue(vmOptions.contains("-Dcoverage.perf.dir=${p.perfDir.absolutePath}"))
        assertTrue(vmOptions.contains("-Dcoverage.covdResponses=${p.covdResponsesDir.absolutePath}"))
        assertTrue(vmOptions.contains("-Dcoverage.uiTestBridge=true"))
        assertTrue(vmOptions.contains("-Dcoverage.uiTestState=${p.stateFile.absolutePath}"))
        assertTrue(vmOptions.contains("-Didea.config.path=${p.configDir.absolutePath}"))
        assertTrue(vmOptions.contains("-Didea.system.path=${p.systemDir.absolutePath}"))
        assertTrue(vmOptions.contains("-Didea.log.path=${p.logDir.absolutePath}"))
        assertTrue(vmOptions.contains("-Didea.plugins.path=${p.pluginsDir.absolutePath}"))
        assertTrue(vmOptions.contains("-Didea.trust.all.projects=true"))
        // The bridge would otherwise classify nothing.
        assertFalse(vmOptions.contains("coverage.uiTestFiles"))
    }

    @Test
    fun `extra props override defaults and files are joined`() {
        val project = File(temp, "project").apply { mkdirs() }
        val p = SandboxIde.prepare(
            "ide-2",
            project,
            "http://127.0.0.1:41000",
            options().copy(
                uiTestFiles = listOf("api/hotels/src/A.php", "app/B.php"),
                extraProps = mapOf("coverage.uiTestBridge" to "true", "custom.flag" to "1"),
                extraJvmArgs = listOf("--add-opens=java.base/java.lang=ALL-UNNAMED"),
                maxHeap = "6g",
            ),
            temp,
        )
        assertTrue(p.vmOptionsFile.readText().contains("-Xmx6g"))
        assertTrue(p.vmOptionsFile.readText().contains("--add-opens=java.base/java.lang=ALL-UNNAMED"))
        assertTrue(p.vmOptionsFile.readText().contains("-Dcoverage.uiTestFiles=api/hotels/src/A.php,app/B.php"))
        assertTrue(p.vmOptionsFile.readText().contains("-Dcustom.flag=1"))
        assertEquals("true", p.properties["coverage.uiTestBridge"])
    }

    @Test
    fun `launch command is idea dot sh with the project directory`() {
        val p = prepared()
        val command = p.launchCommand()
        assertEquals(File(p.ideaHome, "bin/idea.sh").absolutePath, command[0])
        assertEquals(p.projectDir.absolutePath, command[1])
        assertEquals(p.vmOptionsFile.absolutePath, p.launchEnvironment()["IDEA_VM_OPTIONS"])
    }

    @Test
    fun `fresh start wipes the previous state but keeps a restart`() {
        val root = File(temp, "restart")
        val project = File(temp, "project").apply { mkdirs() }
        SandboxIde.prepare("ide-r", project, "http://127.0.0.1:41000", options(root), temp)
        File(root, "config/marker.txt").writeText("kept")
        File(root, "ui-test-state.json").writeText("""{"state":"LOADED"}""")

        val restarted = SandboxIde.prepare(
            "ide-r",
            project,
            "http://127.0.0.1:41000",
            options(root).copy(freshStart = false),
            temp,
        )
        assertTrue(File(restarted.configDir, "marker.txt").isFile)
        assertTrue("a restart keeps the last snapshot until the bridge rewrites it", restarted.stateFile.isFile)

        val fresh = SandboxIde.prepare(
            "ide-r",
            project,
            "http://127.0.0.1:41000",
            options(root).copy(freshStart = true),
            temp,
        )
        assertFalse(File(fresh.configDir, "marker.txt").isFile)
        assertFalse(fresh.stateFile.isFile)
    }

    @Test
    fun `platform lookup prefers idea IU of the declared version`() {
        val caches = File(temp, "caches")
        fun platform(gradle: String, name: String, code: String): File {
            val dir = File(caches, "$gradle/transforms/hash/transformed/$name")
            File(dir, "bin").mkdirs()
            File(dir, "lib").mkdirs()
            File(dir, "bin/idea.sh").writeText("#!/bin/sh\n")
            File(dir, "lib/app.jar").writeText("")
            File(dir, "product-info.json").writeText("""{"version":"2025.2.6.2","productCode":"$code"}""")
            return dir
        }
        platform("9.3.1", "ideaIU-2025.2.6.2", "IU")
        platform("9.3.1", "ideaIC-2025.2.6.2", "IC")
        platform("9.5.0", "ideaIU-2025.2", "IU")

        val found = SandboxIde.findIdeaHome(temp, caches, version = "2025.2.6.2")
        assertNotNull(found)
        assertEquals("ideaIU-2025.2.6.2", found!!.name)

        File(temp, "gradle.properties").writeText("platformVersion = 2025.2.6.2\n")
        assertEquals("2025.2.6.2", SandboxIde.readPlatformVersion(temp))
        assertEquals(found, SandboxIde.findIdeaHome(temp, caches))
    }

    @Test
    fun `platform lookup returns null without a matching product`() {
        val caches = File(temp, "empty-caches")
        File(caches, "9.3.1/transforms/hash/transformed").mkdirs()
        assertNull(SandboxIde.findIdeaHome(temp, caches, version = "2025.2.6.2"))
    }

    @Test
    fun `local sandbox jvm args are read without exposing the rest of the file`() {
        val file = File(temp, "gradle.properties")
        file.writeText(
            """
            org.gradle.jvmargs=-Xmx2g
            ideaSandboxJvmArgs=-javaagent:/tmp/agent.jar=opt --add-opens=java.base/java.lang=ALL-UNNAMED
            someSecret=not-read
            """.trimIndent(),
        )
        assertEquals(
            listOf("-javaagent:/tmp/agent.jar=opt", "--add-opens=java.base/java.lang=ALL-UNNAMED"),
            SandboxIde.localSandboxJvmArgs(file),
        )
        assertEquals(emptyList<String>(), SandboxIde.localSandboxJvmArgs(File(temp, "nope.properties")))
    }

    @Test
    fun `license installation copies the files and enables ultimate`() {
        val config = File(temp, "license-config").apply { mkdirs() }
        File(config, "disabled_plugins.txt").writeText("com.intellij.modules.ultimate\nsome.other.plugin\n")
        val copied = SandboxIde.installLicense(config, fakeLicenseRoot())
        assertEquals(listOf("idea.key"), copied)
        assertTrue(File(config, "idea.key").isFile)
        assertEquals(listOf("some.other.plugin"), File(config, "disabled_plugins.txt").readLines())
    }

    @Test
    fun `license installation is a no-op without local keys`() {
        val config = File(temp, "license-config-empty").apply { mkdirs() }
        assertEquals(emptyList<String>(), SandboxIde.installLicense(config, File(temp, "no-licenses")))
        assertFalse(File(config, "idea.key").exists())
    }

    @Test
    fun `vm option values with spaces stay one argument`() {
        assertEquals("plain", SandboxIde.quoteVmOptionValue("plain"))
        assertEquals("\"a b\"", SandboxIde.quoteVmOptionValue("a b"))
        assertEquals("\"C:\\\\dir x\"", SandboxIde.quoteVmOptionValue("C:\\dir x"))
    }

    @Test
    fun `bridge snapshot parses the documented fields`() {
        val snapshot = BridgeSnapshot.parse(
            """
            {"state":"LOADED","project":"raketa","commit":"abcdef1234567890","baselineCommit":"1111222233334444",
             "stale":false,"headHash":"abcdef1234567890","gutterVisible":true,"files":21700,"artifacts":2,
             "error":null,"lastError":null,"loading":false,"coverageGeneration":7,"dumb":false,
             "covdInstanceId":"deadbeefcafe","covdPid":4242,
             "lines":{"app/A.php":{"10":"COVERED","12":"UNCOVERED"}},"updatedAt":1760000000000,
             "unknownField":{"nested":true}}
            """.trimIndent(),
        )
        assertEquals("LOADED", snapshot.state)
        assertEquals("raketa", snapshot.project)
        assertEquals("abcdef1234567890", snapshot.commit)
        assertEquals("1111222233334444", snapshot.baselineCommit)
        assertEquals(false, snapshot.stale)
        assertEquals("abcdef1234567890", snapshot.headHash)
        assertEquals(true, snapshot.gutterVisible)
        assertEquals(21700, snapshot.files)
        assertEquals(2, snapshot.artifacts)
        assertNull(snapshot.lastError)
        assertEquals(false, snapshot.loading)
        assertEquals(7L, snapshot.coverageGeneration)
        assertEquals(false, snapshot.dumb)
        assertEquals("deadbeefcafe", snapshot.covdInstanceId)
        assertEquals(4242L, snapshot.covdPid)
        assertEquals(mapOf(10 to "COVERED", 12 to "UNCOVERED"), snapshot.lines["app/A.php"])
        assertEquals(1760000000000L, snapshot.updatedAt)
        assertTrue(snapshot.isLoaded)
        assertTrue(snapshot.raw.containsKey("unknownField"))
    }

    @Test
    fun `bridge snapshot accepts old builds and missing fields`() {
        val old = BridgeSnapshot.parse("""{"state":"NOT_CONFIGURED","error":"token expired","lines":{}}""")
        assertEquals("NOT_CONFIGURED", old.state)
        assertEquals("token expired", old.lastError)
        assertNull(old.dumb)
        assertNull(old.coverageGeneration)
        assertTrue(old.lines.isEmpty())

        val empty = BridgeSnapshot.parse("{}")
        assertNull(empty.state)
        assertNull(empty.commit)
        assertFalse(empty.isLoaded)
    }

    @Test
    fun `snapshot json builder keeps values unquoted`() {
        val raw: JsonObject = SandboxIdeTestSupport.json(
            "state" to "LOADED",
            "files" to 3,
        )
        val parsed = BridgeSnapshot.parse(raw)
        assertEquals("LOADED", parsed.state)
        assertEquals(3, parsed.files)
        assertEquals("LOADED", raw["state"]!!.jsonPrimitive.content)
    }

    private object SandboxIdeTestSupport {
        fun json(vararg entries: Pair<String, Any>): JsonObject {
            val text = entries.joinToString(",", "{", "}") { (key, value) ->
                "\"$key\":" + if (value is String) "\"$value\"" else value.toString()
            }
            return Json.parseToJsonElement(text).jsonObject
        }
    }
}
