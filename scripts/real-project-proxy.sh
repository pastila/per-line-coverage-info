#!/usr/bin/env bash
# Manual launcher for the reflecting proxy (real-project test source set).
#
#   scripts/real-project-proxy.sh [--port N] [--upstream URL] [--journal FILE]
#
# Defaults: --port 0 (an ephemeral port, printed on start), --upstream https://gitlab.raketa.in.
# The proxy serves until Ctrl-C and strips Authorization / PRIVATE-TOKEN / JOB-TOKEN / Cookie /
# Proxy-Authorization from every request, so the client token can simply be "dummy".
#
# Control endpoints of a running proxy:
#   GET  /__proxy/stats    counters (requests, bytesDown, artifactsCompleted, byStatus)
#   POST /__proxy/faults   applies a fault document, e.g.
#        {"clear":true,
#         "throttle":{"bytesPerSec":1000000,"pathRegex":"/artifacts"},
#         "dropAfter":{"pathRegex":"/artifacts","afterBytes":65536,"times":1},
#         "respondStatus":{"pathRegex":"/pipelines","status":401,"fromRequestN":2,"times":1,
#                          "body":"{\"message\":\"401 Unauthorized\"}"},
#         "delay":{"ms":500}}
# The proxy writes one JSON line per request to --journal (format: "Proxy journal row" in
# docs/real-project-testing.md).
#
# The task comes from scripts/real-project-proxy.init.gradle.kts, so build.gradle.kts stays
# untouched; it needs the realProject classes compiled (this script builds them on demand).
set -euo pipefail

cd "$(dirname "$0")/.."

exec ./gradlew --offline -q --console=plain \
    -I scripts/real-project-proxy.init.gradle.kts \
    realProjectProxy --args="$*"
