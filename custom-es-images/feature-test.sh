#!/usr/bin/env bash
# feature-test.sh — Comprehensive feature testing for custom ES images
# Tests: cluster info, CRUD, bulk, search, mappings, aliases, snapshots, reindex, ingest
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSIONS_FILE="${SCRIPT_DIR}/versions.json"
IMAGE_PREFIX="${IMAGE_PREFIX:-custom-elasticsearch}"
CONTAINER_PREFIX="es-feat-test"
TIMEOUT="${TIMEOUT:-120}"
RESULTS_DIR="${SCRIPT_DIR}/test-results"
mkdir -p "$RESULTS_DIR"

# Colors
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[0;33m'; NC='\033[0m'

PARALLEL=0
LOGS_DIR="${RESULTS_DIR}/logs"

usage() {
  echo "Usage: $0 [OPTIONS] [MINOR_VERSION...]"
  echo "  -a, --all         Test all versions"
  echo "  -m, --major N     Test all minor versions for major N"
  echo "  -p, --parallel N  Run N tests concurrently (default: sequential)"
  echo "  -h, --help        Show this help"
  exit 0
}

cleanup() {
  local name="$1"
  docker rm -f "$name" &>/dev/null || true
}

wait_for_es() {
  local port="$1" elapsed=0
  while [ $elapsed -lt "$TIMEOUT" ]; do
    if curl -sf "http://localhost:${port}/_cluster/health" &>/dev/null; then
      return 0
    fi
    sleep 2; elapsed=$((elapsed + 2))
  done
  return 1
}

# Run a curl test, return 0 on success (retries on connection failure)
run_test() {
  local name="$1" port="$2" method="$3" path="$4" expected_code="$5"
  shift 5
  local body="${1:-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$method" --max-time 30)
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local code retries=3
  for ((r=0; r<retries; r++)); do
    code=$(curl "${args[@]}" "http://localhost:${port}${path}" 2>/dev/null) || code="000"
    [ "$code" != "000" ] && break
    sleep 2
  done
  if [ "$code" = "$expected_code" ]; then
    echo -e "  ${GREEN}✅${NC} ${name}: ${code}"
    return 0
  else
    echo -e "  ${RED}❌${NC} ${name}: expected ${expected_code}, got ${code}"
    return 1
  fi
}

# Run a curl test that checks response body contains a string
run_test_body() {
  local name="$1" port="$2" method="$3" path="$4" expected_str="$5"
  shift 5
  local body="${1:-}"
  local args=(-s -X "$method" --max-time 30)
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
  local resp retries=3
  for ((r=0; r<retries; r++)); do
    resp=$(curl "${args[@]}" "http://localhost:${port}${path}" 2>/dev/null) || resp=""
    [ -n "$resp" ] && break
    sleep 2
  done
  if echo "$resp" | grep -q "$expected_str"; then
    echo -e "  ${GREEN}✅${NC} ${name}"
    return 0
  else
    echo -e "  ${RED}❌${NC} ${name}: expected '${expected_str}' in response"
    return 1
  fi
}

test_version() {
  local minor="$1"
  local version major minor_num
  version=$(python3 -c "import json; data=json.load(open('${VERSIONS_FILE}')); print(data['${minor}']['version'])")
  major=$(echo "$version" | cut -d. -f1)
  minor_num=$(echo "$version" | cut -d. -f2)

  local tag="${IMAGE_PREFIX}:${version}"
  local container="${CONTAINER_PREFIX}-${version}"
  local port
  port=$(python3 -c "m,n='${minor}'.split('.'); print(9200+int(m)*100+int(n))")

  echo ""
  echo "================================================================"
  echo "  Testing ES ${version} (${minor}) on port ${port}"
  echo "================================================================"

  cleanup "$container"

  # Build run args
  local java_mem="-Xms512m -Xmx512m"
  [ "$major" -le 2 ] && java_mem="-Xms256m -Xmx256m"

  docker run -d --name "$container" -p "${port}:9200" \
    -e "ES_JAVA_OPTS=${java_mem}" \
    "$tag" >/dev/null 2>&1

  if ! wait_for_es "$port"; then
    echo -e "  ${RED}❌ TIMEOUT — failed to start${NC}"
    docker logs --tail 10 "$container" 2>&1 | sed 's/^/    /'
    cleanup "$container"
    echo "${version}|TIMEOUT" >> "${RESULTS_DIR}/results.csv"
    return 1
  fi

  local passed=0 failed=0 total=0

  # Helper to track pass/fail
  t() {
    total=$((total + 1))
    if "$@"; then passed=$((passed + 1)); else failed=$((failed + 1)); fi
  }

  # === Core Tests (all versions) ===
  echo "  --- Core ---"
  t run_test "GET /" "$port" GET "/" "200"
  t run_test "Cluster health" "$port" GET "/_cluster/health" "200"
  t run_test "Cluster settings" "$port" GET "/_cluster/settings" "200"
  t run_test "Cat indices" "$port" GET "/_cat/indices" "200"
  t run_test "Cat nodes" "$port" GET "/_cat/nodes" "200"

  # === Index + Mappings ===
  echo "  --- Index & Mappings ---"
  local text_type="text"
  [ "$major" -le 2 ] && text_type="string"

  if [ "$major" -le 6 ]; then
    # Typed API
    t run_test "Create index" "$port" PUT "/test-index" "200" \
      "{\"settings\":{\"number_of_shards\":1,\"number_of_replicas\":0},\"mappings\":{\"doc\":{\"properties\":{\"title\":{\"type\":\"${text_type}\"},\"count\":{\"type\":\"integer\"}}}}}"
    t run_test "Get mappings" "$port" GET "/test-index/_mapping" "200"
    # CRUD with type
    echo "  --- CRUD ---"
    t run_test "Index doc" "$port" PUT "/test-index/doc/1" "201" \
      '{"title":"hello world","count":42}'
    t run_test "Get doc" "$port" GET "/test-index/doc/1" "200"
    t run_test "Update doc" "$port" POST "/test-index/doc/1/_update" "200" \
      '{"doc":{"count":43}}'
    t run_test "Delete doc" "$port" DELETE "/test-index/doc/1" "200"
    # Bulk with type
    echo "  --- Bulk ---"
    t run_test "Bulk index" "$port" POST "/_bulk" "200" \
      '{"index":{"_index":"test-index","_type":"doc","_id":"b1"}}
{"title":"bulk doc 1","count":1}
{"index":{"_index":"test-index","_type":"doc","_id":"b2"}}
{"title":"bulk doc 2","count":2}
'
  else
    # Typeless API (7.x+)
    t run_test "Create index" "$port" PUT "/test-index" "200" \
      '{"settings":{"number_of_shards":1,"number_of_replicas":0},"mappings":{"properties":{"title":{"type":"text"},"count":{"type":"integer"}}}}'
    t run_test "Get mappings" "$port" GET "/test-index/_mapping" "200"
    echo "  --- CRUD ---"
    t run_test "Index doc" "$port" PUT "/test-index/_doc/1" "201" \
      '{"title":"hello world","count":42}'
    t run_test "Get doc" "$port" GET "/test-index/_doc/1" "200"
    t run_test "Update doc" "$port" POST "/test-index/_update/1" "200" \
      '{"doc":{"count":43}}'
    t run_test "Delete doc" "$port" DELETE "/test-index/_doc/1" "200"
    echo "  --- Bulk ---"
    t run_test "Bulk index" "$port" POST "/_bulk" "200" \
      '{"index":{"_index":"test-index","_id":"b1"}}
{"title":"bulk doc 1","count":1}
{"index":{"_index":"test-index","_id":"b2"}}
{"title":"bulk doc 2","count":2}
'
  fi

  # Force refresh for search
  curl -sf -X POST "http://localhost:${port}/test-index/_refresh" >/dev/null 2>&1 || true

  # === Search ===
  echo "  --- Search ---"
  t run_test_body "Match all" "$port" POST "/test-index/_search" '"hits"' \
    '{"query":{"match_all":{}}}'
  t run_test_body "Term query" "$port" POST "/test-index/_search" '"hits"' \
    '{"query":{"term":{"count":1}}}'

  # === Aliases ===
  echo "  --- Aliases ---"
  t run_test "Create alias" "$port" POST "/_aliases" "200" \
    '{"actions":[{"add":{"index":"test-index","alias":"test-alias"}}]}'
  t run_test "Query via alias" "$port" GET "/test-alias/_search" "200"
  t run_test "Remove alias" "$port" POST "/_aliases" "200" \
    '{"actions":[{"remove":{"index":"test-index","alias":"test-alias"}}]}'

  # === Snapshot (filesystem) ===
  echo "  --- Snapshots ---"
  # Register fs repo — path must be writable
  local snap_ok=false snap_resp=""
  local snap_retries=3
  for ((sr=0; sr<snap_retries; sr++)); do
    snap_resp=$(curl -s --max-time 30 -X PUT "http://localhost:${port}/_snapshot/test-repo" \
      -H 'Content-Type: application/json' \
      -d '{"type":"fs","settings":{"location":"/tmp/es-snapshots"}}' 2>/dev/null) || snap_resp=""
    if echo "$snap_resp" | grep -q '"acknowledged":true'; then
      snap_ok=true; break
    fi
    [ -n "$snap_resp" ] && break  # got a response, just not acknowledged
    sleep 2
  done

  if $snap_ok; then
    t run_test "Create snapshot" "$port" PUT "/_snapshot/test-repo/snap1?wait_for_completion=true" "200"
    t run_test "List snapshots" "$port" GET "/_snapshot/test-repo/_all" "200"
    t run_test "Delete snapshot" "$port" DELETE "/_snapshot/test-repo/snap1" "200"
  else
    echo -e "  ${YELLOW}⚠️${NC}  Snapshot repo: path.repo not configured (expected — needs Dockerfile change)"
    total=$((total + 1)); failed=$((failed + 1))
  fi

  # === Reindex (5.x+) ===
  if [ "$major" -ge 5 ]; then
    echo "  --- Reindex ---"
    t run_test "Reindex" "$port" POST "/_reindex" "200" \
      '{"source":{"index":"test-index"},"dest":{"index":"test-index-copy"}}'
    t run_test "Verify reindex" "$port" GET "/test-index-copy/_search" "200"
  fi

  # === Ingest Pipelines (5.x+) ===
  if [ "$major" -ge 5 ]; then
    echo "  --- Ingest ---"
    t run_test "Create pipeline" "$port" PUT "/_ingest/pipeline/test-pipe" "200" \
      '{"description":"test","processors":[{"set":{"field":"added_field","value":"hello"}}]}'
    t run_test "Get pipeline" "$port" GET "/_ingest/pipeline/test-pipe" "200"
    t run_test "Delete pipeline" "$port" DELETE "/_ingest/pipeline/test-pipe" "200"
  fi

  # === Index Templates ===
  echo "  --- Templates ---"
  if [ "$major" -le 2 ]; then
    # ES 1.x-2.x: uses "template" (singular) and "string" type
    t run_test "Create template" "$port" PUT "/_template/test-tmpl" "200" \
      '{"template":"tmpl-*","settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"name":{"type":"string"}}}}}'
    t run_test "Get template" "$port" GET "/_template/test-tmpl" "200"
    t run_test "Delete template" "$port" DELETE "/_template/test-tmpl" "200"
  elif [ "$major" -eq 5 ]; then
    # ES 5.x: uses "template" (singular) and "text" type
    t run_test "Create template" "$port" PUT "/_template/test-tmpl" "200" \
      '{"template":"tmpl-*","settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"name":{"type":"text"}}}}}'
    t run_test "Get template" "$port" GET "/_template/test-tmpl" "200"
    t run_test "Delete template" "$port" DELETE "/_template/test-tmpl" "200"
  elif [ "$major" -eq 6 ]; then
    t run_test "Create template" "$port" PUT "/_template/test-tmpl" "200" \
      '{"index_patterns":["tmpl-*"],"settings":{"number_of_shards":1},"mappings":{"doc":{"properties":{"name":{"type":"text"}}}}}'
    t run_test "Get template" "$port" GET "/_template/test-tmpl" "200"
    t run_test "Delete template" "$port" DELETE "/_template/test-tmpl" "200"
  elif [ "$major" -eq 7 ]; then
    t run_test "Create template" "$port" PUT "/_template/test-tmpl" "200" \
      '{"index_patterns":["tmpl-*"],"settings":{"number_of_shards":1},"mappings":{"properties":{"name":{"type":"text"}}}}'
    t run_test "Get template" "$port" GET "/_template/test-tmpl" "200"
    t run_test "Delete template" "$port" DELETE "/_template/test-tmpl" "200"
  else
    # ES 8.x: use composable index templates
    t run_test "Create index template" "$port" PUT "/_index_template/test-tmpl" "200" \
      '{"index_patterns":["tmpl-*"],"template":{"settings":{"number_of_shards":1},"mappings":{"properties":{"name":{"type":"text"}}}}}'
    t run_test "Get index template" "$port" GET "/_index_template/test-tmpl" "200"
    t run_test "Delete index template" "$port" DELETE "/_index_template/test-tmpl" "200"
  fi

  # === Cluster APIs ===
  echo "  --- Cluster APIs ---"
  t run_test "Cat shards" "$port" GET "/_cat/shards" "200"
  t run_test "Cat health" "$port" GET "/_cat/health" "200"
  t run_test "Nodes info" "$port" GET "/_nodes" "200"
  t run_test "Nodes stats" "$port" GET "/_nodes/stats" "200"

  # === Check installed plugins ===
  echo "  --- Plugins ---"
  local plugins
  plugins=$(curl -sf "http://localhost:${port}/_cat/plugins" 2>/dev/null) || plugins=""
  if [ -n "$plugins" ]; then
    echo "  Installed plugins:"
    echo "$plugins" | sed 's/^/    /'
  else
    echo -e "  ${YELLOW}ℹ️${NC}  No plugins installed"
  fi

  # Cleanup
  t run_test "Delete test index" "$port" DELETE "/test-index" "200"
  [ "$major" -ge 5 ] && curl -sf -X DELETE "http://localhost:${port}/test-index-copy" >/dev/null 2>&1 || true

  cleanup "$container"

  # Results
  echo ""
  echo -e "  Results: ${GREEN}${passed}${NC}/${total} passed, ${RED}${failed}${NC} failed"
  echo "${version}|${passed}|${failed}|${total}" >> "${RESULTS_DIR}/results.csv"

  [ "$failed" -eq 0 ] && return 0 || return 1
}

# Parse arguments
VERSIONS=()
[ $# -eq 0 ] && usage
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage ;;
    -a|--all)
      mapfile -t VERSIONS < <(python3 -c "
import json
with open('${VERSIONS_FILE}') as f:
    data = json.load(f)
for k in sorted(data.keys(), key=lambda x: [int(p) for p in x.split('.')]):
    print(k)")
      shift ;;
    -m|--major)
      shift; M="$1"
      mapfile -t mv < <(python3 -c "
import json
with open('${VERSIONS_FILE}') as f:
    data = json.load(f)
for k in sorted(data.keys(), key=lambda x: [int(p) for p in x.split('.')]):
    if k.startswith('${M}.'): print(k)")
      VERSIONS+=("${mv[@]}")
      shift ;;
    -p|--parallel) shift; PARALLEL="$1"; shift ;;
    *) VERSIONS+=("$1"); shift ;;
  esac
done

# Clear results
echo "version|passed|failed|total" > "${RESULTS_DIR}/results.csv"
mkdir -p "$LOGS_DIR"

TOTAL=${#VERSIONS[@]}

if [ "$PARALLEL" -gt 0 ]; then
  echo "Feature testing ${TOTAL} image(s) with parallelism=${PARALLEL}..."
  PIDS=()
  VER_FOR_PID=()

  for i in "${!VERSIONS[@]}"; do
    v="${VERSIONS[$i]}"
    log="${LOGS_DIR}/${v}.log"
    # Run test_version in a subshell, output to log file
    ( test_version "$v" ) > "$log" 2>&1 &
    PIDS+=($!)
    VER_FOR_PID+=("$v")

    # Throttle: when we hit PARALLEL running jobs, wait for one to finish
    while [ "$(jobs -rp | wc -l)" -ge "$PARALLEL" ]; do
      sleep 1
    done
  done

  # Wait for all remaining jobs and collect results
  PASS_LIST=(); FAIL_LIST=()
  for i in "${!PIDS[@]}"; do
    if wait "${PIDS[$i]}" 2>/dev/null; then
      PASS_LIST+=("${VER_FOR_PID[$i]}")
    else
      FAIL_LIST+=("${VER_FOR_PID[$i]}")
    fi
  done

  # Print all logs in version order
  for v in "${VERSIONS[@]}"; do
    log="${LOGS_DIR}/${v}.log"
    [ -f "$log" ] && cat "$log"
  done
else
  echo "Feature testing ${TOTAL} image(s)..."
  PASS_LIST=(); FAIL_LIST=()
  for i in "${!VERSIONS[@]}"; do
    echo ""
    echo "[$(( i + 1 ))/${TOTAL}]"
    if test_version "${VERSIONS[$i]}"; then
      PASS_LIST+=("${VERSIONS[$i]}")
    else
      FAIL_LIST+=("${VERSIONS[$i]}")
    fi
  done
fi

echo ""
echo "========================================"
echo "Feature test results: ${#PASS_LIST[@]}/${TOTAL} fully passed"
[ ${#FAIL_LIST[@]} -gt 0 ] && echo "Partial failures: ${FAIL_LIST[*]}"
echo "Detailed results: ${RESULTS_DIR}/results.csv"
