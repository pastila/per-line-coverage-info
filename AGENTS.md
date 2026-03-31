# Per-Line Coverage Info — IntelliJ Plugin

## Overview

An IntelliJ/PhpStorm plugin that fetches per-line code coverage data from an external API and displays it inline in the editor with background highlighting and gutter icons. Each line shows which tests cover it.

- **Plugin ID**: `com.github.yakov255.perlinecoverageinfo`
- **Platform**: PhpStorm (PS) 2024.2.5+, requires PHP plugin
- **Language**: Kotlin, JVM 21
- **Build**: Gradle with IntelliJ Platform Gradle Plugin
- **Serialization**: kotlinx-serialization-json

## Project Structure

All plugin source is under `src/main/kotlin/com/github/yakov255/perlinecoverageinfo/`:

| File | Purpose |
|------|---------|
| `CoverageApiClient.kt` | HTTP client — fetches coverage data from the API, resolves which commit to use via git merge-base |
| `CoverageApiModels.kt` | Data models for API responses (`BranchCommitsResponse`, `IdeFileCoverageResponse`) |
| `CoverageApiSettings.kt` | Persistent settings (API URL, bearer token) via `PersistentStateComponent` |
| `CoverageApiSettingsConfigurable.kt` | Settings UI under Settings → Tools → Coverage API |
| `CoverageDataService.kt` | Project-level in-memory cache: `Map<filePath, Map<lineNumber, List<testNames>>>` |
| `CoverageHighlighter.kt` | Applies line background colors (green=covered, red=uncovered) and gutter renderers |
| `CoverageGutterRenderer.kt` | Gutter icons + tooltips/popups showing test names per line |
| `CoverageEditorListener.kt` | Auto-applies highlights when editors open |
| `LoadCoverageAction.kt` | Tools menu action: fetches and loads coverage |
| `ClearCoverageAction.kt` | Tools menu action: clears all coverage annotations |
| `CoverageIcons.kt` | Green/red circle icons for gutter |

Plugin manifest: `src/main/resources/META-INF/plugin.xml`

## API Endpoints

The plugin communicates with two endpoints (base URL configured in settings):

1. **`GET /api/ide/branches/:branch/commits`** — Returns commit hashes with coverage on a branch.
   - Response: `{"commits": ["hash1", "hash2", ...]}`

2. **`GET /api/ide/commits/:commitHash/coverage/:filePath`** — Returns per-line coverage for a file.
   - Response: `{"lines": {"1": 0, "2": -1}, "testSets": [[0, 1]], "tests": ["test1", "test2"]}`
   - `lines` maps line numbers to a testSet index (-1 = uncovered, ≥0 = index into `testSets`)
   - `testSets` contains arrays of indices into `tests`

## Coverage Resolution Flow

1. Detect default branch via `git symbolic-ref refs/remotes/origin/HEAD`
2. Compute `git merge-base HEAD <defaultBranch>`
3. Fetch available commits from API for the default branch
4. Find the nearest local ancestor commit that has API coverage
5. For each PHP file in the project, fetch per-line coverage from that commit

## Build Commands

```bash
./gradlew compileKotlin          # Compile
./gradlew test                   # Run tests
./gradlew buildPlugin            # Build distributable plugin
./gradlew runIde                 # Launch sandbox IDE with plugin
```

## Conventions

- HTTP client: Java's built-in `java.net.http.HttpClient` (no external HTTP libraries)
- Git operations: command-line `git` via `ProcessBuilder` (not JGit)
- File discovery: IntelliJ's `FilenameIndex` for PHP files
- Path matching in `CoverageHighlighter` tries multiple normalization strategies (absolute, relative, leading slash, suffix match)
- All coverage data is 1-based line numbers
