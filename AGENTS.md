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
| `CoverageLoadService.kt` | Project service orchestrating the whole flow. `loadOfflineFirst()` walks recent HEAD commits, opens the first cached `.cov4` (marks stale), shows highlights immediately, then refreshes from GitLab in the background. `loadFromGitLab()` runs resolver → checks cache → `downloadArtifacts()` → writes `.cov4` → applies coverage. All heavy work runs under a `Task.Backgroundable` progress indicator. |
| `LoadCoverageAction.kt` | Tools menu: **Load Coverage from GitLab**. Validates settings, then delegates to `CoverageLoadService`. |
| `LoadLocalCoverageAction.kt` | Tools menu: **Load Coverage from File**. Loads a local `.covt` (or `.covt.gz`) directly, bypassing GitLab. |
| `ClearCoverageAction.kt` | Tools menu: clears highlighters + in-memory data. |
| `CoverageBranchListener.kt` | `BranchChangeListener` — auto-triggers `loadOfflineFirst()` after a VCS branch change (silent on errors). |

### Parsing & on-disk format
| File | Purpose |
|------|---------|
| `BinaryCoverageParser.kt` | Parses the **COVT** binary format produced by the PHP extension (magic `COVT`, `FILE`…`ENDF` markers, little-endian). Also unzips CI artifact zips and finds `.covt`/`.covt.gz` entries, merging their contents. Output shape: `Map<filePath, Map<lineNumber, List<testName>>>`. |
| `Cov4Writer.kt` | Writes a random-access **COV4** file (see `coverage_storage_format_v4.md`): header + interned test-name table + per-file index + per-file chunks with line→test-set mappings. |
| `Cov4Reader.kt` | Reads COV4 on demand: parses the index eagerly, then lazily decodes a single file's coverage when requested. Implements `Closeable`. Used by `CoverageDataService` as a reader-backed fallback so the whole pipeline cache never needs to sit in memory. |
| `CoverageCacheService.kt` | Disk cache at `~/.cache/coverage-plugin/<gitlabProjectId>/`. Key is **commit hash** (same commit → same coverage, independent of pipeline ID). Stores `<commit>.cov4` files + `cache-index.json` metadata. Provides `get()` (returns a `Cov4Reader`), `writeCov4()`, `cleanup(maxAgeDays = 7)`, `findCachedCommit(candidates)` for offline-first lookup. |

### In-memory model
| File | Purpose |
|------|---------|
| `CoverageDataService.kt` | Project service holding the current coverage. Dual-mode: **in-memory** `Map<path, Map<line, List<test>>>` (freshly downloaded or loaded from local `.covt`) OR **reader-backed** via a `Cov4Reader` (from disk cache). Tracks `coverageCommitHash`, `gitRoot`, `isStale`. |
| `LineMappingService.kt` | Bridges stale coverage and drifted source. Fetches the file content at `coverageCommitHash` via `git show` (cached per file), then uses IntelliJ's `ComparisonManager` through `CoverageLineMapper` to translate old line numbers to current document line numbers every time highlights are re-applied. |
| `CoverageLineMapper.kt` | Pure functions: diff old vs current content, build old→new line map, remap a coverage map. |

### Rendering
| File | Purpose |
|------|---------|
| `CoverageHighlighter.kt` | Applies line-background highlighters using `CodeInsightColors.LINE_FULL_COVERAGE` / `LINE_NONE_COVERAGE` and attaches a `CoverageGutterRenderer`. Runs every mapped line through `LineMappingService` first; falls back to raw path matching (absolute / project-relative / leading-slash / suffix match) via `findCoverageForFile`. |
| `CoverageGutterRenderer.kt` | `LineMarkerRenderer` drawing the gutter strip + click popup listing the covering tests. Uses `BehatTestRunner` to re-run a selected test. |
| `CoverageIcons.kt` | Icon constants (green/red). |
| `CoverageEditorListener.kt` | `EditorFactoryListener` — applies highlights on editor open, installs a debounced `DocumentListener` (300ms) that re-applies highlights after edits so the line mapping refreshes. |
| `HideCoverageGutterAction.kt` / `ShowCoverageGutterAction.kt` | Editor gutter popup actions to toggle coverage display. |

### Tests tool window
| File | Purpose |
|------|---------|
| `CoverageTestsToolWindowFactory.kt` | Registers the **Coverage Tests** bottom tool window. |
| `CoverageTestsPanel.kt` | UI listing tests covering the current file/line, with run/debug buttons hooking into `BehatTestRunner`. |

### Behat runner
| File | Purpose |
|------|---------|
| `BehatTestRunner.kt` | Locates Behat run configuration template and executes a specific scenario (`feature:line` form) from the gutter popup / tool window. |

### Errors & telemetry
| File | Purpose |
|------|---------|
| `CoverageApiException.kt` | Typed exception with `CoverageErrorKind` (`NETWORK`, `GITLAB_API`, `PARSE`, `ARTIFACT_PARSE`, `GIT`, `NO_DATA`, `PROJECT_SETUP`) and a detail map surfaced in error dialogs. |
| `CoverageErrorReporter.kt` | IDE `ErrorReportSubmitter` for "Submit" button on plugin exceptions. |

## End-to-end Flow (GitLab path)

1. User invokes **Tools → Load Coverage from GitLab** (or a branch change fires `CoverageBranchListener`).
2. `CoverageLoadService.loadOfflineFirst()`:
   - Walks last 200 HEAD commits, asks `CoverageCacheService` if any are cached on disk.
   - If yes → open `Cov4Reader`, mark `isStale = true`, apply highlights, notify "Showing cached coverage".
3. `loadFromGitLab(showErrors = false)` runs in the background under a progress task:
   - `CoverageCacheService.cleanup()` prunes entries older than 7 days.
   - `CoverageResolver.resolve()` → `ResolvedPipeline(commitHash, pipelineId, gitRoot, fallback?)`.
   - If `cache.get(commitHash) != null` → reuse cached COV4 reader, skip download.
   - Otherwise `downloadArtifacts()`:
     - List pipeline jobs, filter `status == "success" && name contains "behat"` with artifacts.
     - Download each job artifact zip, extract `.covt`/`.covt.gz` via `BinaryCoverageParser.parseZipArtifact`.
     - Merge per-file line→test maps across jobs (dedup test names).
   - `Cov4Writer.write()` stores the merged result as `<commit>.cov4`; index updated.
   - `applyCoverage()` swaps in the fresh data and, if the previous was stale, notifies "Coverage updated".
4. `CoverageHighlighter.applyToOpenEditors()` runs on the EDT. For each editor it resolves the file's coverage through `LineMappingService` (which diffs cached old content vs live document) and installs per-line gutter renderers.

## Local `.covt` Path

`LoadLocalCoverageAction` lets the user pick a `.covt` file (or `.covt.gz`), parses it with `BinaryCoverageParser.parseCovtBytes`, feeds the result into `CoverageDataService.setCoverageAll()`. Useful for offline development and debugging without GitLab.

## Build & Run

```bash
./gradlew compileKotlin   # compile
./gradlew test            # unit tests (BinaryCoverageParserTest, Cov4WriterReaderTest, GitLabModelsTest)
./gradlew buildPlugin     # distributable zip
./gradlew runIde          # sandbox IDE with the plugin
```

## Conventions

- **HTTP**: Java's built-in `java.net.http.HttpClient`, no external HTTP deps.
- **Git**: command-line `git` via `ProcessBuilder` (see `CoverageResolver.runGitCommand`); no JGit.
- **Serialization**: kotlinx-serialization for GitLab JSON models and the cache index.
- **Threading**: all network / parsing work runs under `Task.Backgroundable`; UI mutations via `ApplicationManager.getApplication().invokeLater`.
- **Line numbers**: coverage data is **1-based** throughout (COVT, COV4, `LineMappingService`, highlighter loops offset by `+1`).
- **Paths**: coverage files are stored with project-relative paths. `CoverageHighlighter.findCoverageForFile` tries absolute → relative → `/relative` → suffix-match before giving up. `LineMappingService.toGitRelativePath` rebases project-relative paths to the git root for `git show`.
- **Cache key**: commit hash, not pipeline ID.
- **Error surfacing**: throw `CoverageApiException` with a `CoverageErrorKind`; `CoverageLoadService.errorTitle` maps to user-visible dialog titles. Silent-mode callers (auto-trigger) only log.
- **Notifications**: use the `Coverage Notifications` group registered in `plugin.xml` (balloon type).
