#!/usr/bin/env bash
# validate_phase_3.sh
#
# Runs the Phase 3 validation checks against a live stack.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# Usage:
#   ./scripts/validate_phase_3.sh               # checks only (stack + k6 must already be done)
#   ./scripts/validate_phase_3.sh --with-setup  # also starts the stack and runs k6 first
#   ./scripts/validate_phase_3.sh --teardown    # also tears down the stack when finished
#   ./scripts/validate_phase_3.sh --with-setup --teardown
#   ./scripts/validate_phase_3.sh --with-setup --deep-paging  # run deep-paging config instead
#
# Note: Step 9 (Replayer memory growth) requires the Traffic Replayer and is guidance only.

set -euo pipefail

# ── Helpers ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'

PASS=0; FAIL=0

pass() { echo -e "  ${GREEN}✓ PASS${NC}  $1"; (( PASS++ )) || true; }
fail() { echo -e "  ${RED}✗ FAIL${NC}  $1"; (( FAIL++ )) || true; }
info() { echo -e "  ${YELLOW}ℹ${NC}      $1"; }
header() { echo -e "\n${BOLD}$1${NC}"; }

prom_query() {
  curl -sf "http://localhost:19090/api/v1/query" \
    --data-urlencode "query=$1" 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); r=d['data']['result']; print(r[0]['value'][1] if r else '')" 2>/dev/null \
    || true
}

# ── Argument parsing ───────────────────────────────────────────────────────────
WITH_SETUP=false
WITH_TEARDOWN=false
DEEP_PAGING=false
for arg in "$@"; do
  case $arg in
    --with-setup)   WITH_SETUP=true ;;
    --teardown)     WITH_TEARDOWN=true ;;
    --deep-paging)  DEEP_PAGING=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "\n${BOLD}Phase 3 Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"
if $DEEP_PAGING; then
  echo -e "  Mode: ${YELLOW}deep-paging${NC} (scroll sequences enabled)"
else
  echo -e "  Mode: steady search (DEEP_PAGING_ENABLED=false)"
fi

# ── Step 1: capture proxy image check / build ──────────────────────────────────
header "Step 1 — Capture Proxy image"

CAPTURE_IMAGE="migrations/capture_proxy:latest"
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_SOLUTION="$PROJECT_ROOT/TrafficCapture/dockerSolution"

if docker image inspect "$CAPTURE_IMAGE" &>/dev/null; then
  pass "$CAPTURE_IMAGE found locally"
else
  if $WITH_SETUP; then
    info "$CAPTURE_IMAGE not found — building via buildDockerImages (this may take a few minutes)"
    (cd "$DOCKER_SOLUTION" && "$PROJECT_ROOT/gradlew" buildDockerImages)
    if docker image inspect "$CAPTURE_IMAGE" &>/dev/null; then
      pass "$CAPTURE_IMAGE built successfully"
    else
      fail "$CAPTURE_IMAGE still not found after build — check Gradle output above"
      exit 1
    fi
  else
    fail "$CAPTURE_IMAGE not found locally"
    echo -e "       Build it with:\n         cd TrafficCapture/dockerSolution && ../../../gradlew buildDockerImages"
    echo -e "       Or re-run this script with --with-setup to build automatically."
    exit 1
  fi
fi

# ── Step 2: optional stack startup + k6 run ────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Resetting and starting stack"
  # Tear down first so the nyc_taxis index is always recreated with the correct mapping.
  # Without this, a stale index from a previous run (possibly with dynamic/wrong mapping)
  # would cause all aggregation queries to return 400 — see known-gotchas.md #11.
  docker compose down -v 2>/dev/null || true
  docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
  echo ""

  # Build -e flags from env file (docker compose run does not support --env-file)
  if $DEEP_PAGING; then
    ENV_FILE="k6-config/search-deep-paging.env"
  else
    ENV_FILE="k6-config/search-steady.env"
  fi

  header "Step 5 — Running k6 search scenario (env: $ENV_FILE)"
  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < "$ENV_FILE"
  docker compose run --rm "${env_flags[@]}" \
    k6 run --out=experimental-prometheus-rw /scripts/scenarios/search.js
  echo ""
else
  info "Skipping stack startup and k6 run (use --with-setup to include those steps)."
fi

# ── Step 3: service health ─────────────────────────────────────────────────────
header "Step 3 — Service health"

for svc in kafka opensearch-source capture-proxy; do
  status=$(docker compose ps --format json "$svc" 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health','unknown'))" 2>/dev/null \
    || docker inspect --format '{{.State.Health.Status}}' \
       "$(docker compose ps -q "$svc" 2>/dev/null)" 2>/dev/null \
    || echo "unknown")
  if [[ "$status" == "healthy" ]]; then
    pass "$svc is healthy"
  else
    fail "$svc is not healthy (status: $status)"
  fi
done

# ── Step 4: Kafka topic exists and has messages ────────────────────────────────
header "Step 4 — Kafka topic and message count"

if docker compose exec -T kafka \
     /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null \
   | grep -q "logging-traffic-topic"; then
  pass "logging-traffic-topic exists"
else
  fail "logging-traffic-topic not found"
fi

offset_output=$(docker compose exec -T kafka \
  /opt/kafka/bin/kafka-get-offsets.sh \
  --bootstrap-server localhost:9092 \
  --topic logging-traffic-topic 2>/dev/null || true)

total_msgs=0
while IFS= read -r line; do
  count=$(echo "$line" | awk -F: '{print $3}' | tr -d '[:space:]')
  if [[ "$count" =~ ^[0-9]+$ ]]; then
    (( total_msgs += count )) || true
  fi
done <<< "$offset_output"

if (( total_msgs > 0 )); then
  pass "Kafka end offset: $total_msgs messages across all partitions"
else
  fail "Kafka end offset is 0 — no messages written to logging-traffic-topic"
fi

# ── Step 5: OpenSearch has documents ──────────────────────────────────────────
header "Step 5 — OpenSearch index"

doc_count=$(curl -sf "http://localhost:19200/nyc_taxis/_count" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
  || echo 0)

if (( doc_count > 0 )); then
  pass "nyc_taxis index contains $doc_count documents"
else
  fail "nyc_taxis index has 0 documents — seed the index before running the search scenario"
fi

# ── Step 6: No leaked scroll contexts ─────────────────────────────────────────
# Open scroll contexts accumulate if scroll_close requests fail.
# After k6 exits, the count should be 0 (or very low — scroll TTL is 1m so
# any leaked contexts expire within a minute of the test ending).
header "Step 6 — Scroll context leak check"

open_contexts=$(curl -sf "http://localhost:19200/_nodes/stats/indices" 2>/dev/null \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
total = sum(n['indices']['search']['open_contexts'] for n in d['nodes'].values())
print(total)
" 2>/dev/null || echo "?")

if [[ "$open_contexts" == "0" ]]; then
  pass "0 open scroll contexts — no leaks"
elif [[ "$open_contexts" =~ ^[0-9]+$ ]] && (( open_contexts <= 3 )); then
  pass "Negligible open scroll contexts ($open_contexts — within scroll TTL tolerance)"
elif [[ "$open_contexts" =~ ^[0-9]+$ ]]; then
  fail "Open scroll contexts: $open_contexts — scroll_close may have failed (check search_deep_paging_errors_rate)"
else
  info "Could not query open scroll context count: $open_contexts"
fi

# ── Step 7: Prometheus — search metrics ────────────────────────────────────────
header "Step 7 — Prometheus: search metrics"

flat_total=$(prom_query "k6_search_flat_requests_total")
if [[ -n "$flat_total" && "$flat_total" != "0" ]]; then
  pass "k6_search_flat_requests_total = $flat_total"
else
  fail "k6_search_flat_requests_total not found or is 0 — flat searches may not have run"
fi

agg_total=$(prom_query "k6_search_agg_requests_total")
if [[ -n "$agg_total" && "$agg_total" != "0" ]]; then
  pass "k6_search_agg_requests_total = $agg_total"
else
  fail "k6_search_agg_requests_total not found or is 0 — aggregation searches may not have run"
fi

search_err_rate=$(prom_query "k6_search_errors_rate")
if [[ -n "$search_err_rate" ]]; then
  search_err_pct=$(python3 -c "print(f'{float(\"$search_err_rate\")*100:.2f}')" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$search_err_rate') < 0.05 else 1)" 2>/dev/null; then
    pass "k6_search_errors_rate = ${search_err_pct}% (threshold: <5%)"
  else
    fail "k6_search_errors_rate = ${search_err_pct}% — exceeds 5% threshold"
  fi
else
  fail "k6_search_errors_rate not found in Prometheus"
fi

# Per-operation latency checks (values from k6 remote-write are in seconds)
declare -A LATENCY_THRESHOLDS=(
  [search_flat]=3000
  [search_agg]=5000
  [search_update]=2000
  [search_single_doc]=2000
)
for op_name in "${!LATENCY_THRESHOLDS[@]}"; do
  threshold_ms="${LATENCY_THRESHOLDS[$op_name]}"
  p95=$(prom_query "k6_http_req_duration_p95{name=\"${op_name}\"}")
  if [[ -n "$p95" ]]; then
    p95_ms=$(python3 -c "print(round(float('$p95') * 1000, 1))" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$p95') * 1000 < ${threshold_ms} else 1)" 2>/dev/null; then
      pass "${op_name} p95 = ${p95_ms}ms (threshold: <${threshold_ms}ms)"
    else
      fail "${op_name} p95 = ${p95_ms}ms — exceeds ${threshold_ms}ms threshold"
    fi
  else
    info "${op_name} p95 not yet in Prometheus — may need a moment to scrape"
  fi
done

# ── Step 8: Deep-paging metrics (if enabled) ──────────────────────────────────
header "Step 8 — Deep-paging metrics"

if $DEEP_PAGING; then
  scroll_total=$(prom_query "k6_search_scroll_sequences_total")
  if [[ -n "$scroll_total" && "$scroll_total" != "0" ]]; then
    pass "k6_search_scroll_sequences_total = $scroll_total (scroll sequences ran)"
  else
    fail "k6_search_scroll_sequences_total = 0 — scroll sequences did not run"
  fi

  dp_err_rate=$(prom_query "k6_search_deep_paging_errors_rate")
  if [[ -n "$dp_err_rate" ]]; then
    dp_err_pct=$(python3 -c "print(f'{float(\"$dp_err_rate\")*100:.2f}')" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$dp_err_rate') < 0.05 else 1)" 2>/dev/null; then
      pass "k6_search_deep_paging_errors_rate = ${dp_err_pct}% (threshold: <5%)"
    else
      fail "k6_search_deep_paging_errors_rate = ${dp_err_pct}% — exceeds 5% threshold"
    fi
  else
    fail "k6_search_deep_paging_errors_rate not found in Prometheus"
  fi

  # Scroll latency
  for step_name in scroll_open scroll_page scroll_close; do
    p95=$(prom_query "k6_http_req_duration_p95{name=\"${step_name}\"}")
    if [[ -n "$p95" ]]; then
      p95_ms=$(python3 -c "print(round(float('$p95') * 1000, 1))" 2>/dev/null || echo "?")
      if python3 -c "import sys; sys.exit(0 if float('$p95') * 1000 < 3000 else 1)" 2>/dev/null; then
        pass "${step_name} p95 = ${p95_ms}ms (threshold: <3000ms)"
      else
        fail "${step_name} p95 = ${p95_ms}ms — exceeds 3000ms threshold"
      fi
    else
      info "${step_name} p95 not yet in Prometheus"
    fi
  done
else
  info "Skipping deep-paging metric checks (run with --deep-paging to enable)"
  info "To run deep-paging mode: ./scripts/validate_phase_3.sh --with-setup --deep-paging"
fi

# ── Step 9: Replayer memory growth (manual) ────────────────────────────────────
header "Step 9 — Replayer memory growth (manual / Traffic Replayer required)"
info "This step requires the Traffic Replayer to be running against the same Kafka topic."
info "Large query responses inflate the Replayer's memory footprint more than the Proxy's."
info ""
info "To observe expected memory growth:"
info "  1. Start the Traffic Replayer against the same Kafka topic."
info "  2. Run k6 with --deep-paging to generate scroll/search_after traffic."
info "  3. Monitor Replayer JVM heap in Grafana or via:"
info "       docker stats <replayer-container>"
info "  4. Expected: Replayer memory grows proportionally with open scroll context count."
info "  5. After scroll close requests arrive, memory should plateau or decrease."
info ""
info "To compare modes:"
info "  Run k6 twice — once with PAGING_MODE=scroll, once with PAGING_MODE=search_after"
info "  and compare Replayer heap profiles."

# ── Optional teardown ──────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  docker compose down -v
  pass "Stack torn down (volumes removed)"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
echo "──────────────────────────────────────────────────"
echo -e "${BOLD}Results: ${GREEN}${PASS} passed${NC}  ${RED}${FAIL} failed${NC}"
echo "──────────────────────────────────────────────────"

if (( FAIL > 0 )); then
  exit 1
fi
