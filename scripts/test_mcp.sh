#!/usr/bin/env bash
set -euo pipefail

MCP_URL="${MCP_URL:-http://127.0.0.1:17179/mcp}"
PROJECT="${PROJECT:-/home/yvladimirov/projects/raketa}"
PASS=0
FAIL=0
FILE="core/src/Avia/Aircompany.php"

call() { curl -s --max-time 10 "$MCP_URL" -H "Content-Type: application/json" -d "$1" 2>&1 || echo '{"error":"curl_failed"}'; }

# Extract tool-result text (for tools/call responses)
txt()  { echo "$1" | jq -r '.result.content[0].text' 2>/dev/null; }

# Checks
ok()    { echo "$1" | jq -e '.result != null and (.result.isError != true)' >/dev/null 2>&1; }
err()   { echo "$1" | jq -e '.result.isError == true' >/dev/null 2>&1; }
jrpc()  { echo "$1" | jq -e '.error != null' >/dev/null 2>&1; }
jq_match() { echo "$1" | jq -e "$2" >/dev/null 2>&1; }

inc_pass() { PASS=$((PASS + 1)); }
inc_fail() { FAIL=$((FAIL + 1)); }

assert_ok()   { if ok "$1"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2"; inc_fail; fi; }
assert_err()  { if err "$1"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2"; inc_fail; fi; }
assert_jrpc() { if jrpc "$1"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2"; inc_fail; fi; }
assert_jq()   { if jq_match "$1" "$3"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2"; inc_fail; fi; }
assert_txt()  { local tmp; tmp=$(mktemp); txt "$1" > "$tmp"; if grep -qF "$3" "$tmp"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2 — missing '$3'"; inc_fail; fi; rm -f "$tmp"; }

echo "=== 1. Basic JSON-RPC ==="

r=$(call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}')
assert_ok "$r" "initialize OK"
assert_jq "$r" "initialize serverName" '.result.serverInfo.name == "per-line-coverage-info"'
assert_jq "$r" "initialize protocolVersion" '.result.protocolVersion == "2025-03-26"'

r=$(call '{"jsonrpc":"2.0","id":2,"method":"ping"}')
assert_ok "$r" "ping"

r=$(call 'invalid json')
assert_jrpc "$r" "invalid JSON → parse error"

r=$(call '{"jsonrpc":"2.0","id":3,"method":"nonexistent"}')
assert_jrpc "$r" "unknown method → -32601"

echo
echo "=== 2. tools/list ==="

r=$(call '{"jsonrpc":"2.0","id":4,"method":"tools/list"}')
assert_ok "$r" "tools/list"
assert_jq "$r" "2 tools total" '.result.tools | length == 2'
assert_jq "$r" "tool: get_coverage_for_file" '.result.tools | map(.name) | contains(["get_coverage_for_file"])'
assert_jq "$r" "tool: list_files" '.result.tools | map(.name) | contains(["list_files"])'
assert_jq "$r" "get_coverage_for_file has project param" '.result.tools[] | select(.name=="get_coverage_for_file") | .inputSchema.required | contains(["project"])'
assert_jq "$r" "get_coverage_for_file has file_path param" '.result.tools[] | select(.name=="get_coverage_for_file") | .inputSchema.required | contains(["file_path"])'
assert_jq "$r" "list_files has project param" '.result.tools[] | select(.name=="list_files") | .inputSchema.required | contains(["project"])'
assert_jq "$r" "list_files has coverage param" '.result.tools[] | select(.name=="list_files") | .inputSchema.properties | has("coverage")'
assert_jq "$r" "coverage enum values" '.result.tools[] | select(.name=="list_files") | .inputSchema.properties.coverage.enum | contains(["all","uncovered","fully_covered"])'

echo
echo "=== 3. get_coverage_for_file ==="

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"detail\":\"summary\"}}}")
assert_ok "$r" "coverage summary for $FILE"
assert_txt "$r" "summary → Covered lines" "Covered:"
assert_txt "$r" "summary → Uncovered lines" "Uncovered:"
assert_jq "$r" "summary 1 content element" '.result.content | length == 1'

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"detail\":\"detailed\"}}}")
assert_ok "$r" "coverage detailed for $FILE"
assert_txt "$r" "detailed → per-line tests" "core/src/Features"

# default detail = summary
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":60,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\"}}}")
assert_ok "$r" "coverage default detail"

r=$(call '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_coverage_for_file","arguments":{"file_path":"foo.php"}}}')
assert_err "$r" "missing project → isError=true"
assert_txt "$r" "missing project → hint" "Missing required argument: project"

r=$(call '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"get_coverage_for_file","arguments":{"project":"'"$PROJECT"'"}}}')
assert_err "$r" "missing file_path → isError=true"
assert_txt "$r" "missing file_path → hint" "Missing required argument: file_path"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"nonexistent.php\"}}}")
assert_ok "$r" "nonexistent file → success (not error)"
assert_txt "$r" "nonexistent file → message" "No coverage found"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"detail\":\"invalid\"}}}")
assert_err "$r" "invalid detail value"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"/nonexistent\",\"file_path\":\"foo.php\"}}}")
assert_err "$r" "unknown project → isError=true"
assert_txt "$r" "unknown project → hint" "No project found for path"

echo
echo "=== 4. list_files ==="

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"sort\":\"coverage_asc\"}}}")
assert_ok "$r" "coverage_asc sort"
assert_txt "$r" "coverage_asc → 0%" "0%"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"sort\":\"name\"}}}")
assert_ok "$r" "name sort"
assert_txt "$r" "name sort → alpha" "AccessDeniedException.php"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/Avia/\",\"recursive\":true}}}")
assert_ok "$r" "recursive=true"
assert_txt "$r" "recursive → subdir paths" "Carrier/"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/Avia/\",\"recursive\":false}}}")
assert_ok "$r" "recursive=false"
tmp=$(mktemp); txt "$r" > "$tmp"
if grep -qP '^\s+\S+/\S+\.php' "$tmp"; then
    echo "  FAIL: recursive=false returned subdir files"; inc_fail
else
    echo "  PASS: recursive=false → no subdirs"; inc_pass
fi
rm -f "$tmp"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"\",\"sort\":\"name\"}}}")
assert_ok "$r" "root dir ''"
assert_txt "$r" "root → files" "core/src/"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"\",\"sort\":\"name\",\"offset\":50}}}")
assert_ok "$r" "offset=50"
assert_txt "$r" "offset → next hint" "Use offset=100"

r=$(call '{"jsonrpc":"2.0","id":18,"method":"tools/call","params":{"name":"list_files","arguments":{"project":"'"$PROJECT"'"}}}')
assert_err "$r" "missing path → isError=true"
assert_txt "$r" "missing path → hint" "Missing required argument: path"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"sort\":\"invalid\"}}}")
assert_err "$r" "invalid sort → isError=true"

echo
echo "=== 4b. list_files coverage filter ==="

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":30,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"coverage\":\"uncovered\",\"sort\":\"name\"}}}")
assert_ok "$r" "coverage=uncovered"
tmp=$(mktemp); txt "$r" > "$tmp"
if grep -oP '\d+%' "$tmp" | sed 's/%//' | awk '{if($1>0) exit 1}' ; then
    echo "  PASS: uncovered → all 0%"; inc_pass
else
    echo "  FAIL: uncovered returned files with coverage >0%"; inc_fail
fi
rm -f "$tmp"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"coverage\":\"fully_covered\",\"sort\":\"name\"}}}")
assert_ok "$r" "coverage=fully_covered"
tmp=$(mktemp); txt "$r" > "$tmp"
if grep -oP '\d+%' "$tmp" | sed 's/%//' | awk '{if($1<100) exit 1}' ; then
    echo "  PASS: fully_covered → all 100%"; inc_pass
else
    echo "  FAIL: fully_covered returned files <100%"; inc_fail
fi
rm -f "$tmp"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"sort\":\"name\"}}}")
assert_ok "$r" "coverage=all (default)"
tmp=$(mktemp); txt "$r" > "$tmp"
has0=$(grep -cF "0%" "$tmp" || true)
has100=$(grep -cF "100%" "$tmp" || true)
if [ "$has0" -gt 0 ] && [ "$has100" -gt 0 ]; then
    echo "  PASS: all → mixed coverage"; inc_pass
else
    echo "  FAIL: all should show 0% and 100% files"; inc_fail
fi
rm -f "$tmp"

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":33,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"coverage\":\"invalid\"}}}")
assert_err "$r" "coverage=invalid → isError=true"
assert_txt "$r" "coverage=invalid → hint" "coverage"

echo
echo "=== 5. Unknown tool ==="

r=$(call '{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"nonexistent_tool","arguments":{}}}')
assert_err "$r" "unknown tool → isError=true"

echo
echo "=== 6. Notifications ==="

r=$(call '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}')
if [ -z "$r" ]; then
    echo "  PASS: notification returns empty (no response)"; inc_pass
else
    echo "  FAIL: notification should be silent, got: $r"; inc_fail
fi

echo
echo "═════════════════════════════════════════════"
echo "  PASS: $PASS  FAIL: $FAIL  TOTAL: $((PASS + FAIL))"
echo "═════════════════════════════════════════════"
[ "$FAIL" -eq 0 ]
