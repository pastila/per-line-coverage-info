# Per-Line Coverage Info — IntelliJ Plugin

## Overview

An IntelliJ/PhpStorm plugin that displays per-line PHP code coverage inline in the editor. Coverage is collected by CI (Behat with a custom PHP extension that emits `.covt` files), downloaded from GitLab pipeline artifacts, cached locally in a random-access binary format, and rendered via gutter icons + line backgrounds. Each covered line exposes the list of tests that execute it, and individual tests can be re-run from the gutter popup.

- **Plugin ID**: `com.github.yakov255.perlinecoverageinfo`
- **Platform**: PhpStorm 2024.2.5+, depends on `com.jetbrains.php`, `gherkin`, optional `com.jetbrains.php.behat`
- **Language**: Kotlin, JVM 21
- **Build**: Gradle with IntelliJ Platform Gradle Plugin
- **Serialization**: kotlinx-serialization-json

## Project Layout

Plugin source: `src/main/kotlin/com/github/yakov255/perlinecoverageinfo/`
Tests: `src/test/kotlin/com/github/yakov255/perlinecoverageinfo/`
Plugin manifest: `src/main/resources/META-INF/plugin.xml` (+ `behat-integration.xml` for optional Behat deps)
Related artifacts in repo root: `php-behat-coverage-extension/` (the PHP extension that produces `.covt`), `behat-decompiled/`, `php-sample-code/`, `coverage_storage_format_v4.md` (on-disk COV4 spec).

## Source Files

### Settings & configuration
| File | Purpose |
|------|---------|
| `CoverageApiSettings.kt` | Persistent application-level settings: `gitlabDomain`, `bearerToken` (PRIVATE-TOKEN), `gitlabProjectId`, `gitlabProjectName`, `coverageBranch` (default `behat-run-necessary-tests`). Derives `gitlabBaseUrl`. |
| `CoverageApiSettingsConfigurable.kt` | Settings UI under **Settings → Tools → GitLab Coverage**. |

### GitLab integration
| File | Purpose |
|------|---------|
| `GitLabApiClient.kt` | `java.net.http.HttpClient` wrapper. Uses `PRIVATE-TOKEN` header. Endpoints: list pipelines, list pipeline jobs, download job artifacts zip, merge-base, list projects (for settings picker). |
| `GitLabModels.kt` | `@Serializable` data classes for GitLab REST responses. |
| `CoverageResolver.kt` | Picks the best pipeline for the current HEAD. Three-tier merge-base resolution: (1) local `git merge-base`, (2) GitLab API merge-base with HEAD sha, (3) GitLab API with upstream ref. Matches pipeline commits against merge-base, commits on the coverage branch after merge-base, and local ancestors. Falls back to the latest pipeline (flagged via `ResolvedPipeline.fallback`). |

### Loading pipeline
| File | Purpose |
|------|---------|
| `CoverageLoadService.kt` | Project service orchestrating the whole flow. `loadOfflineFirst()` walks recent HEAD commits, opens the first cached `.cov4` (marks stale), shows highlights immediately, then refreshes from GitLab in the background. `loadFromGitLab()` runs resolver → checks cache → `downloadArtifacts()` → writes `.cov4` → **reopens it as a `Cov4Reader` and drops the merged map** so steady-state memory stays bounded. All three load paths (cache hit, offline-first, fresh download) converge on the same reader-backed state. All heavy work runs under a `Task.Backgroundable` progress indicator. |
| `LoadCoverageAction.kt` | Tools menu: **Load Coverage from GitLab**. Validates settings, then delegates to `CoverageLoadService`. |
| `LoadLocalCoverageAction.kt` | Tools menu: **Load Coverage from File**. Loads a local `.covt` (or `.covt.gz`) directly, bypassing GitLab. |
| `ClearCoverageAction.kt` | Tools menu: clears highlighters + in-memory data. |
| `CoverageBranchListener.kt` | `BranchChangeListener` — auto-triggers `loadOfflineFirst()` after a VCS branch change (silent on errors). |
| `CoverageStartupActivity.kt` | `ProjectActivity` — auto-triggers `loadOfflineFirst()` once after the IDE has fully started (silent on errors; skips if settings are not configured). |

### Parsing & on-disk format
| File | Purpose |
|------|---------|
| `BinaryCoverageParser.kt` | Parses the **COVT** binary format produced by the PHP extension (magic `COVT`, `FILE`…`ENDF` markers, little-endian). Also unzips CI artifact zips and finds `.covt`/`.covt.gz` entries, merging their contents. Output shape: `Map<filePath, Map<lineNumber, List<testName>>>`. |
| `Cov4Writer.kt` | Writes a random-access **COV4** file (see `coverage_storage_format_v4.md`): header + interned test-name table + per-file index + per-file chunks with line→test-set mappings. |
| `Cov4Reader.kt` | Reads COV4 on demand: parses the index eagerly, then lazily decodes a single file's coverage when requested. Implements `Closeable`. Used by `CoverageDataService` as a reader-backed fallback so the whole pipeline cache never needs to sit in memory. |
| `CoverageCacheService.kt` | Disk cache at `~/.cache/coverage-plugin/<gitlabProjectId>/`. Key is **commit hash** (same commit → same coverage, independent of pipeline ID). Stores `<commit>.cov4` files + `cache-index.json` metadata. Provides `get()` (returns a `Cov4Reader`), `writeCov4()`, `findCachedCommit(candidates)`, and `cleanup(maxAgeDays = 7)`. Cleanup is self-healing: it retries on delete failure (entry stays in the index), drops index entries whose `.cov4` file is missing, and deletes orphan `.cov4` files with no index entry. |

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
| `HideCoverageGutterAction.kt` / `ShowCoverageGutterAction.kt` | Editor gutter popup actions to toggle coverage display **project-wide**. They flip `CoverageGutterVisibilityService` and either `clearAllEditors` or `applyToOpenEditors`. The actions are mutually exclusive in the popup (only the relevant one is visible) and require coverage data to exist. |
| `CoverageGutterVisibilityService.kt` | Project-level `PersistentStateComponent` (`coverageGutterVisibility.xml`) holding a single `visible: Boolean` flag. Persists across IDE restarts. `CoverageHighlighter.applyToEditor` early-returns (and clears) when `visible == false`, so newly opened editors and document-change re-highlights also respect the toggle. |

### Tests tool window
| File | Purpose |
|------|---------|
| `CoverageTestsToolWindowFactory.kt` | Registers the **Coverage Tests** bottom tool window with two tabs: **Tests** (`CoverageTestsPanel`) and **Log** (`CoverageLogPanel`). Both contents are non-closable. |
| `CoverageTestsPanel.kt` | Dual-mode UI. **Normal mode** (per-line): lists tests covering the clicked line, groups by feature file / PHPUnit class, Run All / Run All With Debug / Run Selected actions. **Affected mode** (per-change): activated by Find HEAD / Find Working Tree toolbar buttons; shows a `OnePixelSplitter` with a `CheckboxTree` of affected files on the left and the test tree on the right. Files pane supports tree/flat toggle, expand/collapse all, check/uncheck all, per-node Δ (unique contribution). Toolbar: Find HEAD, Find Working Tree, Refresh, Run All, Run All With Debug, Run Selected, Remove Selected, Back. F5 = Refresh. Switches back to normal mode via Back. Run All / Run Selected bundle every chosen scenario into a **single** Behat launch via `BehatTestRunner.runMultiplePathsWithCallback` (no more sequential per-test launches). `buildPathsByFile` groups tree test names (`feature:line`) into the `Map<String, List<Int>>` shape the runner expects; whole-file entries override per-line entries for the same file. `selectedBehatTests` walks the tree selection (recursing into group nodes) for the Run Selected action. |
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

1. User invokes **Tools → Load Coverage from GitLab** (or a branch change fires `CoverageBranchListener`, or the IDE starts and `CoverageStartupActivity` fires).
2. `CoverageLoadService.loadOfflineFirst()`:
   - Walks last 200 HEAD commits, asks `CoverageCacheService` if any are cached on disk.
   - If yes → open `Cov4Reader`, mark `isStale = true`, apply highlights, notify "Showing cached coverage".
3. `loadFromGitLab(showErrors = false)` runs in the background under a progress task:
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
4. `CoverageHighlighter.applyToOpenEditors()` runs on the EDT. For each editor it resolves the file's coverage through `LineMappingService` (which diffs cached old content vs live document) and installs per-line gutter renderers.

## Local `.covt` Path

`LoadLocalCoverageAction` lets the user pick a `.covt` file (or `.covt.gz`), parses it with `BinaryCoverageParser.parseCovtBytes`, feeds the result into `CoverageDataService.setCoverageAll()`. Useful for offline development and debugging without GitLab.

## Build & Run

```bash
./gradlew compileKotlin   # compile
./gradlew test            # unit tests (BinaryCoverageParserTest, Cov4WriterReaderTest, GitLabModelsTest, AffectedTestsModelTest, ChangedLinesAnalyzerTest, CoverageLogServiceTest)
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
