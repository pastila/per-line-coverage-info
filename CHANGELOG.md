<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# per-line-coverage-info Changelog

## [Unreleased]
- Local runs are tied to the commit they were collected on: after a checkout they are marked stale and stop superseding CI coverage (they used to keep hiding it on the new branch, turning covered lines red); a balloon reports it and "Restore Stale Local Coverage" in the Covering Line toolbar brings them back onto the current HEAD
- MCP: new `get_local_coverage_status`, `set_local_coverage_collection`, `restore_stale_local_coverage` and `clear_local_coverage` tools — read and flip the "Collect Local Coverage" switch, restore runs that went stale after a checkout, and drop local runs without leaving the agent
- Fix "No specifications found" when running tests from the coverage panels: each launch is now pinned to the `behat.yml`/`behat.php` that owns the feature file, and paths are relative to it (before, the working directory came from the first configured Behat framework, so a `core` feature was passed as `../../core/src/...`); tests from different services are launched one after another
- Local coverage layer: `.covt` files from local Behat runs are merged over CI coverage instead of replacing it; a locally re-run scenario replaces its CI data, other scenarios keep theirs
- Auto-load of new `.covt` / `.covt.gz` files from `storage/coverage` (Settings → Tools → GitLab Coverage → Local Coverage Dir) via the IDE file watcher
- Local coverage stays aligned while editing: each run keeps a snapshot of the files it executed and is line-mapped from it
- "Load Coverage from File" now adds to the local layer; new "Clear Local Coverage" action
- Lines covered by a local run are shown in dark blue (`LOCAL` category); the tooltip shows how many covering tests come from it
- "Collect Local Coverage" toggle (Covering Line toolbar, Find Action, settings): Behat runs started from the IDE — gutter ▶, run configurations, Run from the coverage panels — get pcov and binary-coverage options added and write `local.covt` into the local coverage dir; services whose `behat.yml` has no profile with the binary coverage extension run unchanged

## [2.7.0] - 2026-08-17
- Shared app-level GitLab coordinator: single worker thread, TTL-cached metadata and a rate limiter — dramatically fewer API requests
- Pipeline resolution memoized per repository/HEAD; refresh skipped within a 60s cooldown (zero requests on branch switches with cached coverage)
- Identical artifact downloads coalesced across project windows
- Pipeline polling interval reduced from 5s to 30s; polls share the cached API so N windows cost one network request
- `Cov4Reader` instances shared across windows (large files read once per commit)
- Disk cache writes synchronized for safety with multiple windows
- Retry HTTP 429 honouring `Retry-After`
- Send plugin name and version as `User-Agent` so requests are identifiable in GitLab server logs
- Visibility logging: cache hits/misses, rate-limiter waits, cross-window coalescing

## [2.6.0] - 2026-07-10
- Component-based coverage: separate `.cov4` per component, keyed by commit+component
- Component config with 10 components (api/avia, raketa, api/hotels, api/transfer, api/rail, api/profile-sync, api/queue, api/aeroexpress, api/contract, api/bus)
- Filter CI jobs by detected component; wait for running behat jobs (infinite, cancel via progress or branch switch)
- Fallback to baseline (master) pipeline when current branch has no jobs for the component
- Branch name stored per artifact; shown in Artifacts table
- Cache cleanup runs on every `writeCov4`; entries expire by `lastUsedMs` instead of `timestampMs`
- Reuse preloaded baseline reader to avoid redundant `.cov4` opens
- Skip initial HEAD reload in `CoverageHeadTracker` (avoids duplicate load at startup)
- Auto-refresh Artifacts panel on coverage change via `COVERAGE_CHANGED_TOPIC`
- `pluginUntilBuild` extended to `299.*`
- Simplify CI: build + release only, no tests/Qodana/Verifier/Marketplace
- Auto-create GitHub release with `.zip` asset on push to main (if version changed)
- Pre-commit hook to auto-generate `docs/updatePlugins.xml`
- Rewrite README for in-house Raketa usage

## [2.5.1] - 2026-07-13
- Show startup dialog to configure GitLab token when not set
- Normalize raketta coverage paths: strip `core/web/` prefix when downloading artifacts
- Replace coverage column with separate Покрыто/Не покрыто/Всего строк columns in artifacts table
- Add pre-commit hook to auto-generate `docs/updatePlugins.xml`
- Rewrite README for in-house Raketa usage

## [2.5.0] - 2026-06-05
- MCP: new `get_tests_at_line` tool with paginated test listing for a specific line
- MCP: new `get_first_tests_at_lines` tool — quickly check which test covers each line
- MCP: `get_coverage_for_file` now returns per-line content with pagination (`offset`/`limit`) and `coverage` filter
- MCP: `list_files` gains `coverage` filter (`all`/`uncovered`/`fully_covered`)
- Suppress progress indicator during pipeline resolution — only show status bar when actually downloading artifacts
- Simplify loading pipeline: unified download flow, removed dead code (`refreshBaselineFromCache`, `applyCoverageFromReader`)
- Fix double-close of `Cov4Reader` in post-download apply path

## [2.4.0] - 2026-06-04
- Stop all loading activity (GitLab downloads, pipeline polling, HEAD tracking, startup loading) when coverage gutter is hidden — resume when shown again

## [2.3.1] - 2026-06-04
- Switch to standard IntelliJ custom plugin repository for updates
- Pipeline polling for automatic coverage refresh
- Show report merging progress in the progress bar
- Show download speed and per-job byte progress in progress bar
- Set-based deduplication when merging coverage reports
- Queue reload when coverage load is triggered during an in-progress load
- Suppress misleading "0 commit(s) behind" warning
- Migrate MCP server to JetBrains MCP framework
- Add detailed logging across coverage pipeline
- Remove coverage status notification popups
- Fix EDT violations: move I/O off EDT, sync LRU cache, wrap Swing subscribers
- Fix "no coverage" display after refresh

## [2.3.0] - 2026-05-20
- Dual coverage diff view: show only new coverage
- MCP toolset: coverage access for LLM, list_files tool, detail parameter
- Async coverage artifact download
- Detect expired artifacts
- Speed up artifacts downloading
- Coverage auto-updating after settings save
- Warning if coverage is stale
- Loading optimization
- Show tab content during indexing
- No fallback to configured target branch
- Split affected tests and covered line tests
- Download current branch coverage

## [2.2.0] - 2026-04-14
- Embedded MCP server for LLM coverage access
- Auto-reload coverage on any HEAD change, not just branch switch
- Move coverage actions from Tools menu to Artifacts panel
- Artifacts tab in Coverage Tests tool window
- Allow all coverage actions during IDE indexing (dumb mode)
- Auto load coverage on start
- Select coverage dialog and saved selected artifact
- Store artifact usage history (last used artifact)
- Load from file now saves to cache and appears in artifacts list
- Handle GitLab token expired error
- Fix path resolution
- Many UI/UX improvements

## [2.1.0] - 2026-04-09
- Offline-first coverage loading
- Replace raw .covt cache with indexed COV4 binary format
- Add auto-refresh, disk cache, and local file loading
- Filter artifact downloads to behat jobs only
- Show Coverage gutter context menu action
- Reduce RAM on cache download
- Improve cache handling

## [2.0.0] - 2026-04-06
- Migrate plugin from custom API to GitLab coverage artifacts
- Three-tier merge-base resolution with GitLab API fallback
- Show covered test count
- Replace project search with load-all + live filter
- Fix project search: move HTTP call off EDT to background thread
- Fix coverage artifact download to match real GitLab behavior
- Improve error dialogs with detailed diagnostic info

## [1.0.0] - 2026-04-01
- Initial release
- Basic per-line coverage display in editor gutter
