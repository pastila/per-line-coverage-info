#!/usr/bin/env bash
set -euo pipefail

# Configuration
MCP_BASE="${MCP_BASE:-http://127.0.0.1:64343}"
PROJECT="${PROJECT:-$HOME/projects/raketa}"
PASS=0
FAIL=0
FILE="core/src/Avia/Aircompany.php"

# Temp files for SSE transport
SSE_FILE=$(mktemp)
SSE_PID=""

# Cleanup on exit
cleanup() {
    [ -n "$SSE_PID" ] && kill "$SSE_PID" 2>/dev/null || true
    wait "$SSE_PID" 2>/dev/null || true
    rm -f "$SSE_FILE"
}
trap cleanup EXIT

# Initialize SSE session: start listening and extract the session endpoint URL
init_sse() {
    curl -sN "$MCP_BASE/sse" > "$SSE_FILE" &
    SSE_PID=$!

    local timeout=5
    while [ "$timeout" -gt 0 ]; do
        SESSION_URL=$(grep "^data:" "$SSE_FILE" 2>/dev/null | head -1 | sed 's/^data: //' | tr -d '\r' || true)
        if [ -n "$SESSION_URL" ]; then
            echo "SSE session: $SESSION_URL" >&2
            return 0
        fi
        sleep 0.2
        timeout=$((timeout - 1))
    done
    echo "ERROR: Failed to get SSE session from $MCP_BASE" >&2
    exit 1
}

# Send a JSON-RPC call and wait for the matching response by ID
call() {
    local request="$1"
    local req_id
    req_id=$(echo "$request" | jq -r '.id')

    curl -s "$MCP_BASE$SESSION_URL" \
        -H "Content-Type: application/json" \
        -d "$request" > /dev/null 2>&1

    local timeout=10
    while [ "$timeout" -gt 0 ]; do
        local response
        response=$(grep "^data:" "$SSE_FILE" | sed 's/^data: //' | tr -d '\r' | \
            grep '^{' | jq -c "select(.id == $req_id)" 2>/dev/null || true)
        if [ -n "$response" ]; then
            echo "$response"
            return 0
        fi
        sleep 0.3
        timeout=$((timeout - 1))
    done
    echo '{}'
}

# Checks
ok()    { echo "$1" | jq -e '.error == null and .result.isError != true' >/dev/null 2>&1; }
err()   { echo "$1" | jq -e '(.error != null) or (.result.isError == true)' >/dev/null 2>&1; }
jq_match() { echo "$1" | jq -e "$2" >/dev/null 2>&1; }

inc_pass() { PASS=$((PASS + 1)); }
inc_fail() { FAIL=$((FAIL + 1)); }

assert_ok()   { if ok "$1"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2 — $(echo "$1" | jq -c '{id, isError: .result.isError}' 2>/dev/null)"; inc_fail; fi; }
assert_err()  { if err "$1"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2 — $(echo "$1" | jq -c '{id}')"; inc_fail; fi; }
assert_jq()   { if jq_match "$1" "$3"; then echo "  PASS: $2"; inc_pass; else echo "  FAIL: $2"; inc_fail; fi; }

echo "=== 0. Init SSE session ==="
init_sse
echo "  OK: SSE session established ($SESSION_URL)"

echo
echo "=== 1. Basic JSON-RPC ==="

r=$(call '{"jsonrpc":"2.0","id":1,"method":"ping"}')
assert_ok "$r" "ping"

echo
echo "=== 2. tools/list ==="

r=$(call '{"jsonrpc":"2.0","id":4,"method":"tools/list"}')
assert_ok "$r" "tools/list"
TOOL_COUNT=$(echo "$r" | jq '.result.tools | length')
echo "  Tools available: $TOOL_COUNT"
for tool in $(echo "$r" | jq -r '.result.tools[].name'); do
    echo "    - $tool"
done
assert_jq "$r" "tool: get_coverage_for_file" '.result.tools | map(.name) | contains(["get_coverage_for_file"])'
assert_jq "$r" "tool: list_files" '.result.tools | map(.name) | contains(["list_files"])'
assert_jq "$r" "tool: get_tests_at_line" '.result.tools | map(.name) | contains(["get_tests_at_line"])'
assert_jq "$r" "tool: get_first_tests_at_lines" '.result.tools | map(.name) | contains(["get_first_tests_at_lines"])'
echo
echo "=== 3. get_coverage_for_file ==="

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\"}}}")
assert_ok "$r" "default params for $FILE"
assert_jq "$r" "result has coveredLinesInFile" '.result.content[0].text | fromjson | has("coveredLinesInFile")'
assert_jq "$r" "result has lines array" '.result.content[0].text | fromjson | has("lines")'

# coverage=uncovered — all lines must be !isCovered
ru=$(call "{\"jsonrpc\":\"2.0\",\"id\":52,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"coverage\":\"uncovered\"}}}")
assert_ok "$ru" "coverage=uncovered"
assert_jq "$ru" "uncovered → no covered lines" '[.result.content[0].text | fromjson | .lines[] | .isCovered] | all(. == false)'

# coverage=covered — all lines must be isCovered
rc=$(call "{\"jsonrpc\":\"2.0\",\"id\":53,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"coverage\":\"covered\"}}}")
assert_ok "$rc" "coverage=covered"
assert_jq "$rc" "covered → all lines are covered" '[.result.content[0].text | fromjson | .lines[] | .isCovered] | all(. == true)'

# offset and limit
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":55,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"offset\":10,\"limit\":5}}}")
assert_ok "$r" "offset=10 limit=5"
assert_jq "$r" "offset+limit → 5 lines" '([.result.content[0].text | fromjson | .lines | length] | .[0]) == 5'
assert_jq "$r" "offset+limit → starts at line 11" '[.result.content[0].text | fromjson | .lines[0].lineNumber] | .[0] == 11'

# Validation: offset < 0
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":56,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"offset\":-1}}}")
assert_err "$r" "offset=-1 → error"

# Validation: limit > 1000
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":57,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"limit\":1001}}}")
assert_err "$r" "limit=1001 → error"

# Validation: limit < 1
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":58,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"limit\":0}}}")
assert_err "$r" "limit=0 → error"

# Validation: invalid coverage value
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":59,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"coverage\":\"invalid\"}}}")
assert_err "$r" "coverage=invalid → error"

# Unknown project path
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":60,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"/nonexistent\",\"file_path\":\"foo.php\"}}}")
assert_err "$r" "unknown project → error"

# Validation: missing file_path
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":61,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\"}}}")
assert_err "$r" "missing file_path → error"

# Nonexistent file
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"get_coverage_for_file\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"nonexistent.php\"}}}")
assert_err "$r" "nonexistent file → error"

echo
echo "=== 3b. get_tests_at_line ==="

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":40,\"method\":\"tools/call\",\"params\":{\"name\":\"get_tests_at_line\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_number\":1}}}")
assert_ok "$r" "default params for line 1"
assert_jq "$r" "result is valid JSON" '.result.content[0].text | fromjson | has("lineNumber")'

# Pagination: offset=0, limit=2
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":41,\"method\":\"tools/call\",\"params\":{\"name\":\"get_tests_at_line\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_number\":50,\"offset\":0,\"limit\":2}}}")
assert_ok "$r" "line 50 offset=0 limit=2"
assert_jq "$r" "limit=2 → ≤2 tests" '([.result.content[0].text | fromjson | .tests | length] | .[0]) <= 2'

# Pagination: limit beyond available tests
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/call\",\"params\":{\"name\":\"get_tests_at_line\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_number\":50,\"limit\":100}}}")
assert_ok "$r" "line 50 limit=100 (max)"
assert_jq "$r" "limit=max → ≤100 tests" '([.result.content[0].text | fromjson | .tests | length] | .[0]) <= 100'

# Validation: line_number < 1
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":43,\"method\":\"tools/call\",\"params\":{\"name\":\"get_tests_at_line\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_number\":0}}}")
assert_err "$r" "line_number=0 → error"

# Validation: line_number missing
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":44,\"method\":\"tools/call\",\"params\":{\"name\":\"get_tests_at_line\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\"}}}")
assert_err "$r" "missing line_number → error"

echo
echo "=== 3c. get_first_tests_at_lines ==="

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":45,\"method\":\"tools/call\",\"params\":{\"name\":\"get_first_tests_at_lines\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_numbers\":\"1,5,10,20\"}}}")
assert_ok "$r" "lines 1,5,10,20"
assert_jq "$r" "4 lines returned" '([.result.content[0].text | fromjson | .lines | length] | .[0]) == 4'

# Single line
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":46,\"method\":\"tools/call\",\"params\":{\"name\":\"get_first_tests_at_lines\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_numbers\":\"5\"}}}")
assert_ok "$r" "single line 5"
assert_jq "$r" "single → 1 line" '([.result.content[0].text | fromjson | .lines | length] | .[0]) == 1'

# Validation: empty line_numbers
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":47,\"method\":\"tools/call\",\"params\":{\"name\":\"get_first_tests_at_lines\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_numbers\":\"\"}}}")
assert_err "$r" "empty line_numbers → error"

# Validation: invalid chars in line_numbers
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":48,\"method\":\"tools/call\",\"params\":{\"name\":\"get_first_tests_at_lines\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\",\"line_numbers\":\"abc,def\"}}}")
assert_err "$r" "invalid line_numbers → error"

# Validation: missing line_numbers
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":49,\"method\":\"tools/call\",\"params\":{\"name\":\"get_first_tests_at_lines\",\"arguments\":{\"project\":\"$PROJECT\",\"file_path\":\"$FILE\"}}}")
assert_err "$r" "missing line_numbers → error"

echo
echo "=== 4. list_files ==="

r=$(call "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"sort\":\"coverage_asc\"}}}")
assert_ok "$r" "coverage_asc sort"
assert_jq "$r" "result has files" '.result.content[0].text | fromjson | has("files")'
assert_jq "$r" "first file has 0%" '([.result.content[0].text | fromjson | .files[0].coveragePercent] | .[0]) == 0'

# sort=name
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"sort\":\"name\"}}}")
assert_ok "$r" "sort=name"
assert_jq "$r" "name sort → AccessDeniedException" '[.result.content[0].text | fromjson | .files[0].path] | .[0] | test("AccessDeniedException")'

# recursive=true
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":14,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/Avia/\",\"recursive\":true}}}")
assert_ok "$r" "recursive=true"
assert_jq "$r" "recursive → has subdir file" '[.result.content[0].text | fromjson | .files[] | .path] | any(test("/"))'

# recursive=false — no subdir files
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":15,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/Avia/\",\"recursive\":false}}}")
assert_ok "$r" "recursive=false"
assert_jq "$r" "non-recursive → returns files" '([.result.content[0].text | fromjson | .files | length] | .[0]) > 0'

# offset pagination
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":16,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"offset\":25,\"sort\":\"name\"}}}")
assert_ok "$r" "offset=25"
assert_jq "$r" "offset → returns files" '([.result.content[0].text | fromjson | .files | length] | .[0]) > 0'

# coverage=uncovered
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":17,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"coverage\":\"uncovered\",\"sort\":\"name\"}}}")
assert_ok "$r" "coverage=uncovered"
assert_jq "$r" "uncovered → all 0%" '[.result.content[0].text | fromjson | .files[] | .coveragePercent] | all(. == 0)'

# coverage=fully_covered
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":18,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"coverage\":\"fully_covered\",\"sort\":\"name\"}}}")
assert_ok "$r" "coverage=fully_covered"
assert_jq "$r" "fully_covered → all 100%" '[.result.content[0].text | fromjson | .files[] | .coveragePercent] | all(. == 100)'

# Validation: coverage=invalid
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"coverage\":\"invalid\"}}}")
assert_err "$r" "coverage=invalid → error"

# Validation: missing path
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\"}}}")
assert_err "$r" "missing path → error"

# Validation: invalid sort
r=$(call "{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\",\"params\":{\"name\":\"list_files\",\"arguments\":{\"project\":\"$PROJECT\",\"path\":\"core/src/\",\"sort\":\"invalid\"}}}")
assert_err "$r" "invalid sort → error"

echo
echo "=== 5. Unknown tool ==="

r=$(call '{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"nonexistent_tool","arguments":{}}}')
assert_err "$r" "unknown tool → error"

echo
echo "═════════════════════════════════════════════"
echo "  PASS: $PASS  FAIL: $FAIL  TOTAL: $((PASS + FAIL))"
echo "═════════════════════════════════════════════"
[ "$FAIL" -eq 0 ]
