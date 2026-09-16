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
| `CoverageApiSettings.kt` | App-level settings: GitLab domain/token/project, coverage branch, `enabled` master switch, `remoteUrlAutoChecked` sentinel, `localCoverageDir` (default `storage/coverage`) |
| `CoverageApiSettingsConfigurable.kt` | Settings UI — **Settings → Tools → GitLab Coverage**; triggers `loadOfflineFirst()` for all open projects on Apply/OK if plugin enabled and configured |

### Component configuration
| File | Purpose |
|------|---------|
| `ComponentConfig.kt` | Hardcoded mapping from repository service directories to CI job prefixes (e.g. `api/avia` → `test:behat:avia`). `detectComponent()` returns the component for the current project root (root → `raketa`, subpath → matching component). |

### MCP server
| File | Purpose |
|------|---------|
| `CoverageMcpToolset.kt` | JetBrains MCP framework toolset via `com.intellij.mcpServer`; exposes `get_coverage_for_file`, `get_tests_at_line`, `get_first_tests_at_lines`, and `list_files` tools |

### GitLab integration
| File | Purpose |
|------|---------|
| `GitLabApi.kt` | Interface for the GitLab REST surface (`listPipelines`, `listPipelineJobs`, `getMergeBase`, artifact download, project list). Two impls: thin HTTP client and the cached worker facade |
| `GitLabApiClient.kt` | Thin HTTP client implementing `GitLabApi`: pipelines, jobs, artifact download, merge-base, project list. Retries 5xx/429, honours `Retry-After` |
| `GitLabModels.kt` | `@Serializable` data classes for GitLab REST responses |
| `CoverageResolver.kt` | Picks the best pipeline for current HEAD via three-tier merge-base resolution; `resolveDual()` returns `DualResolved(primary, baseline?)` for dual-coverage mode. Works against the shared `GitLabApi` facade |
| `CachedGitLabApi.kt` | Shared "worker" facade implementing `GitLabApi`: serializes metadata calls (pipelines/jobs/merge-base) on one dedicated worker thread, TTL-caches results, rate-limits actual requests. Artifact downloads pass through. `invalidateJobs()` forces fresh job status for the wait-loop |
| `GitLabCoordinator.kt` | App-level (`Service.Level.APP`) coordinator owning the single shared worker for the whole JVM: `api()` (recreates client on settings change, clears caches), `singleFlight()`/`memoize()` (cross-window coalescing + TTL memoization), `sharedReader()` (LRU of `Cov4Reader`), `markRefreshed()`/`isWithinRefreshCooldown()` (refresh cooldown) |
| `TtlCache.kt` | Generic thread-safe TTL cache (lazy expiry) |
| `RateLimiter.kt` | Token-bucket rate limiter (`tryAcquire`/`acquire(timeout)`) |

### Loading pipeline
| File | Purpose |
|------|---------|
| `CoverageLoadService.kt` | Central orchestrator: `loadOfflineFirst()` (cache walk → stale fallback → GitLab refresh), `loadFromGitLab()` (two-phase: silent resolve → visible download on cache miss). Resolves through `GitLabCoordinator` (memoized per git-root/HEAD, short-circuits when fresh within 60 s cooldown); coalesces identical downloads across windows via `singleFlight`. `downloadArtifacts()` filters by detected component, waits for running behat jobs (infinite, cancel via progress/branch switch), falls back to baseline pipeline on `NO_DATA`. `loadOrFetchBaseline()` accepts `preloadedReader` to avoid redundant `.cov4` opens. |
| `CoverageHeadTracker.kt` | Triggers `loadOfflineFirst()` on every HEAD change (debounced 2 s); skipped when gutter hidden |
| `CoverageStartupActivity.kt` | On project open: remote-URL auto-check, then `loadOfflineFirst()`; skipped when gutter hidden |
| `CoveragePipelinePoller.kt` | Polls remote for new pipelines and auto-refreshes coverage (every 30 s; skipped while a load is in progress); uses the shared cached API, so N windows/pollers cost one network request per TTL; stopped/blocked when gutter hidden |
| `LoadCoverageAction.kt` | Find Action: "Load Coverage from GitLab" |
| `LoadLocalCoverageAction.kt` | Find Action: "Load Coverage from File" (.covt/.covt.gz) — adds a run to the local layer |
| `ClearLocalCoverageAction.kt` | Find Action: "Clear Local Coverage" |
| `CollectLocalCoverageToggleAction.kt` | Toggle "Collect Local Coverage" (Find Action + Covering Line toolbar) → `CoverageApiSettings.collectLocalCoverage` |
| `LocalCoverageRunExtension.kt` | `phpRunConfigurationExtension` (registered in `behat-integration.xml`): when the toggle is on, patches Behat command lines via `LocalCoverageCommand` |
| `LocalCoverageCommand.kt` | Pure command-line rewrite: finds the Behat script, matches its interpreter-side service dir (`/web/core`) to the git root (`core`), picks the profile with `BehatBinaryCoverage` (prefers `coverage-clover`), adds `-d pcov.*` before the script and `--profile/--binary-coverage-*` after; skips services without the extension and command lines that already set `--profile` |
| `ClearCoverageAction.kt` | Find Action: "Clear Coverage Data" |

### Parsing & on-disk format
| File | Purpose |
|------|---------|
| `BinaryCoverageParser.kt` | Parses `.covt` binary and CI artifact ZIPs → `Map<file, Map<line, List<test>>>` |
| `Cov4Writer.kt` | Writes COV4 random-access cache file (spec: `coverage_storage_format_v4.md`) |
| `Cov4Reader.kt` | Reads COV4 on demand (lazy per-file decoding); implements `Closeable` |
| `CoverageCacheService.kt` | Disk cache at `~/.cache/coverage-plugin/<projectId>/`; key = commit hash + component; files named `hash-component.cov4`; manages index, cleanup (7 days by `lastUsedMs`), last-used tracking, in-JVM-synchronized writes, shared `Cov4Reader` via coordinator. `CacheEntry` stores component and branch per artifact. |

### In-memory model
| File | Purpose |
|------|---------|
| `CoverageDataService.kt` | Holds active coverage: primary `Cov4Reader` + optional baseline `Cov4Reader`; tracks commit hashes, git root, stale flag |
| `LineMappingService.kt` | Maps old coverage line numbers to current document lines via `git show` + `ComparisonManager` diff; supports both primary and baseline commits; LRU cache of file contents |
| `CoverageLineMapper.kt` | Pure functions: diff old/new content, build old→new line map |
| `LocalCoverageLayer.kt` | Pure model of local runs: `LocalCoverageRun` (tests + coverage + file snapshots), `LocalCoverageLayer` (newer run supersedes the same tests in older runs), `LocalCoverageMerge` (CI ⊕ local), `LocalCoveragePaths` (`core/web/app/…` → `app/…`) |
| `LocalCoverageService.kt` | Project service holding the local layer: loads `.covt` runs (manual or auto via IDE file watcher on `localCoverageDir`, loaded once size+mtime settle), maps each run from its snapshot onto the current document |
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
| 🔵 Dark blue | Line covered by at least one test from a local run (`LOCAL`, takes precedence; see "Local coverage layer") |
| 🔴 Red | Line not covered by any test |

**Test filter** (bottom "Covering Line" panel, visible only in dual mode):
| Filter | Shows |
|--------|-------|
| Both | Union of primary + baseline tests |
| Master Only | Tests that appear in both (still on master) |
| New on This Branch | Tests only in primary (feature-only diff) |

`CoverageDiff` provides the set-diff / union helpers used by both the highlighter and the panel.

## Local coverage layer

Coverage from local Behat runs is kept apart from the CI readers in `LocalCoverageService`, so CI reloads (poller, HEAD change) never drop it. Per line of the current document:

```
effective = (ci − localTests) ∪ local
```

`localTests` is the full test list from the `.covt` headers: a scenario re-run locally replaces its CI data everywhere, including files the local run no longer reaches; scenarios not run locally keep their CI data. Line numbers: CI is mapped from `git show <commit>:path`, each local run from the file snapshot taken when the run was loaded (the run executed the on-disk files, which may be uncommitted). `CoverageHighlighter` merges via `LocalCoverageMerge.merge` before rendering, so dual-mode classification works unchanged — local tests missing on master show as blue.

Auto-load: `LocalFileSystem.addRootToWatch(<git root>/<localCoverageDir>)` + `VFS_CHANGES` listener; the directory and its parent get their VFS children loaded so events arrive even when it is excluded from the project. Files already present at startup are not loaded (their snapshots are unknown). Collecting: with `collectLocalCoverage` on, `LocalCoverageRunExtension` rewrites every Behat launch from the IDE to write `<git root>/<localCoverageDir>/local.covt` (interpreter-side path), which the watcher then loads. Not yet merged: MCP tools, "Affected by Changes".

## Flow

On startup, HEAD change, or settings save (Apply/OK): `loadOfflineFirst()` walks recent commits for a cache hit and shows stale coverage immediately, then `loadFromGitLab()` refreshes in the background (resolve dual → download primary + baseline → write COV4 → swap readers). **All auto-loading is skipped when gutter visibility is off** (see below). GitLab requests are also skipped when the gutter is hidden.

All GitLab traffic goes through the app-level `GitLabCoordinator`: pipeline resolution is memoized per git-root/HEAD (TTL 60 s) and skipped entirely when the loaded coverage is already fresh within the 60 s refresh cooldown; identical downloads across project windows are coalesced via `singleFlight`; metadata calls (pipelines/jobs/merge-base) are serialized on one worker thread, TTL-cached (120 s / 600 s / 300 s) and rate-limited (12-token bucket, 1 token / 3 s) — so N open windows of the same repo cost the same as one.

`CoverageLoadService` auto-detects the current component via `ComponentConfig.detectComponent()` (root → `raketa`, subpath → matching component, unknown → error). During download, the plugin filters CI jobs to only the detected component, waits for running jobs (infinite — cancelled only via progress bar or branch switch), and falls back to the baseline (master) pipeline when the current branch has no jobs for the component.

Cache is stored per component (`~/.cache/coverage-plugin/<projectId>/<hash>-<component>.cov4`), shared across IDE instances via the shared project id. Each artifact tracks which branch it was downloaded from. On each editor open, `CoverageHighlighter` maps old line numbers to current positions via `LineMappingService` for both primary and baseline, then classifies each line.

## MCP

```json
{ "mcpServers": { "coverage": { "url": "http://localhost:17178/mcp" } } }
```

Tool: `get_coverage_for_file` — reads a file with per-line coverage markers. Each line includes content, line number, coverage flag, and count of covering tests. Supports `offset`/`limit` pagination and `coverage` filter (`all`/`covered`/`uncovered`). Does NOT return test names (use `get_tests_at_line` instead).

Tool: `get_tests_at_line` — returns paginated test names for a specific line. Use `offset`/`limit` (default 5, max 100) to paginate through long test lists.

Tool: `get_first_tests_at_lines` — returns the first covering test name for each of the given comma-separated line numbers. Useful for quickly checking which tests exercise which lines.

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
- **Cache key**: commit hash + component (not pipeline ID). Files: `<hash>-<component>.cov4` under `~/.cache/coverage-plugin/<projectId>/`.
- **Shared GitLab access**: always go through `GitLabCoordinator.getInstance().api()` (cached/rate-limited worker) — never construct a raw `GitLabApiClient` for the loading flow. `CoverageResolver`, `CoverageLoadService`, `CoveragePipelinePoller` take the `GitLabApi` facade.
- **Component**: `ComponentConfig.detectComponent()` maps project root → `raketa`, subdirectory → matching component. Only behat jobs for the detected component are downloaded.
- **Waiting**: `downloadArtifacts()` waits indefinitely for running behat jobs; cancelled via progress bar (`indicator.isCanceled`) or branch switch (`pendingReload`). The wait-loop calls `invalidateJobs()` to bypass the jobs TTL cache.
- **Fallback**: when the primary pipeline has no jobs for the component, `startDownloadTask()` falls back to the baseline (master) pipeline and clears the baseline reader.
- **Artifact metadata**: each cache entry stores `component` (String) and `branch` (String?) for display in the Artifacts table.
- **Threading**: pipeline resolve on pooled thread (silent); download in `Task.Backgroundable` (visible progress); UI updates via `invokeLater`. Metadata API calls are serialized on the coordinator worker thread.
- **Errors**: throw `CoverageApiException(CoverageErrorKind.*)`. Auto-triggered callers only log (silent mode).
- **Enabled flag**: `CoverageApiSettings.enabled` — checked in `validateSettings()` before every load.
- **Gutter visibility gating**: when `CoverageGutterVisibilityService.visible` is `false`, all loading activity is suppressed — `loadOfflineFirst()`, `loadFromGitLab()`, `CoverageHeadTracker`, `CoverageStartupActivity`, `CoveragePipelinePoller` all skip/stop. Showing the gutter resumes normal behavior via `loadOfflineFirst()`.
- **Dual coverage**: `CoverageDataService` holds primary + baseline `Cov4Reader`. Baseline is `null` in single-coverage mode. `CoverageDiff.featureOnly(primary, baseline)` computes the per-line blue set. `CoverageHighlighter.categorizeLine(primary, baseline, hasBaseline)` is the authoritative classifier for gutter colour.
- **HEAD tracker**: `CoverageHeadTracker` skits the initial `null → revision` event to avoid duplicating `CoverageStartupActivity`.
- **Behat paths**: `BehatTestRunner.runMultiplePaths()` passes feature file paths as separate positional arguments (e.g. `file:1 file:2 file:3`), not as comma-separated lines with `--paths` option.
