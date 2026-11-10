# Per-Line Coverage Info — IntelliJ Plugin

## Overview

PhpStorm plugin that shows per-line PHP coverage inline in the editor. CI collects coverage via a custom PHP/Behat extension (`.covt` files) and uploads it to GitLab pipeline artifacts; the **covd** daemon (coverage-assistant) resolves the pipeline, downloads the shards and writes a local `.cov4` cache, and the plugin renders it via gutter icons + line backgrounds. Clicking a covered line shows the tests that execute it; tests can be re-run from the popup.

- **Plugin ID**: `com.github.yakov255.perlinecoverageinfo`
- **Platform**: IntelliJ IDEA Ultimate 2025.2+, depends on `com.intellij.modules.platform`, `com.intellij.mcpServer`, `com.jetbrains.php`, `gherkin`, optional `com.jetbrains.php.behat`, optional `Git4Idea`
- **Language**: Kotlin, JVM 21 · **Build**: Gradle + IntelliJ Platform Gradle Plugin
- **Coverage engine**: bundled `covd` daemon (Go, from `coverage-assistant`); the plugin is a client

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

### Component configuration
| File | Purpose |
|------|---------|
| `ComponentConfig.kt` | Hardcoded mapping from repository service directories to CI job prefixes (e.g. `api/avia` → `test:behat:avia`). `detectComponent()` returns the component for the current project root (root → `raketa`, subpath → matching component). Coverage is published by the dedicated `test:behat:coverage:<component>` jobs (derived via `coverageJobPrefix`); `selectCoverageJobNames()` prefers those and falls back to legacy `test:behat:<component>` jobs only if no coverage job exists. |

### MCP server
| File | Purpose |
|------|---------|
| `CoverageMcpToolset.kt` | JetBrains MCP framework toolset via `com.intellij.mcpServer`; exposes `get_coverage_for_file`, `get_tests_at_line`, `get_first_tests_at_lines`, and `list_files` tools |

### GitLab and git access
| File | Purpose |
|------|---------|
| `GitLabProjectsClient.kt` | Minimal REST client used only by the settings screen to list/search the projects the token can access |
| `GitCommand.kt` | Read-only `git` wrapper: HEAD, recent commits, merge-base, `rev-list --count` for the "behind by N" warnings |
| `GitLabCoordinator.kt` | App-level (`Service.Level.APP`) LRU of shared `Cov4Reader` instances plus `evictSharedReaders()` after a cache rewrite |
| `CovdClient.kt` | Client for the coverage daemon: binary extraction, handshake, `ensure`, `import`, artifact listing/deletion |

### Loading pipeline
| File | Purpose |
|------|---------|
| `CoverageLoadService.kt` | Orchestrator: `loadOfflineFirst()` (cache walk → stale display → daemon refresh), `loadFromGitLab()` (one `ensure` against covd; `ready`/`not_ready`/`error` handling, retry by `retryAfterMs`), `loadFromLocalFile()` (daemon `import`), `loadFromCache()` (pinned artifact). Applies readers to `CoverageDataService` and computes the "behind by" warning context |
| `CoverageHeadTracker.kt` | Triggers `loadOfflineFirst()` on every HEAD change (debounced 2 s); skipped when gutter hidden |
| `CoverageStartupActivity.kt` | On project open: remote-URL auto-check, then `loadOfflineFirst()`; logged JVM arguments for the perf log |
| `CoveragePipelinePoller.kt` | 30-second tick that asks the daemon again; no GitLab logic. Skipped while a load is in progress, disabled when the gutter is hidden |
| `LoadCoverageAction.kt` | Find Action: "Load Coverage from GitLab" |
| `LoadLocalCoverageAction.kt` | Find Action: "Load Coverage from File" (.covt/.covt.gz), imported through the daemon |
| `ClearCoverageAction.kt` | Find Action: "Clear Coverage Data" |

### On-disk format
| File | Purpose |
|------|---------|
| `Cov4Reader.kt` | Reads COV4 on demand (lazy per-file decoding, streams the index instead of loading the file); implements `Closeable`. Reloads its index when the file was replaced. Accepts optional path/test normalizers, so old raketa `.cov4` caches are normalized while reading |
| `CoveragePathNormalizer.kt` | Normalizes raketa `.covt` keys/tests to git-root-relative paths; used when reading pre-upgrade caches (the daemon normalizes new ones) |
| `CoverageCacheService.kt` | Read-only view of `~/.cache/coverage-plugin/<projectId>/`: `cache-index.json` + `<hash>-<component>.cov4`, reader sharing, artifact listing. Writes and deletions go through the daemon |

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

`covd` resolves both pipelines and writes two `.cov4` files; `CoverageLoadService` attaches the returned paths to `CoverageDataService` as primary and baseline readers.

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

On startup, HEAD change, settings save, or the 30-second poll: `loadOfflineFirst()` shows any cached artifact immediately (stale), then `loadFromGitLab()` sends one `ensure` to covd. The daemon resolves the pipeline (current branch → coverage branch → merge-base tiers), waits for coverage jobs, downloads the COVT shards, merges them, writes the `.cov4` files and updates the index; the plugin attaches readers to the returned paths. `not_ready` answers are retried after `retryAfterMs`; `error` answers map to user-facing messages by code.

The daemon is shared by every IDE instance and keyed by `--cache-dir`; identical concurrent `ensure` calls are coalesced inside it. The plugin never writes the cache index and never downloads artifacts itself. All auto-loading is skipped when gutter visibility is off.

Cache layout is per component (`~/.cache/coverage-plugin/<projectId>/<hash>-<component>.cov4`). On each editor open, `CoverageHighlighter` maps old line numbers to current positions via `LineMappingService` for both primary and baseline, then classifies each line.

## MCP

```json
{ "mcpServers": { "coverage": { "url": "http://localhost:17178/mcp" } } }
```

Tool: `get_coverage_for_file` — reads a file with per-line coverage markers. Each line includes content, line number, coverage flag, and count of covering tests. Supports `offset`/`limit` pagination and `coverage` filter (`all`/`covered`/`uncovered`). Does NOT return test names (use `get_tests_at_line` instead).

Tool: `get_tests_at_line` — returns paginated test names for a specific line. Use `offset`/`limit` (default 5, max 100) to paginate through long test lists.

Tool: `get_first_tests_at_lines` — returns the first covering test name for each of the given comma-separated line numbers. Useful for quickly checking which tests exercise which lines.

Tool: `list_files` — lists files with coverage data under a directory.

## Coverage daemon (covd)

Artifact processing runs in the `covd` daemon from the coverage-assistant repository:
it resolves the pipeline in GitLab, downloads the COVT shards, merges them and writes the
`.cov4` cache, so none of that work touches the IDE JVM.

- Binaries are built there with `make build-covd-all` (`dist/covd/covd-<os>-<arch>[.exe]`) and
  bundled here by `copyCovdBinaries`: pass `-PcovdDist=/path/to/dist/covd` (or `COVD_DIST`);
  without it the task copies nothing and the plugin degrades to the in-process pipeline.
  Resource layout: `/bin/<platform>/covd[.exe]`, platforms `linux-x64`, `linux-arm64`,
  `mac-x64`, `mac-arm64`, `win-x64`.
- `CovdClient` extracts the binary to `~/.cache/coverage-plugin/bin-covd-<ver>/`, starts it
  with `--cache-dir ~/.cache/coverage-plugin`, and reads the single stdout handshake line
  (`{"covdReady":{port,token,instanceId}}`). It never calls `/v1/shutdown`: the daemon is
  shared by all IDE instances and exits on its idle timeout.
- `loadWithCovd` posts `/v1/ensure` with `gitRoot`, `component`, `pathMode`, `jobPrefixes`
  (from `ComponentConfig`) and the GitLab settings; the token travels in the request body and
  is never stored. `ready` → attach `Cov4Reader` to the returned `cov4Path`; `not_ready` →
  retry after `retryAfterMs`; `error` → report `code`/`hint`.
- The daemon owns `<cache>/<projectId>/cache-index.json` and the `.cov4` files:
  `CoverageCacheService` is read-only and deletions go through `DELETE /v1/artifacts`.
- The daemon is a hard dependency: without a working binary the plugin reports "coverage
  daemon unavailable" and loads nothing. There is no in-process fallback any more.
- Local `.covt` import: `POST /v1/import` with `{covtPath, projectId, component, pathMode,
  gitRoot}`; the commit key is the SHA-256 of the file content, so re-importing the same file
  reuses one cache entry.

## Build & Run

```bash
./gradlew compileKotlin            # compile
./gradlew test                     # unit tests
./gradlew buildPlugin              # distributable zip
./gradlew runIde                   # sandbox IDE
./gradlew generateUpdatePluginsXml # regenerate docs/updatePlugins.xml
```

## Performance diagnostics

`CoveragePerfLog` writes structured `key=value` lines to
`~/.cache/coverage-plugin/perf/run-<timestamp>-<pid>.log` (separate from `idea.log` and the
Log tab):

- `ev=span` — wall time, current-thread CPU, bytes allocated by the thread, heap before/after,
  GC count/ms delta, plus caller-provided details, with a `ph=start`/`ph=end` pair and nesting depth.
- `ev=counter` — aggregated count/avg/max/total for high-frequency work, flushed once per second.
- `ev=sample` — 1 Hz samples: heap used/committed/max, non-heap, threads, process CPU %, load
  average, GC deltas, JVM uptime.

Enabled by default; `-Dcoverage.perf.log=false` disables it and Gradle sets it for unit tests.
Find Action **Open Coverage Performance Log Folder** reveals the directory. Instrumented:
`load.offlineFirst`, `covd.ensure`/`covd.ready`/`covd.notReady`/`covd.error`, `poll.tick`,
`cov4.open`, `highlight.*`, `linemap.gitShow`, and `plugin.start` (JVM args, `-Xmx`).
Artifact parsing, merging and writing are measured by covd's own trace, not here.

## Release

Version lives in `gradle.properties` (`pluginVersion`, SemVer) — single source of truth.

Two-commit flow:
1. Work commit(s) add user-visible entries under `## [Unreleased]` in `CHANGELOG.md`.
2. Release commit titled `Release X.Y.Z`:
   - bump `pluginVersion` in `gradle.properties`;
   - move the `[Unreleased]` entries under `## [X.Y.Z] - YYYY-MM-DD`, leaving `[Unreleased]` empty;
   - regenerate `docs/updatePlugins.xml` (`./gradlew generateUpdatePluginsXml`).

Pushing to `main` triggers `.github/workflows/build.yml`, which runs `./gradlew buildPlugin`, creates a GitHub release `X.Y.Z` with the `per-line-coverage-info-X.Y.Z.zip` asset and notes from `getChangelog`, then regenerates and commits `docs/updatePlugins.xml`.

Rules:
- `.githooks/pre-commit` (enabled via `core.hooksPath`) **fails** if the version is bumped while `[Unreleased]` still has entries, and always regenerates and stages `docs/updatePlugins.xml`.
- Never hand-edit `docs/updatePlugins.xml` — it is generated.
- `docs/updatePlugins.xml` is the custom plugin update manifest served to users.

## Conventions

- **Logging**: `private val log = CoverageLog.get(Foo::class.java)` — never `Logger.getInstance` directly.
- **Paths**: canonical key is git-root-relative (e.g. `api/hotels/src/Foo.php`). `LineMappingService.toGitRelativePath` converts project-relative → git-relative. Feature paths in test names are also git-root-relative; strip with `CoverageTestNavigator.toProjectRelativeFeaturePath`.
- **Line numbers**: 1-based throughout.
- **Cache key**: commit hash + component (not pipeline ID). Files: `<hash>-<component>.cov4` under `~/.cache/coverage-plugin/<projectId>/`.
- **Daemon-only pipeline**: the plugin never talks to GitLab pipelines or artifacts; every load goes through `CovdClient.ensure()`. `ComponentConfig.detectComponent()` supplies `component` and `jobPrefixes` for that request.
- **Coverage CI job prefixes**: coverage archives are published by `test:behat:coverage:<component>` jobs; the daemon gets both prefixes and prefers the dedicated one. `ComponentEntry.jobName` is the component token.
- **Raketa coverage paths**: the daemon normalizes them (`pathMode: "raketa"`); the plugin still normalizes while reading pre-upgrade caches. If `SERVICE_DIR` changes in the raketa CI, update both `coverage-assistant/internal/linecov/normalize.go` and `CoveragePathNormalizer`.
- **Waiting and fallback** live in covd: it waits for running coverage jobs, falls back to the baseline pipeline, and reports `not_ready` with `reason`/`retryAfterMs` (the plugin retries).
- **Artifact metadata**: each cache entry stores `component` (String) and `branch` (String?) for display in the Artifacts table.
- **Threading**: `ensure` runs on a pooled thread; daemon answers are applied to data services there and UI updates go through `invokeLater`.
- **Errors**: daemon `code`/`cause`/`hint` drive the user-facing message in `CoverageLoadService`; auto-triggered callers only log.
- **Enabled flag**: `CoverageApiSettings.enabled` — checked in `validateSettings()` before every load.
- **Gutter visibility gating**: when `CoverageGutterVisibilityService.visible` is `false`, all loading activity is suppressed — `loadOfflineFirst()`, `loadFromGitLab()`, `CoverageHeadTracker`, `CoverageStartupActivity`, `CoveragePipelinePoller` all skip/stop. Showing the gutter resumes normal behavior via `loadOfflineFirst()`.
- **Dual coverage**: `CoverageDataService` holds primary + baseline `Cov4Reader`. Baseline is `null` in single-coverage mode. `CoverageDiff.featureOnly(primary, baseline)` computes the per-line blue set. `CoverageHighlighter.categorizeLine(primary, baseline, hasBaseline)` is the authoritative classifier for gutter colour.
- **HEAD tracker**: `CoverageHeadTracker` skits the initial `null → revision` event to avoid duplicating `CoverageStartupActivity`.
- **Behat paths**: `BehatTestRunner.runMultiplePaths()` passes feature file paths as separate positional arguments (e.g. `file:1 file:2 file:3`), not as comma-separated lines with `--paths` option.
