# Real-project testing (тестирование на реальном проекте)

Load and failure scenarios that drive a sandbox IDE and the `covd` daemon against the real
`raketa` repository. They answer: how long download/parse takes, how much memory the IDE and the
daemon use, whether the cache works, whether one daemon serves two IDEs, what happens on a branch
switch in the middle of a download or a parse, what happens on a network cut and on an expired
token. Run with `scripts/real-project-test.sh`; never part of `./gradlew test`.

## Architecture

```
IDE-1 (sandbox, -Dcoverage.*) ─┐
IDE-2 (own config dir)         ├─► covd (one process) ─► reflecting proxy 127.0.0.1:P ─► gitlab.raketa.in
                               ┘                          strips Authorization / PRIVATE-TOKEN,
git worktree /tmp/raketa-load                             journals every request (JSONL),
(git checkout --detach <sha>)                             throttles, cuts, answers 401, emits events
```

* The plugin is configured with `gitlabDomain=http://127.0.0.1:<proxy port>`, token `dummy`,
  project `335`. The proxy never forwards credentials, so **no real token is involved** and none
  can leak. An optional smoke with a real token reads it only from `GITLAB_TOKEN_FILE`.
* Branches are switched from outside with `git checkout --detach <sha>` in a dedicated worktree;
  `CoverageHeadTracker` (2 s debounce) triggers loads like in real use. The user's own checkout
  `~/projects/raketa` is never modified (no checkout/reset/clean there; `git fetch` only).
* State is read from `CoverageUiTestBridge` snapshots plus the perf log.

## Facts found during reconnaissance

* The GitLab API is readable anonymously (pipelines, jobs, `HEAD` on artifacts → 200).
* Project 335 = `raketa/raketa`. A master `push` pipeline has 7 coverage shards
  `test:behat:coverage:raketa-8.5: [sprint:N]` of 4.5-5.8 MB each (≈ 32-40 MB; dual ≈ 70 MB).
  `schedule` pipelines have **no** coverage jobs. hotels: `test:behat:coverage:hotels-8.5`, ≈ 0.7 MB,
  only on branches that touch hotels.
* covd runs the work with `context.WithoutCancel` (`ensure.go`): a client that disconnects does
  not cancel the download.
* Spike (verified): `covd -> reflecting proxy -> gitlab.raketa.in` works with token `dummy`; the
  anonymous API answers. hotels coverage keys are already git-root-relative (`api/hotels/src/...`,
  2586 files, `.cov4` 1.5 MB) and `pathMode` stays empty for hotels, so they match what
  `LineMappingService.toGitRelativePath` builds when `api/hotels` is opened as its own project.
* `pathMode=raketa` reads `app/raketa/features` from the **disk** of `gitRoot` to normalize test
  names: raketa runs need a real checkout (the worktree), not a bare repository.
* GitLab `Link` headers point at `https://raketa.dev` (its `external_url`), not at
  `gitlab.raketa.in`; the proxy rewrites any absolute URL. covd paginates through `X-Next-Page`
  and never follows `Link`. Trace byte counts are the unpacked size: report traffic from the
  proxy journal. Artifacts expire (a branch artifact expired the same day): pick pipelines from a
  fresh inventory, never hardcode ids.

## Contracts (shared by all work items)

### Plugin system properties (implemented in `src/main`)

| Property | Meaning |
|---|---|
| `-Dcoverage.cacheRoot=<dir>` | Replaces `~/.cache/coverage-plugin` for `CovdClient` (daemon `--cache-dir`, binary extraction) **and** `CoverageCacheService`. The daemon is shared by every process with the same cacheRoot |
| `-Dcoverage.perf.dir=<dir>` | Perf-log directory (default `<cacheRoot>/perf`) |
| `-Dcoverage.covdResponses=<dir>` | When set, every `ensure`/`import` answer is saved there (see below). Off by default |
| `-Dcoverage.uiTestBridge=true`, `-Dcoverage.uiTestState=<file>`, `-Dcoverage.uiTestFiles=<csv>` | Existing bridge |
| `-Dcoverage.perf.log=true` | Existing perf log (on by default) |

### Saved covd answers

File name `<seq:04d>-<yyyyMMdd-HHmmss-SSS>-<kind>-<state>.json`, `kind` = `ensure` | `import`.
Content (the token is never present; `settings.token` is not written):

```json
{"seq":1,"atMs":1760000000000,"kind":"ensure","elapsedMs":4321,"httpStatus":200,
 "request":{"component":"raketa","projectId":"335","gitRoot":"/tmp/raketa-load","coverageBranch":"master",
            "pathMode":"raketa","jobPrefixes":["..."],"baseUrl":"http://127.0.0.1:41000","force":false},
 "response":{ ...the daemon's JSON body, unmodified... }}
```

### Perf-log events (`key=value`, same writer as the existing `ev=` lines)

* `covd.response`: `kind seq state code commit(8) cached replaced stale joinedFlight requestId httpStatus
  elapsedMs totalMs resolveMs downloadMs parseMs mergeMs writeMs baselineMs gitlabCalls gitlabBytes gitlabErrors`
* `covd.spawn`: `how=spawn|reuse|restart pid port instance(8) startMs`
* `ev=covdsample` (1 Hz while a daemon is known): `pid rssMb cpuPct threads vszMb`

### Bridge snapshot additions

`lastError`, `loading`, `baselineCommit`, `coverageGeneration`, `dumb` (indexing), `covdInstanceId`, `covdPid`.

### Run directory `build/real-project/<run-id>/`

`proxy.jsonl` (one row per proxied request), `perf/` (copies of perf logs), `covd-responses/`,
`samples.csv` (`tMs,proc,pid,rssMb,cpuPct`), `snapshots.jsonl` (bridge snapshots with `tMs`),
`scenarios.jsonl` (`{"t","scenario","phase","msg","data"}` written by scenarios), `plan.json`
(branch inventory), `report.md`, `summary.json`.

### Proxy journal row (`proxy.jsonl`)

`{"t":<epoch ms>,"seq":n,"method":"GET","path":"/api/v4/...","query":"...","status":200,"bytesDown":123,"bytesUp":0,"ms":42,"fault":null|"drop"|"status"|"throttle","artifactJobId":123|null,"authSeen":true|false}`
(`authSeen` records only whether a credential header arrived; its value is never stored.)

## Scenarios

| ID | What | Expectation |
|---|---|---|
| R0 | Open worktree root, wait for indexing (`dumb=false`) | Baseline memory/CPU |
| R1 | Cold start: empty cache, master, dual | `ready`; phase timings; peak daemon RSS; ≈ 70 MB through the proxy |
| R2 | Repeat load, then IDE restart | `cached=true`, 0 GitLab requests |
| R3 | Tour of 8-10 branches, two laps | Lap 1 downloads, lap 2 hits the cache; no IDE heap leak |
| R4 | Open `api/hotels` as its own project | Component `hotels`; coverage keys match `api/hotels/...`; gutter classification works |
| R5 | Switch branch during download (throttled proxy) | No corruption; new HEAD wins after the first load ends |
| R6 | Switch during parse (`SIGSTOP` covd after the last shard) | Old commit never overwrites the new one |
| R7 | Two IDEs, one cacheRoot: same commit; different commits; close one | One covd pid/instance; same commit → `joinedFlight`, one download |
| R8 | Cache growth and `--max-cache-bytes` | Size and eviction recorded |
| F1 | Network cut during download (once; permanent) | Retries (5/15/45 s), then a balloon with Retry / Show log |
| F2 | Token expired (401 from request N, also mid-download) | No retry loop; balloon "token expired" opens settings; new token loads without restart |
