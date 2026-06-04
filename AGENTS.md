# Per-Line Coverage Info — IntelliJ Plugin

## Overview

PhpStorm plugin that shows per-line PHP coverage inline in the editor. CI collects coverage via a custom PHP/Behat extension (`.covt` files), uploads to GitLab pipeline artifacts; the plugin downloads, caches locally as `.cov4`, and renders via gutter icons + line backgrounds. Clicking a covered line shows the tests that execute it; tests can be re-run from the popup.

- **Plugin ID**: `com.github.yakov255.perlinecoverageinfo`
- **Platform**: IntelliJ IDEA Ultimate 2025.2+, depends on `com.intellij.modules.platform`, `com.intellij.mcpServer`, `com.jetbrains.php`, `gherkin`, optional `com.jetbrains.php.behat`, optional `Git4Idea`
- **Language**: Kotlin, JVM 21 · **Build**: Gradle + IntelliJ Platform Gradle Plugin

## Project Layout

```
src/main/kotlin/com/github/yakov255/perlinecoverageinfo/   # plugin source
src/test/kotlin/com/github/yakov255/perlinecoverageinfo/   # unit tests
src/main/resources/META-INF/plugin.xml                     # manifest (+ behat-integration.xml, git-integration.xml)
php-behat-coverage-extension/                              # PHP ext that emits .covt
coverage_storage_format_v4.md                              # COV4 binary spec
```

## Source Files

### Settings
| File | Purpose |
|------|---------|
| `CoverageApiSettings.kt` | App-level settings: GitLab domain/token/project, coverage branch, `enabled` master switch, `remoteUrlAutoChecked` sentinel |
| `CoverageApiSettingsConfigurable.kt` | Settings UI — **Settings → Tools → GitLab Coverage**; triggers `loadOfflineFirst()` for all open projects on Apply/OK if plugin enabled and configured |

### MCP server
| File | Purpose |
|------|---------|
| `CoverageMcpToolset.kt` | JetBrains MCP framework toolset via `com.intellij.mcpServer`; exposes `get_coverage_for_file` and `list_files` tools |

### GitLab integration
| File | Purpose |
|------|---------|
| `GitLabApiClient.kt` | HTTP client: pipelines, jobs, artifact download, merge-base, project list |
| `GitLabModels.kt` | `@Serializable` data classes for GitLab REST responses |
| `CoverageResolver.kt` | Picks the best pipeline for current HEAD via three-tier merge-base resolution; `resolveDual()` returns `DualResolved(primary, baseline?)` for dual-coverage mode |

### Loading pipeline
| File | Purpose |
|------|---------|
| `CoverageLoadService.kt` | Central orchestrator: `loadOfflineFirst()` (cache walk → stale fallback), `loadFromGitLab()` (resolve → download → write COV4 → activate reader), `loadFromCache()` (manual selection) |
| `CoverageHeadTracker.kt` | Triggers `loadOfflineFirst()` on every HEAD change (debounced 2 s); skipped when gutter hidden |
| `CoverageStartupActivity.kt` | On project open: remote-URL auto-check, then `loadOfflineFirst()`; skipped when gutter hidden |
| `CoveragePipelinePoller.kt` | Polls remote for new pipelines and auto-refreshes coverage; stopped/blocked when gutter hidden |
| `LoadCoverageAction.kt` | Find Action: "Load Coverage from GitLab" |
| `LoadLocalCoverageAction.kt` | Find Action: "Load Coverage from File" (.covt/.covt.gz) |
| `ClearCoverageAction.kt` | Find Action: "Clear Coverage Data" |

### Parsing & on-disk format
| File | Purpose |
|------|---------|
| `BinaryCoverageParser.kt` | Parses `.covt` binary and CI artifact ZIPs → `Map<file, Map<line, List<test>>>` |
| `Cov4Writer.kt` | Writes COV4 random-access cache file (spec: `coverage_storage_format_v4.md`) |
| `Cov4Reader.kt` | Reads COV4 on demand (lazy per-file decoding); implements `Closeable` |
| `CoverageCacheService.kt` | Disk cache at `~/.cache/coverage-plugin/<projectId>/`; key = commit hash; manages index, cleanup (7 days), last-used tracking |

### In-memory model
| File | Purpose |
|------|---------|
| `CoverageDataService.kt` | Holds active coverage: primary `Cov4Reader` + optional baseline `Cov4Reader`; tracks commit hashes, git root, stale flag |
| `LineMappingService.kt` | Maps old coverage line numbers to current document lines via `git show` + `ComparisonManager` diff; supports both primary and baseline commits; LRU cache of file contents |
| `CoverageLineMapper.kt` | Pure functions: diff old/new content, build old→new line map |
| `CoverageDiff.kt` | Pure helpers: `featureOnly(primary, baseline)` (set-diff by test name) and `union(primary, baseline)` (distinct, primary first) |

### Rendering
| File | Purpose |
|------|---------|
| `CoverageHighlighter.kt` | Applies line-background highlighters and gutter renderers; classifies each line as `COVERED` (green) / `UNCOVERED` (red) / `FEATURE_ONLY` (blue) when baseline is present |
| `CoverageGutterRenderer.kt` | Gutter strip coloured by `CoverageCategory`; tooltip shows count of feature-only tests; click opens "Covering Line" panel |
| `CoverageEditorListener.kt` | Applies highlights on editor open; re-applies on document change (debounced 300 ms) |
| `CoverageGutterVisibilityService.kt` | Persists gutter visible/hidden toggle across restarts; gates all loading activity when hidden |
| `CoverageUserSelectionService.kt` | Persists pinned commit hash (user-selected artifact survives IDE restart) |
| `HideCoverageGutterAction.kt` / `ShowCoverageGutterAction.kt` | Toggle coverage gutter project-wide; hide also stops pipeline poller, show triggers `loadOfflineFirst()` |
| `CoverageIcons.kt` | Green/red icon constants |

### Tests tool window
| File | Purpose |
|------|---------|
| `CoverageTestsToolWindowFactory.kt` | Registers "Coverage Tests" bottom tool window (Covering Line / Affected by Changes / Artifacts / Log tabs) |
| `CoverageTestNodeData.kt` | Sealed node-payload hierarchy for test tree: Dir, BehatGroup, BehatScenario, PhpUnitGroup, PhpUnitMethod |
| `TestTreeView.kt` | Shared test-tree widget: tree+model+renderer, double-click navigate, popup (Run / Debug / Go to), flat-vs-tree toggle, Behat/PhpUnit hierarchy builders, Run-All-bundled |
| `CoveringLinePanel.kt` | "Covering Line" tab: per-line view — tests covering the line clicked in the editor gutter; toolbar: toggleView, Run All, Run All Debug, **Both / Master Only / New on This Branch** filter (visible only in dual-coverage mode) |
| `AffectedTestsPanel.kt` | "Affected by Changes" tab: `AffectedFilesPane` + `TestTreeView` in splitter; toolbar: Find HEAD, Find Working Tree, Refresh, toggleView, Run All, Run All Debug, Remove Selected; F5 = Refresh |
| `CoverageArtifactsPanel.kt` | Artifacts tab: table of cached COV4 files with Fetch / Load / Delete toolbar |
| `CoverageLogPanel.kt` | Log tab: `ConsoleView` + `CoverageLogService` subscription |

### Affected-tests analysis
| File | Purpose |
|------|---------|
| `ChangedLinesAnalyzer.kt` | `git diff -U0` parser → changed old-side line numbers; modes: COMMITTED / WORKING_TREE |
| `AffectedTestsService.kt` | Looks up changed lines in coverage data → `AffectedTests` result |
| `AffectedTestsModel.kt` | Pure data model for affected-files view: checked files, removed tests, delta computation |
| `CoveragePathResolver.kt` | Resolves git-root-relative path in coverage map (direct + leading-`/` variant); also `resolveBaseline()` for baseline reader |
| `ToggleAffectedFilesViewAction.kt` | Toolbar toggle: tree ↔ flat list in the files pane |

### Behat runner
| File | Purpose |
|------|---------|
| `BehatTestRunner.kt` | Runs scenarios via Behat run-configuration template; single or bundled multi-path launch; passes feature file paths as separate positional arguments (e.g. `file:1 file:2`) |
| `CoverageTestNavigator.kt` | PSI navigation to scenario; converts git-root-relative feature paths to project-relative |

### Errors & logging
| File | Purpose |
|------|---------|
| `CoverageApiException.kt` | Typed exception with `CoverageErrorKind` enum |
| `CoverageErrorReporter.kt` | IDE "Submit" error reporter |
| `CoverageLog.kt` | Logger wrapper — writes to `idea.log` **and** `CoverageLogService` |
| `CoverageLogService.kt` | Ring buffer (2000 entries) with thread-safe subscribe/backfill |

## Dual-coverage mode

When the current branch has its own pipeline **and** the coverage branch (master) has a different commit, the plugin loads both simultaneously:

- **Primary** = current-branch pipeline (feature or coverage-branch fallback).
- **Baseline** = coverage-branch (master) pipeline. `null` when commits are equal (single-coverage mode) or when resolution fails.

`CoverageLoadService.loadFromGitLab()` calls `CoverageResolver.resolveDual()`, writes two `.cov4` files to the disk cache, and attaches two `Cov4Reader`s to `CoverageDataService`.

**Gutter colours** (set by `CoverageHighlighter.categorizeLine`):
| Colour | Meaning |
|--------|---------|
| 🟢 Green | Line covered; all tests also exist on master |
| 🔵 Blue | Line covered; at least one test is new on this branch (`FEATURE_ONLY`) |
| 🔴 Red | Line not covered by any test |

**Test filter** (bottom "Covering Line" panel, visible only in dual mode):
| Filter | Shows |
|--------|-------|
| Both | Union of primary + baseline tests |
| Master Only | Tests that appear in both (still on master) |
| New on This Branch | Tests only in primary (feature-only diff) |

`CoverageDiff` provides the set-diff / union helpers used by both the highlighter and the panel.

## Flow

On startup, HEAD change, or settings save (Apply/OK): `loadOfflineFirst()` walks recent commits for a cache hit and shows stale coverage immediately, then `loadFromGitLab()` refreshes in the background (resolve dual → download primary + baseline → write COV4 → swap readers). **All auto-loading is skipped when gutter visibility is off** (`CoverageGutterVisibilityService.visible == false`) — no GitLab calls, no cache walks, no polling. On each editor open, `CoverageHighlighter` maps old line numbers to current positions via `LineMappingService` for both primary and baseline, then classifies each line.

## MCP

```json
{ "mcpServers": { "coverage": { "url": "http://localhost:17178/mcp" } } }
```

Tool: `get_coverage_for_file` — returns covered/uncovered lines with test names for any PHP file.
Tool: `list_files` — lists files with coverage data under a directory.

## Build & Run

```bash
./gradlew compileKotlin   # compile
./gradlew test            # unit tests
./gradlew buildPlugin     # distributable zip
./gradlew runIde          # sandbox IDE
```

## Conventions

- **Logging**: `private val log = CoverageLog.get(Foo::class.java)` — never `Logger.getInstance` directly.
- **Paths**: canonical key is git-root-relative (e.g. `api/hotels/src/Foo.php`). `LineMappingService.toGitRelativePath` converts project-relative → git-relative. Feature paths in test names are also git-root-relative; strip with `CoverageTestNavigator.toProjectRelativeFeaturePath`.
- **Line numbers**: 1-based throughout.
- **Cache key**: commit hash (not pipeline ID).
- **Threading**: network/parsing in `Task.Backgroundable`; UI updates via `invokeLater`.
- **Errors**: throw `CoverageApiException(CoverageErrorKind.*)`. Auto-triggered callers only log (silent mode).
- **Enabled flag**: `CoverageApiSettings.enabled` — checked in `validateSettings()` before every load.
- **Gutter visibility gating**: when `CoverageGutterVisibilityService.visible` is `false`, all loading activity is suppressed — `loadOfflineFirst()`, `loadFromGitLab()`, `CoverageHeadTracker`, `CoverageStartupActivity`, `CoveragePipelinePoller` all skip/stop. Showing the gutter resumes normal behavior via `loadOfflineFirst()`.
- **Dual coverage**: `CoverageDataService` holds primary + baseline `Cov4Reader`. Baseline is `null` in single-coverage mode. `CoverageDiff.featureOnly(primary, baseline)` computes the per-line blue set. `CoverageHighlighter.categorizeLine(primary, baseline, hasBaseline)` is the authoritative classifier for gutter colour.
- **Behat paths**: `BehatTestRunner.runMultiplePaths()` passes feature file paths as separate positional arguments (e.g. `file:1 file:2 file:3`), not as comma-separated lines with `--paths` option.
