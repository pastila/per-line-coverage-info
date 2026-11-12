#!/usr/bin/env bash
# Launches the sandbox IDE, waits until the coverage plugin is loaded, then stops the IDE.
# runIde keeps the IDE window open on its own, so this script kills the IDE process after
# the check instead of waiting for a user to close it.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

SANDBOX_ROOT=".intellijPlatform/sandbox/per-line-coverage-info"
GRADLE_LOG="$(mktemp /tmp/coverage-ide-smoke-gradle.XXXXXX)"

# Only the runIde sandbox IDE is stopped: it is the one started with this repository's
# `.../sandbox/<..>/config` as its config path (the UI-test IDE uses `config_runIdeForUiTests`,
# the user's own IDEs use neither), so nothing else matches.
ESCAPED_ROOT="$(printf '%s' "$ROOT" | sed 's/[][\.*^$+?(){}|]/\\&/g')"
OWN_IDE_PATTERN="-Didea\\.config\\.path=${ESCAPED_ROOT}/\\.intellijPlatform/sandbox/[^ ]*/config( |\$)"

stop_own_ide() {
    pkill -f -- "$OWN_IDE_PATTERN" >/dev/null 2>&1 || true
}

GRADLE_PID=""
cleanup() {
    stop_own_ide
    [ -n "$GRADLE_PID" ] && wait "$GRADLE_PID" 2>/dev/null
}
trap cleanup EXIT INT TERM

# The most recently written idea.log of any sandbox version.
last_log() {
    ls -t "$SANDBOX_ROOT"/*/log/idea.log 2>/dev/null | head -n 1
}

LOG=$(last_log)
START=0
[ -n "$LOG" ] && START=$(wc -l < "$LOG")

echo "Starting the sandbox IDE (./gradlew runIde)..."
./gradlew runIde > "$GRADLE_LOG" 2>&1 &
GRADLE_PID=$!

STATUS=1
for _ in $(seq 1 150); do
    sleep 2
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
        echo "The IDE exited before the plugin loaded. See $GRADLE_LOG"
        break
    fi

    LOG=$(last_log)
    [ -z "$LOG" ] && continue

    # Only inspect lines written by this run: the log is appended across launches.
    if tail -n +"$((START + 1))" "$LOG" | grep -q "Loaded custom plugins:.*per-line-coverage-info"; then
        tail -n +"$((START + 1))" "$LOG" | grep -m1 "Loaded custom plugins:"
        STATUS=0
        break
    fi
done

[ "$STATUS" -ne 0 ] && echo "Coverage plugin was not loaded. See ${LOG:-<no log>} and $GRADLE_LOG"

echo "Stopping the IDE..."
stop_own_ide
wait "$GRADLE_PID" 2>/dev/null
GRADLE_PID=""

[ "$STATUS" -eq 0 ] && rm -f "$GRADLE_LOG"
exit "$STATUS"
