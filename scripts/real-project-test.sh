#!/usr/bin/env bash
# Ornament: real-project test run. Prepares the sandbox IDE once, runs ./gradlew realProjectTest
# and prints the run directory with the report at the end.
#
# The scenarios drive real IDE processes against the real raketa repository through a local
# reflecting proxy; no GitLab token is needed and none is read here.
#
# Usage:
#   scripts/real-project-test.sh [--scenario NAME] [--runs N] [--offline] [--skip-prepare]
#
# Environment (all optional):
#   COVD_DIST      directory with covd-<os>-<arch> binaries (default: sibling coverage-assistant)
#   REAL_PROJECT_GRADLE_ARGS   extra arguments appended to the gradle command
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SCENARIO=""
RUNS=1
OFFLINE=0
SKIP_PREPARE=0

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --scenario)
            [ $# -ge 2 ] || { echo "--scenario needs a value" >&2; exit 2; }
            SCENARIO="$2"; shift 2 ;;
        --scenario=*)
            SCENARIO="${1#*=}"; shift ;;
        --runs)
            [ $# -ge 2 ] || { echo "--runs needs a value" >&2; exit 2; }
            RUNS="$2"; shift 2 ;;
        --runs=*)
            RUNS="${1#*=}"; shift ;;
        --offline)
            OFFLINE=1; shift ;;
        --skip-prepare)
            SKIP_PREPARE=1; shift ;;
        -h|--help)
            usage; exit 0 ;;
        *)
            echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$RUNS" in
    ''|*[!0-9]*) echo "--runs must be a positive integer" >&2; exit 2 ;;
esac
[ "$RUNS" -ge 1 ] || { echo "--runs must be >= 1" >&2; exit 2; }

fail() {
    echo "real-project-test: $*" >&2
    exit 2
}

# --- preconditions -----------------------------------------------------------------------------
find_covd() {
    local candidates=(
        "${COVD_DIST:-}/covd-linux-amd64"
        "$ROOT/build/covd-resources/bin/linux-x64/covd"
        "$ROOT/../raketa/yakov_scripts/coverage/coverage-assistant/dist/covd/covd-linux-amd64"
        "$ROOT/../coverage-assistant/dist/covd/covd-linux-amd64"
    )
    local candidate
    for candidate in "${candidates[@]}"; do
        [ -n "$candidate" ] && [ -f "$candidate" ] && [ -x "$candidate" ] && { echo "$candidate"; return 0; }
    done
    return 1
}

COVD_BIN="$(find_covd || true)"
if [ -z "$COVD_BIN" ]; then
    fail "no covd binary found. Build it (make build-covd-all in coverage-assistant) and pass COVD_DIST=<dist/covd>, or run ./gradlew processResources -PcovdDist=<dist/covd>"
fi

LICENSE_FOUND=0
for key in "$HOME"/.config/JetBrains/IntelliJIdea*/idea.key "$HOME"/.config/JetBrains/PhpStorm*/idea.key; do
    [ -f "$key" ] && LICENSE_FOUND=1 && break
done
if [ "$LICENSE_FOUND" -ne 1 ]; then
    fail "no local IDE license (idea.key) found; the sandbox IDE would start without Ultimate and PHP disabled"
fi

if [ -z "${DISPLAY:-}" ]; then
    fail "DISPLAY is not set; a real IDE needs a graphical session"
fi

AVAILABLE_KB="$(df -Pk /tmp 2>/dev/null | awk 'NR==2 {print $4}')"
if [ -z "$AVAILABLE_KB" ] || [ "$AVAILABLE_KB" -lt 2097152 ]; then
    fail "not enough free space in /tmp (need at least 2 GB, have ${AVAILABLE_KB:-?} KB)"
fi

# --- preparation -------------------------------------------------------------------------------
GRADLE_ARGS=(--console=plain)
[ "$OFFLINE" -eq 1 ] && GRADLE_ARGS+=(--offline)

PLUGINS_DIR="$(ls -d "$ROOT"/.intellijPlatform/sandbox/per-line-coverage-info/*/plugins 2>/dev/null | tail -n 1)"
if [ "$SKIP_PREPARE" -eq 0 ] || [ -z "$PLUGINS_DIR" ] || [ ! -d "$PLUGINS_DIR/per-line-coverage-info" ]; then
    echo "real-project-test: preparing the sandbox IDE (./gradlew prepareSandbox)" >&2
    ./gradlew "${GRADLE_ARGS[@]}" prepareSandbox >&2 || fail "prepareSandbox failed"
fi

# --- run ---------------------------------------------------------------------------------------
TEST_ARGS=("${GRADLE_ARGS[@]}" realProjectTest)
[ -n "$SCENARIO" ] && TEST_ARGS+=("-PrealProject.scenario=$SCENARIO")
TEST_ARGS+=("-PrealProject.runs=$RUNS")
[ -n "${REAL_PROJECT_GRADLE_ARGS:-}" ] && TEST_ARGS+=($REAL_PROJECT_GRADLE_ARGS)

echo "real-project-test: ${TEST_ARGS[*]}" >&2
./gradlew "${TEST_ARGS[@]}"
STATUS=$?

# --- report location ---------------------------------------------------------------------------
OUTPUT_DIR="$ROOT/build/real-project"
LATEST_RUN="$(ls -dt "$OUTPUT_DIR"/*/ 2>/dev/null | head -n 1)"
if [ -n "$LATEST_RUN" ]; then
    LATEST_RUN="${LATEST_RUN%/}"
    echo "real-project-test: run directory: $LATEST_RUN"
    if [ -f "$LATEST_RUN/report.md" ]; then
        echo "real-project-test: report:        $LATEST_RUN/report.md"
    else
        echo "real-project-test: report.md not written yet; raw data:"
        for artifact in scenarios.jsonl snapshots.jsonl samples.csv machine.json; do
            [ -f "$LATEST_RUN/$artifact" ] && echo "  $LATEST_RUN/$artifact"
        done
    fi
else
    echo "real-project-test: no run directory under $OUTPUT_DIR" >&2
fi

exit "$STATUS"
