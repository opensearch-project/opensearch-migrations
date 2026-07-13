#!/usr/bin/env bash
# validate_phase_4.sh
#
# Runs the Phase 4 validation checks against a live stack.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# Usage:
#   ./scripts/validate_phase_4.sh               # checks only (stack + k6 must already be done)
#   ./scripts/validate_phase_4.sh --with-setup  # also resets, starts the stack, and runs k6
#   ./scripts/validate_phase_4.sh --teardown    # also tears down the stack when finished
#   ./scripts/validate_phase_4.sh --with-setup --teardown

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
for arg in "$@"; do
  case $arg in
    --with-setup)  WITH_SETUP=true ;;
    --teardown)    WITH_TEARDOWN=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "\n${BOLD}Phase 4 Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

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
  # Tear down first so the nyc_taxis index is always recreated with the correct mapping
  # and the Redis ring starts empty — see known-gotchas.md #11 and #12.
  docker compose down -v 2>/dev/null || true
  docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana redis webdis
  echo ""

  header "Step 6 — Running k6 mixed scenario (env: k6-config/mixed-steady.env)"
  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < k6-config/mixed-steady.env
  docker compose run --rm "${env_flags[@]}" \
    k6 run --out=experimental-prometheus-rw /scripts/scenarios/mixed.js
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

# ── Step 4: Webdis responding ──────────────────────────────────────────────────
# A successful Webdis PING validates the full Webdis → Redis connection chain.
header "Step 4 — Webdis"

if curl -sf "http://localhost:7379/PING" | grep -q '"PONG"'; then
  pass "Webdis is healthy and connected to Redis (PING → PONG)"
else
  fail "Webdis is not responding — is the stack running? (docker compose up -d webdis)"
fi

# ── Step 5: Kafka topic and message count ──────────────────────────────────────
header "Step 5 — Kafka topic and message count"

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

# ── Step 6: OpenSearch has documents ──────────────────────────────────────────
header "Step 6 — OpenSearch index"

doc_count=$(curl -sf "http://localhost:19200/nyc_taxis/_count" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
  || echo 0)

if (( doc_count > 0 )); then
  pass "nyc_taxis index contains $doc_count documents"
else
  fail "nyc_taxis index has 0 documents — writes may have failed"
fi

# ── Step 7: Redis ID ring has entries ─────────────────────────────────────────
# Ingest VUs push IDs to the 'recent_ids' ring after every successful single-doc PUT.
# A non-zero ring size confirms ingest VUs ran, documents were written, and the
# registry integration is working end-to-end.
header "Step 7 — Redis ID ring buffer"

ring_size=$(curl -sf "http://localhost:7379/LLEN/recent_ids" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('LLEN',0))" 2>/dev/null \
  || echo "?")

if [[ "$ring_size" =~ ^[0-9]+$ ]] && (( ring_size > 0 )); then
  pass "Redis recent_ids ring has $ring_size entries (ingest VUs registered IDs)"
elif [[ "$ring_size" == "0" ]]; then
  fail "Redis recent_ids ring is empty — ingest VUs may not have registered any IDs"
  info "Check that CONSISTENCY_FRACTION > 0 and that single-doc writes succeeded"
else
  fail "Could not query Redis ring size (got: $ring_size)"
fi

# ── Step 8: Prometheus — ingest stream metrics ─────────────────────────────────
header "Step 8 — Prometheus: ingest stream metrics"

bulk_total=$(prom_query "k6_mixed_ingest_bulk_requests_total")
if [[ -n "$bulk_total" && "$bulk_total" != "0" ]]; then
  pass "k6_mixed_ingest_bulk_requests_total = $bulk_total"
else
  fail "k6_mixed_ingest_bulk_requests_total not found or is 0 — bulk ingest may not have run"
fi

single_total=$(prom_query "k6_mixed_ingest_single_requests_total")
if [[ -n "$single_total" && "$single_total" != "0" ]]; then
  pass "k6_mixed_ingest_single_requests_total = $single_total"
else
  fail "k6_mixed_ingest_single_requests_total not found or is 0 — single-doc ingest may not have run"
fi

ingest_err_rate=$(prom_query "k6_mixed_ingest_errors_rate")
if [[ -n "$ingest_err_rate" ]]; then
  ingest_err_pct=$(python3 -c "print(f'{float(\"$ingest_err_rate\")*100:.2f}')" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$ingest_err_rate') < 0.05 else 1)" 2>/dev/null; then
    pass "k6_mixed_ingest_errors_rate = ${ingest_err_pct}% (threshold: <5%)"
  else
    fail "k6_mixed_ingest_errors_rate = ${ingest_err_pct}% — exceeds 5% threshold"
  fi
else
  fail "k6_mixed_ingest_errors_rate not found in Prometheus"
fi

# Per-op latency checks for the ingest stream
declare -A INGEST_LATENCY=(
  [bulk_write]=3000
  [single_doc]=2000
)
for op_name in "${!INGEST_LATENCY[@]}"; do
  threshold_ms="${INGEST_LATENCY[$op_name]}"
  p95=$(prom_query "k6_http_req_duration_p95{name=\"${op_name}\",scenario=\"mixed_ingest\"}")
  if [[ -z "$p95" ]]; then
    p95=$(prom_query "k6_http_req_duration_p95{name=\"${op_name}\"}")
  fi
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

# ── Step 9: Prometheus — search stream metrics ─────────────────────────────────
header "Step 9 — Prometheus: search stream metrics"

flat_total=$(prom_query "k6_mixed_search_flat_requests_total")
if [[ -n "$flat_total" && "$flat_total" != "0" ]]; then
  pass "k6_mixed_search_flat_requests_total = $flat_total"
else
  fail "k6_mixed_search_flat_requests_total not found or is 0 — search stream may not have run"
fi

agg_total=$(prom_query "k6_mixed_search_agg_requests_total")
if [[ -n "$agg_total" && "$agg_total" != "0" ]]; then
  pass "k6_mixed_search_agg_requests_total = $agg_total"
else
  fail "k6_mixed_search_agg_requests_total not found or is 0 — aggregation searches may not have run"
fi

search_err_rate=$(prom_query "k6_mixed_search_errors_rate")
if [[ -n "$search_err_rate" ]]; then
  search_err_pct=$(python3 -c "print(f'{float(\"$search_err_rate\")*100:.2f}')" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$search_err_rate') < 0.05 else 1)" 2>/dev/null; then
    pass "k6_mixed_search_errors_rate = ${search_err_pct}% (threshold: <5%)"
  else
    fail "k6_mixed_search_errors_rate = ${search_err_pct}% — exceeds 5% threshold"
  fi
else
  fail "k6_mixed_search_errors_rate not found in Prometheus"
fi

# ── Step 10: Prometheus — write-then-read consistency metrics ──────────────────
header "Step 10 — Prometheus: write-then-read consistency"

consistency_total=$(prom_query "k6_mixed_search_consistency_reads_total")
if [[ -n "$consistency_total" && "$consistency_total" != "0" ]]; then
  pass "k6_mixed_search_consistency_reads_total = $consistency_total (ring-based reads ran)"
else
  fail "k6_mixed_search_consistency_reads_total = 0 or missing"
  info "This check fails if the Redis ring was still empty when all search VUs ran."
  info "The ring fills after ~130 s of ingest at 30 req/s with SEQUENCE_FRACTION=0.15."
  info "Increase DURATION or decrease CONSISTENCY_FRACTION for shorter test runs."
fi

id_registry_rate=$(prom_query "k6_mixed_id_registry_hits_rate")
if [[ -n "$id_registry_rate" ]]; then
  id_hit_pct=$(python3 -c "print(f'{float(\"$id_registry_rate\")*100:.1f}')" 2>/dev/null || echo "?")
  pass "k6_mixed_id_registry_hits_rate = ${id_hit_pct}% (fraction of ring reads that found an ID)"
else
  info "k6_mixed_id_registry_hits_rate not yet in Prometheus"
fi

# consistency_read p95 latency
cr_p95=$(prom_query "k6_http_req_duration_p95{name=\"consistency_read\"}")
if [[ -n "$cr_p95" ]]; then
  cr_p95_ms=$(python3 -c "print(round(float('$cr_p95') * 1000, 1))" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$cr_p95') * 1000 < 2000 else 1)" 2>/dev/null; then
    pass "consistency_read p95 = ${cr_p95_ms}ms (threshold: <2000ms)"
  else
    fail "consistency_read p95 = ${cr_p95_ms}ms — exceeds 2000ms threshold"
  fi
else
  info "consistency_read p95 not yet in Prometheus"
fi

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
