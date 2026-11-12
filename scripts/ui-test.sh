#!/usr/bin/env bash
# Starts the sandbox IDE with the RemoteRobot server, waits until the plugin is loaded and the
# robot answers, runs ./gradlew uiTest against it and always stops that IDE afterwards.
#
# Environment (all optional except a token):
#   GITLAB_TOKEN_FILE     file holding the GitLab token (preferred: it never enters the environment)
#   GITLAB_TOKEN          the token itself; copied to a private temp file for the test
#   GITLAB_TOKEN_FROM_IDE=1   explicit opt-in: take the token from the newest local
#                         ~/.config/JetBrains/*/options/coverageApiSettings.xml
#   UI_TEST_PROJECT       project the sandbox IDE opens (default: $HOME/projects/raketa)
#   UI_TEST_PHP_FILE      git-root-relative PHP fixture (default: the one in build.gradle.kts)
#   ROBOT_PORT            RemoteRobot port (default 8082)
#   UI_TEST_ALLOW_STALE=1 accept the cached (stale) artifact when HEAD has no pipeline yet
#   GITLAB_URL, GITLAB_PROJECT, GITLAB_PROJECT_NAME, COVERAGE_BRANCH  scenario settings
set -uo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

UI_TEST_PROJECT="${UI_TEST_PROJECT:-$HOME/projects/raketa}"
ROBOT_PORT="${ROBOT_PORT:-8082}"
TIMEOUT=300
GRADLE_LOG="$(mktemp /tmp/coverage-ide-uitest-gradle.XXXXXX)"
export UI_TEST_PROJECT

GRADLE_PROPS=("-PuiTestRobotPort=$ROBOT_PORT")
if [ -n "${UI_TEST_PHP_FILE:-}" ]; then
    GRADLE_PROPS+=("-PuiTestPhpFile=$UI_TEST_PHP_FILE")
fi

# --- token: always handed to the test as a private file, never as a variable ---------------------
TOKEN_TMP=""
if [ -n "${GITLAB_TOKEN_FILE:-}" ]; then
    [ -f "$GITLAB_TOKEN_FILE" ] || { echo "GITLAB_TOKEN_FILE does not exist: $GITLAB_TOKEN_FILE" >&2; exit 2; }
elif [ -n "${GITLAB_TOKEN:-}" ]; then
    TOKEN_TMP="$(mktemp)"            # mktemp creates it with mode 600
    printf '%s' "$GITLAB_TOKEN" >"$TOKEN_TMP"
    GITLAB_TOKEN_FILE="$TOKEN_TMP"
elif [ "${GITLAB_TOKEN_FROM_IDE:-}" = "1" ]; then
    SETTINGS_XML="$(ls -td "$HOME"/.config/JetBrains/*/options/coverageApiSettings.xml 2>/dev/null | head -n 1)"
    [ -n "$SETTINGS_XML" ] || { echo "GITLAB_TOKEN_FROM_IDE=1 but no coverageApiSettings.xml found" >&2; exit 2; }
    TOKEN_TMP="$(mktemp)"
    sed -n 's/.*name="bearerToken" value="\([^"]*\)".*/\1/p' "$SETTINGS_XML" | tr -d '\n' >"$TOKEN_TMP"
    [ -s "$TOKEN_TMP" ] || { echo "no token stored in $SETTINGS_XML" >&2; rm -f "$TOKEN_TMP"; exit 2; }
    GITLAB_TOKEN_FILE="$TOKEN_TMP"
else
    echo "Provide a token: GITLAB_TOKEN_FILE=<file>, GITLAB_TOKEN=<token> or GITLAB_TOKEN_FROM_IDE=1" >&2
    exit 2
fi
unset GITLAB_TOKEN
export GITLAB_TOKEN_FILE

# --- stopping only OUR sandbox IDE ----------------------------------------------------------------
# The sandbox IDE is started with -Didea.log.path=<repo>/.intellijPlatform/sandbox/<..>/log_runIdeForUiTests
# (see build.gradle.kts). That argument is unique to it, so no other IDE of the user matches.
ESCAPED_ROOT="$(printf '%s' "$ROOT" | sed 's/[][\.*^$+?(){}|]/\\&/g')"
OWN_IDE_PATTERN="-Didea\\.log\\.path=${ESCAPED_ROOT}/\\.intellijPlatform/sandbox/[^ ]*/log_runIdeForUiTests"

stop_own_ide() {
    pkill -f -- "$OWN_IDE_PATTERN" 2>/dev/null || true
}

GRADLE_PID=""
cleanup() {
    stop_own_ide
    [ -n "$GRADLE_PID" ] && wait "$GRADLE_PID" 2>/dev/null
    [ -n "$TOKEN_TMP" ] && rm -f "$TOKEN_TMP"
}
trap cleanup EXIT INT TERM

# A previous run of this very IDE would hold the robot port; its idea.log would also look "loaded".
stop_own_ide
rm -rf "$ROOT"/.intellijPlatform/sandbox/*/*/log_runIdeForUiTests

./gradlew "${GRADLE_PROPS[@]}" runIdeForUiTests --args="$UI_TEST_PROJECT" >"$GRADLE_LOG" 2>&1 &
GRADLE_PID=$!

IDEA_LOG=""
loaded=0
elapsed=0
while [ "$elapsed" -lt "$TIMEOUT" ]; do
    if ! kill -0 "$GRADLE_PID" 2>/dev/null; then
        echo "runIdeForUiTests exited before the plugin loaded" >&2
        break
    fi
    for dir in "$ROOT"/.intellijPlatform/sandbox/per-line-coverage-info/*/; do
        log="${dir}log_runIdeForUiTests/idea.log"
        if [ -f "$log" ] && grep -qE "Loaded custom plugins:.*per-line-coverage-info" "$log"; then
            IDEA_LOG="$log"
            loaded=1
            break
        fi
    done
    [ "$loaded" -eq 1 ] && break
    sleep 2
    elapsed=$((elapsed + 2))
done

# The plugin is logged as loaded before the RemoteRobot server listens: wait for it as well. Any
# listener answers on the port, so the page content is checked too.
if [ "$loaded" -eq 1 ]; then
    robot_up=0
    while [ "$elapsed" -lt "$TIMEOUT" ]; do
        if curl -s -m 2 "http://127.0.0.1:${ROBOT_PORT}" 2>/dev/null | grep -q "xpathEditor"; then
            robot_up=1
            break
        fi
        kill -0 "$GRADLE_PID" 2>/dev/null || break
        sleep 2
        elapsed=$((elapsed + 2))
    done
    [ "$robot_up" -eq 1 ] || loaded=0
fi

status=1
if [ "$loaded" -eq 1 ]; then
    GITLAB_URL="${GITLAB_URL:-gitlab.raketa.in}" \
    GITLAB_PROJECT="${GITLAB_PROJECT:-raketa/raketa}" \
    GITLAB_PROJECT_NAME="${GITLAB_PROJECT_NAME:-raketa}" \
    COVERAGE_BRANCH="${COVERAGE_BRANCH:-master}" \
        ./gradlew "${GRADLE_PROPS[@]}" uiTest
    status=$?
else
    echo "Plugin or RemoteRobot server was not ready within ${TIMEOUT}s" >&2
fi

if [ "$status" -ne 0 ]; then
    echo "UI tests failed. See:" >&2
    echo "  idea.log:    ${IDEA_LOG:-<not found>}" >&2
    echo "  gradle log:  $GRADLE_LOG" >&2
    echo "  test report: $ROOT/build/reports/tests/uiTest" >&2
    echo "  artifacts:   $ROOT/build/uiTest-artifacts" >&2
else
    rm -f "$GRADLE_LOG"
fi

exit "$status"
