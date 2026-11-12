#!/usr/bin/env bash
# Prepares the real-project load environment: reads the anonymous GitLab branch inventory,
# fetches the missing commits into the shared repository and creates the detached worktree.
#
# The GitLab API is read anonymously (no token, no Authorization header) and only `git fetch`
# talks to the remote; the user's working copy is never modified.
#
# Usage: scripts/real-project-prepare.sh [--dry-run] [--no-fetch] [--pipelines N] [extra options]
#   --dry-run      read the inventory and print the summary; no fetch, no worktree, no plan file
#   --no-fetch     use only the commits already present locally
#   --pipelines N  number of successful pipelines to scan (default 200)
# Other options (--plan, --repo, --worktree, --branch, --branches, --hotels, --pause-ms) are
# forwarded to InventoryCli; see its usage text.
#
# Environment: GRADLE_ARGS (default --offline), REAL_PROJECT_* knobs read by the CLI.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

GRADLE_ARGS=${GRADLE_ARGS:---offline}
INIT_SCRIPT=$(mktemp "${TMPDIR:-/tmp}/real-project-classpath-XXXXXX.gradle")
trap 'rm -f "$INIT_SCRIPT"' EXIT

DRY_RUN=0
NO_FETCH=0
PIPELINES="${REAL_PROJECT_PIPELINES:-200}"
OTHER_ARGS=()

usage() {
  sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while (($#)); do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --no-fetch) NO_FETCH=1 ;;
    --help | -h)
      usage
      exit 0
      ;;
    --pipelines=*) PIPELINES="${1#*=}" ;;
    --pipelines)
      shift
      PIPELINES="${1:?--pipelines needs a value}"
      ;;
    *) OTHER_ARGS+=("$1") ;;
  esac
  shift
done

cat >"$INIT_SCRIPT" <<'GRADLE'
gradle.rootProject { project ->
    project.tasks.register("realProjectPrintClasspath") {
        dependsOn(project.tasks.named("realProjectClasses"))
        doLast {
            def sourceSets = project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer)
            println "REAL_PROJECT_CLASSPATH=" + sourceSets.getByName("realProject").runtimeClasspath.asPath
        }
    }
}
GRADLE

echo "==> inventory: project 335, $PIPELINES successful pipelines, anonymous API"
CLASSPATH_LINE=$(
  ./gradlew $GRADLE_ARGS --no-configuration-cache -q -I "$INIT_SCRIPT" realProjectPrintClasspath \
    | grep '^REAL_PROJECT_CLASSPATH=' | tail -n 1
) || true

CLASSPATH=${CLASSPATH_LINE#REAL_PROJECT_CLASSPATH=}
if [[ -z "$CLASSPATH" ]]; then
  echo "error: cannot resolve the realProject classpath (./gradlew realProjectClasses failed)" >&2
  exit 1
fi

ARGS=(--pipelines "$PIPELINES")
((DRY_RUN)) && ARGS+=(--dry-run)
((NO_FETCH)) && ARGS+=(--no-fetch)
((${#OTHER_ARGS[@]})) && ARGS+=("${OTHER_ARGS[@]}")

OUTPUT_DIR="$PWD/build/real-project"
mkdir -p "$OUTPUT_DIR"

java \
  -DrealProject.outputDir="$OUTPUT_DIR" \
  -DrealProject.projectDir="$PWD" \
  -cp "$CLASSPATH" \
  com.github.yakov255.perlinecoverageinfo.realproject.inventory.InventoryCli \
  "${ARGS[@]}"
