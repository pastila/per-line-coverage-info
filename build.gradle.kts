import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.kotlinxSerialization) // Kotlinx serialization support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}

// Configure project's dependencies
repositories {
    mavenCentral()

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    val kotlinxSerializationJsonVersion = "1.8.1"
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationJsonVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationJsonVersion")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })

        testFramework(TestFrameworkType.Platform)
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
        failureLevel = (VerifyPluginTask.FailureLevel.ALL
            - VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS
            - VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES
            - VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES
            - VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES).toList()
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

// Configure Gradle Kover Plugin - read more: https://kotlin.github.io/kotlinx-kover/gradle-plugin/#configuration-details
kover {
    reports {
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

    val pluginId = providers.gradleProperty("pluginGroup")
    val pluginName = providers.gradleProperty("pluginName")
    val sinceBuild = providers.gradleProperty("pluginSinceBuild")
    val untilBuild = providers.gradleProperty("pluginUntilBuild")
    val repo = "https://github.com/yakov255/per-line-coverage-info"

    register("generateUpdatePluginsXml") {
        group = "distribution"
        description = "Generates docs/updatePlugins.xml from CHANGELOG.md for GitHub Pages"

        val docsDir = layout.projectDirectory.dir("docs")
        val changelogFile = layout.projectDirectory.file("CHANGELOG.md")

        inputs.file(changelogFile)
        outputs.dir(docsDir)

        doLast {
            val text = changelogFile.asFile.readText()
            val sections = text.split(Regex("(?m)^## "))

            val xml = buildString {
                appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                appendLine("<plugins>")
                for (section in sections) {
                    val headerMatch = Regex("""\[(\d[\w.-]*)\]""").find(section)
                    if (headerMatch == null) continue
                    val ver = headerMatch.groupValues[1].trim()
                    val bodyLines = section.lines().drop(1).dropWhile { it.isBlank() }
                    val rawNotes = bodyLines.joinToString("\n").trim()
                    val htmlNotes = if (rawNotes.isNotBlank()) markdownToHTML(rawNotes) else ""
                    val untilBuildAttribute = untilBuild.orNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { """ until-build="$it"""" }
                        ?: ""

                    appendLine("""    <plugin id="${pluginId.get()}" url="$repo/releases/download/$ver/per-line-coverage-info-$ver.zip" version="$ver">""")
                    appendLine("        <name>${pluginName.get()}</name>")
                    appendLine("        <vendor>yakov255</vendor>")
                    appendLine("""        <idea-version since-build="${sinceBuild.get()}"$untilBuildAttribute/>""")
                    if (htmlNotes.isNotBlank()) {
                        appendLine("        <change-notes><![CDATA[$htmlNotes]]></change-notes>")
                    }
                    appendLine("    </plugin>")
                }
                appendLine("</plugins>")
            }

            docsDir.asFile.mkdirs()
            docsDir.file("updatePlugins.xml").asFile.writeText(xml)
            logger.lifecycle("Generated docs/updatePlugins.xml with ${sections.count { Regex("""\[(\d[\w.-]*)\]""").containsMatchIn(it) }} version(s)")
        }
    }
}

// covd binaries are produced by the coverage-assistant repository:
//   make build-covd-all   →  dist/covd/covd-<os>-<arch>[.exe]
// Point the build at that directory with -PcovdDist=/path/to/dist/covd (or COVD_DIST).
val covdDistDir: File? = run {
    val configured = providers.gradleProperty("covdDist").orNull ?: System.getenv("COVD_DIST")
    if (configured != null) {
        File(configured).takeIf { it.isDirectory }
    } else {
        rootProject.projectDir.parentFile
            .resolve("raketa/yakov_scripts/coverage/coverage-assistant/dist/covd")
            .takeIf { it.isDirectory }
    }
}

val covdResourceDir = layout.buildDirectory.dir("covd-resources")

val covdPlatforms = listOf(
    Triple("covd-linux-amd64", "linux-x64", "covd"),
    Triple("covd-linux-arm64", "linux-arm64", "covd"),
    Triple("covd-darwin-amd64", "mac-x64", "covd"),
    Triple("covd-darwin-arm64", "mac-arm64", "covd"),
    Triple("covd-windows-amd64.exe", "win-x64", "covd.exe"),
)

val copyCovdBinaries by tasks.registering(Sync::class) {
    group = "build"
    description = "Bundles covd binaries from -PcovdDist into the plugin resources"
    into(covdResourceDir.map { it.dir("bin") })
    if (covdDistDir != null) {
        for ((sourceName, platform, targetName) in covdPlatforms) {
            val source = File(covdDistDir, sourceName)
            if (source.isFile) {
                from(source) {
                    into(platform)
                    rename { targetName }
                }
            }
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(copyCovdBinaries)
    from(covdResourceDir) { into("") }
}

tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
    // Unit tests run with the performance log off; a real IDE keeps it on by default.
    systemProperty("coverage.perf.log", "false")
}

// Heavy tests run against the real IDE home: without it the platform treats itself as
// Community, so bundled modules (com.intellij.modules.ultimate) and plugins stay unloaded.
// Resolved lazily (when the heavyTest JVM arguments are built), so that `./gradlew tasks` and
// every other task work without a downloaded platform or network.
val heavyIdeHomeProvider: Provider<String> =
    providers.provider { intellijPlatform.platformPath.toFile().absolutePath }

// Extra JVM arguments for IDE-launching tasks (javaagent, --add-opens). They are machine-local
// and never live in this file or in the repository: set ideaSandboxJvmArgs in
// ~/.gradle/gradle.properties (space-separated), e.g.
//   ideaSandboxJvmArgs=-javaagent:/absolute/path/agent.jar=options --add-opens=java.base/x.y=ALL-UNNAMED
// or pass the same value in the IDEA_SANDBOX_JVM_ARGS environment variable.
val sandboxExtraJvmArgs = providers.gradleProperty("ideaSandboxJvmArgs")
    .orElse(providers.environmentVariable("IDEA_SANDBOX_JVM_ARGS"))
    .map { raw -> raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }

// runIde uses its own sandbox config, which has no license: without one the platform disables
// JetBrains Ultimate, PHP and therefore this plugin. Copy the local IDE license (never commit
// it) into the sandbox before the IDE starts.
val ideConfigRootPath = File(System.getProperty("user.home"), ".config/JetBrains").absolutePath
val runIdeSandboxPath = layout.projectDirectory.dir(".intellijPlatform/sandbox/${project.name}").asFile.absolutePath

// ---- UI test settings: one place for everything the IDE process and the test JVM must agree on ----
// RemoteRobot server port (-PuiTestRobotPort=...), the PHP fixture whose gutter is asserted
// (-PuiTestPhpFile=..., git-root-relative) and the per-run bridge snapshot file in build/.
val uiTestRobotPort: String = providers.gradleProperty("uiTestRobotPort").getOrElse("8082")
val uiTestPhpFile: String = providers.gradleProperty("uiTestPhpFile")
    .getOrElse("app/raketa/raketa/Layer/Domain/AppServices/NotificationSystem/OrderEmailFilesBuilder.php")
val uiTestStateFile: String =
    layout.buildDirectory.file("uiTest-state/ui-test-state.json").get().asFile.absolutePath

/**
 * Sandbox directory of the platform version that runIdeForUiTests uses (the one holding its
 * `plugins_runIdeForUiTests`); the newest by name when several exist. Evaluated lazily: the
 * directory only exists after prepareSandbox.
 */
fun uiTestSandboxProductDir(): File? =
    File(runIdeSandboxPath).listFiles { f -> f.isDirectory }
        ?.sortedBy { it.name }
        ?.let { dirs -> dirs.lastOrNull { File(it, "plugins_runIdeForUiTests").isDirectory } ?: dirs.lastOrNull() }

val syncIdeLicense by tasks.registering {
    group = "intellij platform"
    description = "Copies the local IDE license into the runIde sandbox and enables JetBrains Ultimate"
    mustRunAfter(tasks.named("prepareSandbox"))
    notCompatibleWithConfigurationCache("walks the local IDE config and sandbox directories")

    // Local copies: referencing script-level properties from a task action breaks the
    // configuration cache (the script object is not serializable).
    val licenseSourceRoot = ideConfigRootPath
    val sandboxRoot = runIdeSandboxPath

    doLast {
        val licenseNames = listOf("idea.key", "phpstorm.key", "plugin_PCWMP.license")
        val candidates = File(licenseSourceRoot)
            .listFiles()
            ?.filter { it.isDirectory && (it.name.startsWith("IntelliJIdea") || it.name.startsWith("PhpStorm")) }
            ?.filter { dir -> licenseNames.any { File(dir, it).isFile } }
            ?.sortedByDescending { it.name }
            .orEmpty()

        // Each license file comes from the newest local config that has it: the sandbox IDE is
        // IntelliJ IDEA Ultimate and needs idea.key, which a PhpStorm config does not carry.
        val licenseFiles = licenseNames.mapNotNull { name ->
            candidates.map { File(it, name) }.firstOrNull { it.isFile }
        }

        if (licenseFiles.isEmpty()) {
            return@doLast
        }

        val targets = File(sandboxRoot)
            .listFiles()
            ?.filter { it.isDirectory }
            // config: runIde; config_runIdeForUiTests: the isolated sandbox of the UI test IDE.
            ?.flatMap { product -> listOf(File(product, "config"), File(product, "config_runIdeForUiTests")) }
            ?.filter { it.isDirectory || it.mkdirs() }
            .orEmpty()

        for (configDir in targets) {
            for (license in licenseFiles) {
                license.copyTo(File(configDir, license.name), overwrite = true)
            }
            val disabled = File(configDir, "disabled_plugins.txt")
            if (disabled.isFile) {
                val remaining = disabled.readLines().filterNot { it.trim() == "com.intellij.modules.ultimate" }
                disabled.writeText(remaining.joinToString("\n"))
            }
        }
        // No logger call here: Gradle script references are not serializable by the
        // configuration cache.
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=$uiTestRobotPort",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }

            plugins {
                robotServerPlugin()
            }
        }
    }

    // Heavy integration tests run inside a full IDE process with the plugin installed.
    testIde {
        register("heavyTest") {
            plugins {
                // testIde gets its own platform configuration, so bundled plugins declared
                // for the regular test task must be declared again here.
                bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
                // Dependencies of the PHP/Gherkin plugins: the test IDE loads only the
                // plugins listed here, not the whole product bundle.
                bundledPlugins("org.intellij.intelliLang", "com.intellij.modules.json")
                bundledModules("com.intellij.modules.ultimate")
            }

            task {
                group = "verification"
                description = "Runs heavy integration tests in a full IDE process"
                useJUnit()
                filter { includeTestsMatching("com.github.yakov255.perlinecoverageinfo.heavy.*") }
                systemProperty("coverage.perf.log", "false")
                // A local copy: a lambda that reads the script property would capture the
                // script object, which the configuration cache cannot serialize.
                val ideHome = heavyIdeHomeProvider
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf("-Didea.home.path=${ideHome.get()}")
                }
                sandboxExtraJvmArgs.orNull?.let { jvmArgs(it) }
                maxHeapSize = "2g"
            }
        }
    }
}

// The default unit-test task stays fast: heavy tests need the IDE process.
tasks.test {
    filter { excludeTestsMatching("com.github.yakov255.perlinecoverageinfo.heavy.*") }
}

// Registered after intellijPlatformTesting { ... }, which creates the runIde* tasks.
tasks.named<JavaExec>("runIde") {
    dependsOn(syncIdeLicense)
    sandboxExtraJvmArgs.orNull?.let { jvmArgs(it) }
}

// ---- Out-of-process UI tests (RemoteRobot) ----
// The IDE is started separately by runIdeForUiTests (see scripts/ui-test.sh); the uiTest JVM is
// only a RemoteRobot client. It must be a PLAIN Test task: testIde/testIdeUi would boot an IDE
// inside the test JVM.
val uiTestSourceSet: SourceSet = sourceSets.create("uiTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val uiTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val uiTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    uiTestImplementation(libs.remote.robot)
    uiTestImplementation(libs.remote.fixtures)
    // Not exposed by remote-robot at compile time; IdeTestCase builds the HTTP client with it.
    uiTestImplementation(libs.okhttp)
    // JUnit 4 is needed only by the remote-fixtures library itself.
    uiTestImplementation(libs.junit)
    uiTestImplementation(libs.junit.jupiter.api)
    // Literal versions: the catalog only carries the API artifact. Engine and launcher are required
    // at runtime for useJUnitPlatform() because remote-fixtures pulls no JUnit.
    uiTestRuntimeOnly(libs.junit.jupiter.engine)
    uiTestRuntimeOnly(libs.junit.platform.launcher)
}

val uiTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs RemoteRobot UI tests against an IDE started by runIdeForUiTests"
    testClassesDirs = uiTestSourceSet.output.classesDirs
    classpath = uiTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    systemProperty("robot.server.url", providers.systemProperty("robot.server.url").getOrElse("http://127.0.0.1:$uiTestRobotPort"))
    systemProperty("uiTest.stateFile", uiTestStateFile)
    // The scenario asserts on this file; an explicit UI_TEST_PHP_FILE in the environment wins.
    if (System.getenv("UI_TEST_PHP_FILE").isNullOrBlank()) environment("UI_TEST_PHP_FILE", uiTestPhpFile)
    systemProperty("java.awt.headless", "false")
    // RemoteRobot's Gson reflects into Throwable when the IDE returns an error; without this the
    // real failure is replaced by InaccessibleObjectException.
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED", "--add-opens", "java.base/java.util=ALL-UNNAMED")
    // Where the failure diagnostics go, and where the sandbox IDE keeps its idea.log.
    systemProperty("uiTest.artifacts", layout.buildDirectory.dir("uiTest-artifacts").get().asFile.absolutePath)
    // Lazy: the sandbox directory exists only after prepareSandbox has run. The lambda captures
    // only this local String (no script reference), which keeps the task configuration-cache safe;
    // the lookup therefore repeats uiTestSandboxProductDir() instead of calling it.
    val sandboxRootForLog = runIdeSandboxPath
    doFirst {
        val productDir = File(sandboxRootForLog).listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.let { dirs -> dirs.lastOrNull { File(it, "plugins_runIdeForUiTests").isDirectory } ?: dirs.lastOrNull() }
        val logFile = productDir?.let { File(it, "log_runIdeForUiTests/idea.log") }
            ?: File(sandboxRootForLog, "idea.log")
        systemProperty("uiTest.sandboxLog", logFile.absolutePath)
    }
    maxParallelForks = 1
    forkEvery = 1
    // UI tests are never up to date: they depend on external IDE state.
    outputs.upToDateWhen { false }
    testLogging {
        events(*org.gradle.api.tasks.testing.logging.TestLogEvent.values())
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// Option chosen: keep the sandbox the plugin already prepares (it holds the installed plugin, and
// the SandboxArgumentProvider's -Dplugin.path points at it) and get a clean state by deleting its
// config/system/log. plugins is kept. Relocating config/system would not make the plugin load
// because -Dplugin.path would still point at the original sandbox.
// runIdeForUiTests already has its own suffixed directories (config_runIdeForUiTests, ...), so
// the plain runIde sandbox is never touched.
val uiTestSandbox by tasks.registering {
    group = "intellij platform"
    description = "Wipes sandbox config/system/log (keeps plugins) so the UI test IDE starts from a clean first run"
    mustRunAfter(tasks.matching { it.name.startsWith("prepareSandbox") })

    val sandboxRoot = runIdeSandboxPath
    val stateFile = uiTestStateFile
    doLast {
        File(sandboxRoot).listFiles()?.filter { it.isDirectory }?.forEach { productDir ->
            for (name in listOf("config_runIdeForUiTests", "system_runIdeForUiTests", "log_runIdeForUiTests")) {
                File(productDir, name).deleteRecursively()
            }
        }
        // A snapshot left by an earlier run must never be mistaken for this run's state.
        File(stateFile).delete()
    }
}

// Order: prepareSandbox -> uiTestSandbox (clean) -> syncIdeLicense (seed license) -> runIdeForUiTests.
syncIdeLicense.configure { mustRunAfter(uiTestSandbox) }

tasks.named<JavaExec>("runIdeForUiTests") {
    dependsOn(syncIdeLicense)
    dependsOn(uiTestSandbox)
    sandboxExtraJvmArgs.orNull?.let { jvmArgs(it) }
    // First-run suppression: no trust dialog, tips or consent prompts block the window.
    // The bridge lets the UI test read real plugin state (commit, files, per-line colours),
    // which RemoteRobot cannot see through Swing; `coverage.uiTestFiles` limits the dump to the
    // file the scenario asserts on.
    jvmArgs(
        "-Didea.trust.all.projects=true",
        "-Dide.show.tips.on.startup.default.value=false",
        "-Djb.consents.confirmation.enabled=false",
        "-Dcoverage.uiTestBridge=true",
        // The raketa project (with vendor/) outgrows the default heap while indexing, and a heap
        // dump written into the unpacked platform makes Gradle reject that immutable directory.
        "-Xmx4g",
        "-XX:-HeapDumpOnOutOfMemoryError",
        "-Dcoverage.uiTestFiles=$uiTestPhpFile",
        "-Dcoverage.uiTestState=$uiTestStateFile",
    )
    // Without an explicit path the IDE logs to ~/.cache/JetBrains/<product>/log, where neither
    // scripts/ui-test.sh nor the failure diagnostics look for it. Built lazily, after
    // prepareSandbox has created the directory; it also tags the process for scripts/ui-test.sh.
    jvmArgumentProviders += CommandLineArgumentProvider {
        uiTestSandboxProductDir()
            ?.let { listOf("-Didea.log.path=${File(it, "log_runIdeForUiTests").absolutePath}") }
            .orEmpty()
    }
}

// ---- Real-project testing ("тестирование на реальном проекте") ----
// Load/failure scenarios that drive a sandbox IDE and the covd daemon against the real raketa
// repository through a reflecting proxy (see docs/real-project-testing.md). A plain JVM Test
// task: it starts and stops IDE processes itself, so it is never part of `test`.
val realProjectSourceSet: SourceSet = sourceSets.create("realProject")

val realProjectImplementation: Configuration by configurations.getting
val realProjectRuntimeOnly: Configuration by configurations.getting

dependencies {
    realProjectImplementation(libs.junit)
    realProjectImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}

val realProjectTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs real-project load and failure scenarios (long; needs a licensed sandbox IDE and network)"
    testClassesDirs = realProjectSourceSet.output.classesDirs
    classpath = realProjectSourceSet.runtimeClasspath
    useJUnit()
    // Scenarios share network, CPU and memory: never run them in parallel.
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    systemProperty("realProject.outputDir", layout.buildDirectory.dir("real-project").get().asFile.absolutePath)
    systemProperty("realProject.projectDir", layout.projectDirectory.asFile.absolutePath)
    // Optional knobs forwarded from -P / the environment (never the GitLab token).
    for (name in listOf(
        "realProject.scenario", "realProject.runs", "realProject.worktree", "realProject.plan",
        "realProject.repo", "realProject.gitlab", "realProject.projectId", "realProject.pipelines",
        "realProject.pauseMs", "realProject.ide", "realProject.tour", "realProject.r8.branches",
    )) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
