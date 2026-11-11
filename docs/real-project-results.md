# Real-project testing: results of the first campaign

Campaign of 2026-11-10/11 against `gitlab.raketa.in` (project 335) with the raketa worktree
(87 k files, 1.4 GB), sandbox IDE 2025.2 (`-Xmx4g`), covd from `coverage-assistant/dist/covd`,
a reflecting proxy in front of GitLab (no credential ever left the machine: token `dummy`).
Machine: 28 CPU, 62 GB RAM. Reproduce with `scripts/real-project-prepare.sh` and
`scripts/real-project-test.sh`; the scenarios and contracts are described in
`docs/real-project-testing.md`. Result: **17 of 17 scenarios pass**, the credential scan of every
run directory found nothing.

## Numbers

| Scenario | Loads | Slowest load | Artifacts | Wire MB | Requests | covd peak RSS | IDE heap max |
|---|---|---|---|---|---|---|---|
| R1 cold dual load (raketa) | 1 | 7.1 s | 14 | 25 | 20 | 393 MB | 0.8 GB |
| R2 restart with a warm cache | 2 | 7.3 s (1st) | 14 (all in the 1st) | 25 | 20 | 396 MB | 0.7 GB |
| R3 tour of 8 branches × 2 laps | 16 | 7.7 s | 70 (lap 2: 0) | 89 | 143 | 397 MB | 2.2 GB |
| R4 `api/hotels` as its own project | 2 | 7.1 s | 16 | 25 (hotels 0.4) | 48 | 397 MB | 0.7 GB |
| R5 switch during the download | 2 | 21.4 s | 28 | 36 | 41 | 388 MB | 1.8 GB |
| R6 switch while parsing (covd frozen) | 2 | 13.8 s | 28 | 36 | 41 | 395 MB | 2.2 GB |
| R7 two IDEs, one cache | 11 | 43.9 s | 28 | 21 | 64 | 254 MB | 2.2 GB |

* Phases of a cold dual load (covd `trace.timing`): resolve 2.1 s, download 2.2 s, parse 0.34 s, merge 0.45 s,
  write 2.0 s, baseline 2.6 s, total 7.1 s; the IDE shows the coverage about 15 s after the plugin starts.
* Idle IDE after indexing the raketa worktree: RSS 5.2 GB (heap 2.2 GB). Two IDEs need ≈ 11 GB.
* The cache entry of a branch is 57 MB on average (7 MB for a few commits, 107 MB for most), so 5 GiB
  (`--max-cache-bytes`) holds about 90 branches.

## Answers

* **Does it work in a real project?** Yes: raketa root and `api/hotels` (coverage keys are
  `api/hotels/...`, `pathMode` stays empty, hotels loads in 8.6 s from 0.4 MB).
* **Is the cache used?** Yes. After a restart and on the second lap of the tour no artifact is
  downloaded, covd answers in 6-10 ms with `cached=true`.
* **One daemon for two IDEs?** Yes: one pid and one instance id. For the same commit one IDE leads
  and the other gets `joinedFlight`, the artifacts are downloaded once; different commits download in
  parallel through the same process; stopping one IDE leaves the daemon running.
* **Branch switch during a download or a parse?** No corruption in either case: the answer for the
  old commit is applied, then the plugin reloads for the new HEAD (`pendingReload`) and the final state
  is always the new commit. The cost is waste: covd does not cancel (`context.WithoutCancel`), so the
  whole old download is finished (26 MB in R5) and the new coverage appears 20-34 s after the switch.
* **Network cut.** A 5xx on artifacts runs the ladder (1 + 3 attempts), then a balloon with
  *Retry now* / *Show log*; a transient cut recovers by itself.
* **Expired token.** One `AUTH_INVALID`, no retries, a sticky balloon *Update token…*; the poller and the
  HEAD tracker stay quiet (also after switching branches) until the settings change; applying a new token
  loads again without restarting the IDE.

## Defects found and fixed during the campaign

* The 30-second poller asked GitLab forever after deterministic errors and token errors → blocks in `RetryPolicy` (`3e11f51`).
* A HEAD change lifted the token block and asked GitLab again → the token block is tied to the settings only (`9b750d9`).
* `GitLabProject` lost the project paths, `HEAD` race of the daemon start, status handling of daemon answers (earlier commits).

## Open findings

1. **covd: a connection cut while reading an artifact is `INTERNAL`, `retryable=false`**
   (`unexpected EOF`), so a real network cut does not start the plugin's retry ladder; only 5xx does.
   Fix in `coverage-assistant` (`internal/gitlab/client.go`, `internal/covd/faults.go`): map read errors to
   `CONNECTION`/`GITLAB_API` with `retryable=true`.
2. **Wasted work on a switch.** covd finishes the old `ensure`; the plugin cannot cancel it. An option is to
   let the plugin skip a stale answer sooner, or covd to cancel when no other client waits for the flight.
3. **HEAD changes in a linked worktree are noticed late** (12-14 s, or only by the poller): the worktree's
   `HEAD` lives under `.git/worktrees/...`, outside the project. A regular checkout was not measured.
4. **Some master commits produce a `.cov4` with a single fake test `app/raketa/1`** (7 MB instead of
   107 MB with 18 k tests). On such commits "Covering Line" shows one fictitious test and the blue/green
   split against the baseline is skewed. Confirmed on 2026-11-11: the original
   `coverage-reports/coverage.covt.gz` from pipeline 2362676, job 14044870, already contains
   exactly one test identifier, `app/raketa/1`. Names are lost in CI before covd reads the
   artifact. The cause was confirmed in `raketa-libraries/behat-coverage-per-line-report` 1.0.2
   with `php-code-coverage` 14.5.1: `lineCoverageData()` now maps interned test indices to hit
   counts, but the reporter treated those counts as test names. The producer fix resolves the
   map keys through `ProcessedCodeCoverageData::testIds()` and preserves the legacy name-list
   format. A local run of the reported Client API feature then produced its two real scenario
   identifiers (`:53`, `:116`), and the IDE watcher loaded both. CI needs a release of that library.
   The plugin shows an explanation for already-broken numeric-session reports and offers no
   navigation or Run actions for them. Cache commit `690b7a9592af82e6ad764317a8297c9c81189d84`
   still contains 17,969 named tests and can be selected explicitly in Artifacts.
5. covd writes `cache-index.json.branch = <coverage branch>` for every entry (`ensure.go:713`), so the
   Artifacts "branch" column is useless.
6. The plugin sends `component = api/hotels` (the service dir, cache file `<sha>-api_hotels.cov4`) while
   raketa's component equals its token; docs speak of `hotels`.
7. A retry that reaches covd multiplies the work: a permanent 5xx produced 480 proxy requests in 7 loads
   (covd repeats each shard 4-5 times inside every plugin attempt).
8. The IDE heap grew by ≈ 75% between the tour laps (1.3 → 2.2 GB with eight loaded commits); not a failure,
   worth a profile if users keep many branches open.
9. `bridge.dumb` turns false 13-16 s before the file scan finishes; the harness waits for the scan marker.
10. The baseline is the merge-base ancestor on master, not the master tip, and differs per branch.

## Harness notes

* `/tmp` is RAM-backed here: every IDE sandbox is ≈ 650 MB. The harness now deletes it after a scenario
  (`-DrealProject.keepIde=true` keeps it); the first full campaign filled `/tmp` and made R0 and R7 fail.
* `scripts/real-project-test.sh` runs every scenario sequentially (≈ 45 min); do not run anything heavy next to it.
