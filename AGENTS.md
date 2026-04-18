# Per-Line Coverage Info — IntelliJ Plugin

## Overview

An IntelliJ/PhpStorm plugin that displays per-line PHP code coverage inline in the editor. Coverage is collected by CI (Behat with a custom PHP extension that emits `.covt` files), downloaded from GitLab pipeline artifacts, cached locally in a random-access binary format, and rendered via gutter icons + line backgrounds. Each covered line exposes the list of tests that execute it, and individual tests can be re-run from the gutter popup.

- **Plugin ID**: `com.github.yakov255.perlinecoverageinfo`
- **Platform**: PhpStorm 2024.2.5+, depends on `com.jetbrains.php`, `gherkin`, optional `com.jetbrains.php.behat`, optional `Git4Idea`
- **Language**: Kotlin, JVM 21
- **Build**: Gradle with IntelliJ Platform Gradle Plugin
- **Serialization**: kotlinx-serialization-json

## Project Layout

Plugin source: `src/main/kotlin/com/github/yakov255/perlinecoverageinfo/`
Tests: `src/test/kotlin/com/github/yakov255/perlinecoverageinfo/`
Plugin manifest: `src/main/resources/META-INF/plugin.xml` (+ `behat-integration.xml` for optional Behat deps, `git-integration.xml` for optional Git4Idea deps)
Related artifacts in repo root: `php-behat-coverage-extension/` (the PHP extension that produces `.covt`), `behat-decompiled/`, `php-sample-code/`, `coverage_storage_format_v4.md` (on-disk COV4 spec).

## Source Files

### Settings & configuration
| File | Purpose |
|------|---------|
| `CoverageApiSettings.kt` | Persistent application-level settings: `gitlabDomain`, `bearerToken` (PRIVATE-TOKEN), `gitlabProjectId`, `gitlabProjectName`, `coverageBranch` (default `behat-run-necessary-tests`), `enabled` (master on/off switch), `remoteUrlAutoChecked` (one-time auto-check sentinel). Derives `gitlabBaseUrl`. |
| `CoverageApiSettingsConfigurable.kt` | Settings UI under **Settings → Tools → GitLab Coverage**. Contains an **Enable coverage plugin** checkbox at the top that maps to `CoverageApiSettings.enabled`. |
| `CoverageMcpSettings.kt` | Project-level `PersistentStateComponent` (`coverageMcpSettings.xml`): `mcpPort` (default 17178), `mcpEnabled` (default true). |
| `CoverageMcpSettingsConfigurable.kt` | Settings UI under **Settings → Tools → GitLab Coverage → MCP Server**. Enable checkbox, port field, live status label, read-only `mcp.json` snippet text area (updates as port changes), and "Copy to Clipboard" button. |

### MCP server (LLM integration)
| File | Purpose |
|------|---------|
| `McpHandler.kt` | Pure JSON-RPC 2.0 dispatcher — no HTTP dependency. Handles `initialize` (returns capabilities + protocol version `2025-03-26`), `notifications/initialized` (no-op), `tools/list` (single tool descriptor), `tools/call` (dispatches `get_coverage_for_file`), and `ping`. Takes a JSON string, returns a JSON string. Constructor accepts `Project?` (nullable for unit tests). Uses `CoveragePathResolver` for path lookup and `CoverageDataService` for data access — same resolution logic as `CoverageHighlighter`. Tool output is human-readable text: header with commit hash + covered/uncovered counts, then each coverable line with status and test names. |
| `McpServer.kt` | Project-level `Disposable` service wrapping JDK's `com.sun.net.httpserver.HttpServer`. Binds to `127.0.0.1:<mcpPort>` (localhost only, no auth). Exposes a single `POST /mcp` endpoint accepting `Content-Type: application/json` (Streamable HTTP transport). Lifecycle: auto-starts on project open if `mcpEnabled`, stops on project close / dispose. `restart(port, enabled)` called by the settings configurable on apply. Uses a 2-thread daemon pool. `BindException` is caught and logged without crashing. |

### GitLab integration
| File | Purpose |
|------|---------|
| `GitLabApiClient.kt` | `java.net.http.HttpClient` wrapper. Uses `PRIVATE-TOKEN` header. Endpoints: list pipelines, list pipeline jobs, download job artifacts zip, merge-base, list projects (for settings picker). |
| `GitLabModels.kt` | `@Serializable` data classes for GitLab REST responses. |
| `CoverageResolver.kt` | Picks the best pipeline for the current HEAD. Three-tier merge-base resolution: (1) local `git merge-base`, (2) GitLab API merge-base with HEAD sha, (3) GitLab API with upstream ref. Matches pipeline commits against merge-base, commits on the coverage branch after merge-base, and local ancestors. Falls back to the latest pipeline (flagged via `ResolvedPipeline.fallback`). |

### Loading pipeline
| File | Purpose |
|------|---------|
| `CoverageLoadService.kt` | Project service orchestrating the whole flow. `loadOfflineFirst()` walks recent HEAD commits looking for a cached `.cov4` hit; if none match, falls back to the most recently cached artifact (stale, with a WARNING notification). Either path marks `isStale = true`, calls `CoverageCacheService.updateLastUsed()`, applies highlights immediately, then refreshes from GitLab in the background. Deduplication: if the same commit hash is already loaded, skips re-applying highlights and the notification (only triggers the GitLab refresh). `loadFromGitLab()` runs resolver → checks cache → `downloadArtifacts()` → writes `.cov4` → **reopens it as a `Cov4Reader` and drops the merged map** so steady-state memory stays bounded. All three load paths (cache hit, offline-first, fresh download) converge on the same reader-backed state. `loadFromCache(commitHash)` loads an explicitly user-selected cached artifact: opens the `Cov4Reader`, calls `updateLastUsed()`, sets coverage context (`stale=false`), applies highlights on the EDT. All heavy work runs under a `Task.Backgroundable` progress indicator. `validateSettings()` returns early (skips all loads silently) when `CoverageApiSettings.enabled` is false. `performRemoteUrlAutoCheck()` runs once on first startup: reads `git remote get-url origin` and auto-disables the plugin if the remote does not match `git@gitlab.raketa.online:raketa/raketa.git`; sets `remoteUrlAutoChecked = true` afterwards so it never fires again. |
| `LoadCoverageAction.kt` | **Load Coverage from GitLab** (available via Find Action). Validates settings, then delegates to `CoverageLoadService`. Implements `DumbAware` so it works during indexing. Not in any menu — the Artifacts panel is the primary UI. |
| `LoadLocalCoverageAction.kt` | **Load Coverage from File** (available via Find Action). Loads a local `.covt` (or `.covt.gz`) directly, bypassing GitLab. Implements `DumbAware`. Not in any menu — the Artifacts panel toolbar provides this. |
| `ClearCoverageAction.kt` | **Clear Coverage Data** (available via Find Action). Clears highlighters + in-memory data. Implements `DumbAware`. Not in any menu — the Artifacts panel toolbar provides this. |
| `CoverageHeadTracker.kt` | `GitRepositoryChangeListener` (registered via `git-integration.xml`) — monitors `repository.currentRevision` and auto-triggers `loadOfflineFirst()` whenever HEAD changes (branch switch, pull, commit, rebase, reset, etc.). Debounces rapid changes with a 2-second `Alarm`. Replaces the old `BranchChangeListener` which only fired on branch switch. |
| `CoverageStartupActivity.kt` | `ProjectActivity` — calls `CoverageLoadService.performRemoteUrlAutoCheck()` first (one-time git-remote validation), then auto-triggers `loadOfflineFirst()` once after the IDE has fully started (silent on errors; skips if settings are not configured or plugin is disabled). Implements `DumbAware` so it runs immediately without waiting for indexing. |

### Parsing & on-disk format
| File | Purpose |
|------|---------|
| `BinaryCoverageParser.kt` | Parses the **COVT** binary format produced by the PHP extension (magic `COVT`, `FILE`…`ENDF` markers, little-endian). Also unzips CI artifact zips and finds `.covt`/`.covt.gz` entries, merging their contents. Output shape: `Map<filePath, Map<lineNumber, List<testName>>>`. |
| `Cov4Writer.kt` | Writes a random-access **COV4** file (see `coverage_storage_format_v4.md`): header + interned test-name table + per-file index + per-file chunks with line→test-set mappings. |
| `Cov4Reader.kt` | Reads COV4 on demand: parses the index eagerly, then lazily decodes a single file's coverage when requested. Implements `Closeable`. Used by `CoverageDataService` as a reader-backed fallback so the whole pipeline cache never needs to sit in memory. |
| `CoverageCacheService.kt` | Disk cache at `~/.cache/coverage-plugin/<gitlabProjectId>/`. Key is **commit hash** (same commit → same coverage, independent of pipeline ID). Stores `<commit>.cov4` files + `cache-index.json` metadata. Provides `get()` (returns a `Cov4Reader`), `writeCov4()`, `updateLastUsed(commitHash)` (bumps `lastUsedMs` in the index entry), `findCachedCommit(candidates)`, `deleteArtifact(commitHash)` (removes `.cov4` file and index entry), and `cleanup(maxAgeDays = 7)`. Cleanup is self-healing: it retries on delete failure (entry stays in the index), drops index entries whose `.cov4` file is missing, and deletes orphan `.cov4` files with no index entry. The index entry tracks `lastUsedMs` (updated on every activation) which is surfaced in the Artifacts panel. |

### In-memory model
| File | Purpose |
|------|---------|
| `CoverageDataService.kt` | Project service holding the current coverage. Dual-mode: **in-memory** `Map<path, Map<line, List<test>>>` (freshly downloaded or loaded from local `.covt`) OR **reader-backed** via a `Cov4Reader` (from disk cache). Tracks `coverageCommitHash`, `gitRoot`, `isStale`. `setCoverageContext()` prunes `LineMappingService`'s old-content cache to the active commit; `clear()` wipes it entirely. |
| `LineMappingService.kt` | Bridges stale coverage and drifted source. Fetches the file content at `coverageCommitHash` via `git show`, caches it, then uses IntelliJ's `ComparisonManager` through `CoverageLineMapper` to translate old line numbers to current document line numbers every time highlights are re-applied. The content cache is keyed by `(commitHash, relativePath)` and backed by an access-order LRU capped at `MAX_CACHED_FILES = 500`, so entries from previous coverage commits become dead but harmless and the cache size is bounded. `pruneToCommit()` drops entries from non-active commits; `clear()` resets the whole cache. |
| `CoverageLineMapper.kt` | Pure functions: diff old vs current content, build old→new line map, remap a coverage map. |

### Rendering
| File | Purpose |
|------|---------|
| `CoverageHighlighter.kt` | Applies line-background highlighters using `CodeInsightColors.LINE_FULL_COVERAGE` / `LINE_NONE_COVERAGE` and attaches a `CoverageGutterRenderer`. Runs every mapped line through `LineMappingService` first; falls back to raw path matching (absolute / project-relative / leading-slash / suffix match) via `findCoverageForFile`. |
| `CoverageGutterRenderer.kt` | `LineMarkerRenderer` drawing the gutter strip + click popup listing the covering tests. Uses `BehatTestRunner` to re-run a selected test. |
| `CoverageIcons.kt` | Icon constants (green/red). |
| `CoverageEditorListener.kt` | `EditorFactoryListener` — applies highlights on editor open, installs a debounced `DocumentListener` (300ms) that re-applies highlights after edits so the line mapping refreshes. |
| `HideCoverageGutterAction.kt` / `ShowCoverageGutterAction.kt` | Editor gutter popup actions to toggle coverage display **project-wide**. They flip `CoverageGutterVisibilityService` and either `clearAllEditors` or `applyToOpenEditors`. The actions are mutually exclusive in the popup (only the relevant one is visible) and require coverage data to exist. All action classes implement `DumbAware` so they work during indexing. |
| `CoverageGutterVisibilityService.kt` | Project-level `PersistentStateComponent` (`coverageGutterVisibility.xml`) holding a single `visible: Boolean` flag. Persists across IDE restarts. `CoverageHighlighter.applyToEditor` early-returns (and clears) when `visible == false`, so newly opened editors and document-change re-highlights also respect the toggle. |
| `CoverageUserSelectionService.kt` | Project-level `PersistentStateComponent` (`coverageUserSelection.xml`) holding `pinnedCommitHash: String?`. When the user explicitly loads an artifact from the Artifacts panel, `loadFromCache` sets this pin, and `loadOfflineFirst` restores exactly that artifact on the next IDE startup (ignoring the HEAD-based walk). When the user clicks **Fetch Coverage**, `loadFromGitLab` clears the pin so auto-resolution takes over again. If the pinned artifact is missing from the cache (expired/deleted), the pin is cleared automatically and the normal HEAD-walk proceeds. |

### Tests tool window
| File | Purpose |
|------|---------|
| `CoverageTestsToolWindowFactory.kt` | Registers the **Coverage Tests** bottom tool window with three tabs: **Tests** (`CoverageTestsPanel`), **Artifacts** (`CoverageArtifactsPanel`), and **Log** (`CoverageLogPanel`). Tab order: Tests → Artifacts → Log. All contents are non-closable. |
| `CoverageTestNodeData.kt` | Sealed class hierarchy for test-tree node payloads: `Dir(displayName, count)` — intermediate directory/namespace node with recursive test count; `BehatGroup(featurePath, displayName)` — feature file node; `BehatScenario(label, originalTestName)` — leaf scenario; `PhpUnitGroup(className, displayName)` — class node; `PhpUnitMethod(methodName, fullTestName)` — leaf method. Also declares `FileNodeData` sealed class used by `AffectedFilesPane`. |
| `CoverageTestsPanel.kt` | Dual-mode UI. **Normal mode** (per-line): lists tests covering the clicked line, groups by feature file / PHPUnit class. Supports **flat/tree toggle** (`isTestsTreeView`): flat = group by feature file path label; tree = parse the path into a compact directory hierarchy (Behat) or namespace hierarchy (PHPUnit `\`-separated), identical compaction logic to `AffectedFilesPane`. In tree mode each `Dir`, `BehatGroup`, and `PhpUnitGroup` node shows the test count in gray parentheses (e.g. `features/checkout (12)`); counts on `Dir` nodes are the recursive total under that subtree. Toggle button in the toolbar rebuilds the tree immediately. **Affected mode** (per-change): activated by Find HEAD / Find Working Tree toolbar buttons; shows a `OnePixelSplitter` with a `CheckboxTree` of affected files on the left and the test tree on the right. Files pane supports its own tree/flat toggle, expand/collapse all, check/uncheck all, per-node Δ (unique contribution). Both modes share the same test tree and the same flat/tree toggle. Toolbar: Find HEAD, Find Working Tree, Refresh, Tree View toggle, Run All, Run All With Debug, Remove Selected, Back. F5 = Refresh. Switches back to normal mode via Back. Run All bundles every scenario into a **single** Behat launch via `BehatTestRunner.runMultiplePathsWithCallback` (no more sequential per-test launches). `buildPathsByFile` groups tree test names (`feature:line`) into the `Map<String, List<Int>>` shape the runner expects; whole-file entries override per-line entries for the same file. |
| `CoverageArtifactsPanel.kt` | Artifacts tab — **primary UI for managing coverage**. Lists all locally cached coverage artifacts (from `CoverageCacheService.listArtifacts()`) in a table with columns: Скачан, Коммит, Pipeline, Размер, Файлы, Покрытие %, Последнее использование. The currently active artifact (commit hash matching `CoverageDataService.coverageCommitHash`) is highlighted with **bold font** across all columns. Toolbar has four buttons: **Fetch Coverage** (triggers `CoverageLoadService.loadFromGitLab()`), **Load Selected** (loads the selected cached artifact as active coverage via `CoverageLoadService.loadFromCache()`; disabled when no row is selected), **Load from File** (opens file chooser for local `.covt`/`.covt.gz`), and **Delete** (trash icon — removes the selected artifact from disk and cache index; disabled when no row is selected; shows confirmation dialog; if the deleted artifact is the currently active one, also clears gutter highlights). Right-clicking any row shows a unified context menu: **Load This Coverage** (activates that artifact), **Copy Hash** (copies the full commit hash to clipboard), **Open in Browser** (opens the GitLab pipeline URL; only shown when `gitlabProjectName` is configured). When no artifacts are cached, shows an **empty state** panel with a "Fetch Coverage" button and a "load from a local file…" link. `refreshData()` switches between empty state and table via `CardLayout` and calls `table.repaint()` to update bold highlighting. |
| `CoverageLogPanel.kt` | Log tab. Wraps an IntelliJ `ConsoleView` (scrollback / search / copy for free) and subscribes to `CoverageLogService` via the atomic `subscribe(parent, listener)` so the backfill and live stream are gap-free and dupe-free. Maps `CoverageLogService.Level` → `ConsoleViewContentType.LOG_*_OUTPUT` for level-colored output. Toolbar exposes Clear (wipes both buffer and console) and Scroll-to-End. |

### Affected-tests analysis
| File | Purpose |
|------|---------|
| `ChangedLinesAnalyzer.kt` | Runs `git diff -U0 -M <coverageCommit> [HEAD]` and parses the unified diff into `List<FileChange>`. Each `FileChange` carries the old-side line numbers affected by the hunk (those are the coverage-map keys to look up). Supports two modes: `COMMITTED` (diff to HEAD) and `WORKING_TREE` (diff to working tree including unstaged changes). |
| `CoveragePathResolver.kt` | Shared path-lookup helper used by both `CoverageHighlighter` and `AffectedTestsService`. Tries each candidate path as-is, with a leading `/`, and finally via suffix match across all stored paths. Centralises the four-way path normalization so it stays consistent. |
| `AffectedTestsService.kt` | Project service. Calls `ChangedLinesAnalyzer.analyze()`, looks up each affected old line in `CoverageDataService` via `CoveragePathResolver`, and returns `AffectedTests(tests, perFile, newFiles, deletedFiles, filesWithoutCoverage, mode)`. |
| `AffectedTestsModel.kt` | Pure (Swing-free) data model for the affected-files view. Holds `perFile`, a reverse index `test→files`, mutable `checkedFiles` and `removedTests`. Exposes `displayedTests` (union over checked files minus removed) and `deltaForFiles(subtreeFiles)` (number of currently-displayed tests that would disappear if the subtree were unchecked). All recomputation is O(tests × depth); unit-testable without Swing. |
| `ToggleAffectedFilesViewAction.kt` | `ToggleAction` for the files-pane toolbar: switches between tree view and flat list. Stateless — reads/writes state via lambdas injected from `CoverageTestsPanel`. |

### Behat runner
| File | Purpose |
|------|---------|
| `BehatTestRunner.kt` | Locates Behat run configuration template and executes scenarios from the gutter popup / tool window. Single-scenario / single-file APIs (`runScenario`, `runFeatureFile`, plus `*WithCallback` variants) build a run via `ExecutionEnvironmentBuilder.build(callback)` and attach a `ProcessAdapter` so callers get the process exit code on `processTerminated`. **Bundled launch**: `runMultiplePaths` / `runMultiplePathsWithCallback` accept a `Map<String, List<Int>>` (feature file → 1-based scenario lines, empty list = whole file) and start a **single** Behat process. Implementation: forces `Scope.ConfigurationFile` so the handler doesn't append a positional path argument, then injects `--paths=foo.feature:10,20 --paths=bar.feature` via `runnerSettings.testRunnerOptions`. `isUseAlternativeConfigurationFile` and `configurationFilePath` are intentionally **not** touched so they inherit from the project's Behat run-configuration template — both default `behat.yml` and a user-set custom config file location work. The `--paths` option is a custom Behat extension (not stock Behat); paths with whitespace are double-quoted to survive `ParametersList` tokenization. |

### Errors, logging & telemetry
| File | Purpose |
|------|---------|
| `CoverageApiException.kt` | Typed exception with `CoverageErrorKind` (`NETWORK`, `GITLAB_API`, `PARSE`, `ARTIFACT_PARSE`, `GIT`, `NO_DATA`, `PROJECT_SETUP`) and a detail map surfaced in error dialogs. |
| `CoverageErrorReporter.kt` | IDE `ErrorReportSubmitter` for "Submit" button on plugin exceptions. |
| `CoverageLog.kt` | Plugin-wide logger wrapper. `CoverageLog.get(Foo::class.java)` returns an object whose `info/warn/error/debug` methods write to **both** the platform `Logger` (so messages still land in `idea.log`) **and** `CoverageLogService` (so they appear in the Log tab). All plugin code uses this — never `Logger.getInstance` directly. |
| `CoverageLogService.kt` | Application-level service holding a bounded ring buffer (`MAX_ENTRIES = 2000`) of `LogEntry(timestampMs, level, loggerName, message, throwable?)`. Thread-safe. `subscribe(Disposable, Listener)` atomically returns the backfill and registers a listener that fires for every subsequent append; `addListener` / `removeListener` are Disposable-free helpers used by unit tests. Listener exceptions are swallowed so a bad consumer can't break logging. |

## End-to-end Flow (GitLab path)

1. IDE starts → `CoverageStartupActivity.execute()`:
   - Calls `CoverageLoadService.performRemoteUrlAutoCheck()` (runs only once per IDE installation, guarded by `remoteUrlAutoChecked`). If the project's `origin` remote is not `git@gitlab.raketa.online:raketa/raketa.git`, sets `enabled = false` silently and marks the check as done.
   - Calls `validateSettings()`. If `enabled = false` or settings are missing, returns — no further action.
2. User clicks **Fetch Coverage** in the Artifacts panel toolbar (or uses Find Action → "Load Coverage from GitLab"), or HEAD changes and `CoverageHeadTracker` fires.
3. `CoverageLoadService.loadOfflineFirst()`:
   - Walks last 200 HEAD commits, asks `CoverageCacheService` if any are cached on disk.
   - If yes → open `Cov4Reader`, mark `isStale = true`, apply highlights, notify "Showing cached coverage".
   - If no commit in history matches → fall back to the most recently cached artifact (any commit), mark `isStale = true`, notify with WARNING "may not match current code".
   - Deduplication: if the candidate commit is already loaded and `hasData()`, skips the notification and re-highlight — only triggers the background GitLab refresh.
4. `loadFromGitLab(showErrors = false)` runs in the background under a progress task:
   - `CoverageCacheService.cleanup()` prunes entries older than 7 days.
   - `CoverageResolver.resolve()` → `ResolvedPipeline(commitHash, pipelineId, gitRoot, fallback?)`.
   - If `cache.get(commitHash) != null` → reuse cached COV4 reader, skip download (`applyCoverageFromReader`).
   - Otherwise `downloadArtifacts()`:
     - List pipeline jobs, filter `status == "success" && name contains "behat"` with artifacts.
     - Download each job artifact zip, extract `.covt`/`.covt.gz` via `BinaryCoverageParser.parseZipArtifact`.
     - Merge per-file line→test maps across jobs (dedup test names).
   - `Cov4Writer.write()` stores the merged result as `<commit>.cov4`; index updated.
   - `cache.get(commitHash)` reopens the file we just wrote; the merged in-memory map is dropped and `applyCoverageFromReader()` installs the reader. If the reopen fails (I/O error, disk full), falls back to `applyCoverage()` with the in-memory map.
   - The shared `notifyRefreshedFromStale()` helper fires a "Coverage updated" notification if the previous data was stale and the commit hash changed.
5. `CoverageHighlighter.applyToOpenEditors()` runs on the EDT. For each editor it resolves the file's coverage through `LineMappingService` (which diffs cached old content vs live document) and installs per-line gutter renderers.

## Local `.covt` Path

`LoadLocalCoverageAction` (also accessible via the **Load from File** button in the Artifacts panel) lets the user pick a `.covt` file (or `.covt.gz`), parses it with `BinaryCoverageParser.parseCovtBytes`, feeds the result into `CoverageDataService.setCoverageAll()`. Useful for offline development and debugging without GitLab.

## MCP Server (LLM integration)

An embedded MCP (Model Context Protocol) server lets Claude CLI, Copilot CLI, and other MCP-compatible tools query per-line coverage data from the running IDE.

- **Transport**: Streamable HTTP (2025-03-26 spec) — `POST /mcp`, `Content-Type: application/json`.
- **Binding**: `127.0.0.1` only (localhost), default port `17178`. No authentication required.
- **Zero new dependencies**: hand-rolled JSON-RPC 2.0 using `kotlinx.serialization` + JDK `com.sun.net.httpserver`.
- **One server per project**: starts automatically on project open if enabled; stops on project close.

### Tool: `get_coverage_for_file`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file_path` | string | yes | Absolute or project-relative path to the PHP file |

Returns human-readable text listing each coverable line with its status (covered/uncovered) and the names of tests that execute it. Header shows the coverage commit hash and covered/uncovered counts.

### Configuration

Add to `mcp.json`, `.claude.json`, or Claude Desktop config:

```json
{
  "mcpServers": {
    "coverage": {
      "url": "http://localhost:17178/mcp"
    }
  }
}
```

Settings are at **Settings → Tools → GitLab Coverage → MCP Server** — enable/disable checkbox, port, live status, and a copyable config snippet.

## Build & Run

```bash
./gradlew compileKotlin   # compile
./gradlew test            # unit tests (BinaryCoverageParserTest, Cov4WriterReaderTest, GitLabModelsTest, AffectedTestsModelTest, ChangedLinesAnalyzerTest, CoverageLogServiceTest, McpHandlerTest)
./gradlew buildPlugin     # distributable zip
./gradlew runIde          # sandbox IDE with the plugin
```

## Conventions

- **Logging**: every plugin class declares `private val log = CoverageLog.get(Foo::class.java)` — never `Logger.getInstance(...)` directly. This ensures messages reach both `idea.log` and the in-IDE Log tab.
- **HTTP**: Java's built-in `java.net.http.HttpClient`, no external HTTP deps.
- **Git**: command-line `git` via `ProcessBuilder` (see `CoverageResolver.runGitCommand`); no JGit.
- **Serialization**: kotlinx-serialization for GitLab JSON models and the cache index.
- **Threading**: all network / parsing work runs under `Task.Backgroundable`; UI mutations via `ApplicationManager.getApplication().invokeLater`.
- **Line numbers**: coverage data is **1-based** throughout (COVT, COV4, `LineMappingService`, highlighter loops offset by `+1`).
- **Paths**: coverage files are stored with project-relative paths. Path lookup always goes through `CoveragePathResolver.resolve()` which tries absolute → relative → `/relative` → suffix-match. `LineMappingService.toGitRelativePath` rebases project-relative paths to the git root for `git show`.
- **Cache key**: commit hash, not pipeline ID.
- **Error surfacing**: throw `CoverageApiException` with a `CoverageErrorKind`; `CoverageLoadService.errorTitle` maps to user-visible dialog titles. Silent-mode callers (auto-trigger) only log.
- **Notifications**: use the `Coverage Notifications` group registered in `plugin.xml` (balloon type).
- **Enabled flag**: `CoverageApiSettings.enabled` is the master switch. `validateSettings()` returns early when it is false, so all load paths (startup, HEAD tracker, manual actions) are blocked. The user can re-enable via **Settings → Tools → GitLab Coverage**.
- **Remote URL guard**: `CoverageLoadService.REQUIRED_REMOTE_URL = "git@gitlab.raketa.online:raketa/raketa.git"`. On first startup `performRemoteUrlAutoCheck()` auto-disables the plugin for any repo whose `origin` remote doesn't match. The check is one-shot (`remoteUrlAutoChecked` flag) so the user's manual re-enable is never overwritten.
