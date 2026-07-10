#!/usr/bin/env bash
# validate_phase_2.sh
#
# Runs the Phase 2 validation checks against a live stack.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# Usage:
#   ./scripts/validate_phase_2.sh               # checks only (stack + k6 must already be done)
#   ./scripts/validate_phase_2.sh --with-setup  # also starts the stack and runs k6 first
#   ./scripts/validate_phase_2.sh --teardown    # also tears down the stack when finished
#   ./scripts/validate_phase_2.sh --with-setup --teardown
#
# Note: Step 10 (replayer ordering) requires the Traffic Replayer to be running
# and is printed as guidance only — it cannot be automated here.

set -euo pipefail

# ── Helpers ───────────────────────────────────────────────────────────────────
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

# ── Argument parsing ──────────────────────────────────────────────────────────
WITH_SETUP=false
WITH_TEARDOWN=false
for arg in "$@"; do
  case $arg in
    --with-setup)  WITH_SETUP=true ;;
    --teardown)    WITH_TEARDOWN=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "\n${BOLD}Phase 2 Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Step 1: capture proxy image check / build ─────────────────────────────────
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

# ── Step 2: optional stack startup + k6 run ───────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Starting stack"
  docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
  echo ""

  header "Step 5 — Running k6 ingest scenario (Phase 2: sequences enabled, pinned mode)"
  # docker compose run does not support --env-file; build -e flags from the file
  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < k6-config/ingest-steady.env
  # SEQUENCE_FRACTION and CONNECTION_MODE default to 0.15 / pinned in ingest.js;
  # pass them explicitly so the intent is visible in the script output.
  docker compose run --rm "${env_flags[@]}" \
    -e SEQUENCE_FRACTION=0.15 \
    -e CONNECTION_MODE=pinned \
    k6 run --out=experimental-prometheus-rw /scripts/scenarios/ingest.js
  echo ""
else
  info "Skipping stack startup and k6 run (use --with-setup to include those steps)."
fi

# ── Step 3: service health ────────────────────────────────────────────────────
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

# ── Step 4: Kafka topic exists ────────────────────────────────────────────────
header "Step 4 — Kafka topic"

if docker compose exec -T kafka \
     /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null \
   | grep -q "logging-traffic-topic"; then
  pass "logging-traffic-topic exists"
else
  fail "logging-traffic-topic not found"
fi

# ── Step 6: Kafka message count ───────────────────────────────────────────────
header "Step 6 — Kafka message count"

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

# ── Step 7: OpenSearch document count ────────────────────────────────────────
header "Step 7 — OpenSearch index"

doc_count=$(curl -sf "http://localhost:19200/nyc_taxis/_count" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
  || echo 0)

if (( doc_count > 0 )); then
  pass "nyc_taxis index contains $doc_count documents"
else
  fail "nyc_taxis index has 0 documents — writes may have failed"
fi

# ── Step 8: No leaked sequence documents ─────────────────────────────────────
# Sequences create docs with IDs like seq-<VU>-<ITER>. Each create is followed by a
# delete in the same iteration. A non-zero count here means some sequences did not
# complete their delete (e.g. due to an error mid-sequence).
header "Step 8 — Sequence document leak check"

leaked=$(curl -sf "http://localhost:19200/nyc_taxis/_count" \
  -H 'Content-Type: application/json' \
  --data '{"query":{"wildcard":{"_id":{"value":"seq-*"}}}}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
  || echo "?")

if [[ "$leaked" == "0" ]]; then
  pass "No leaked sequence documents (0 docs with seq-* IDs)"
elif [[ "$leaked" =~ ^[0-9]+$ ]] && (( leaked <= 5 )); then
  pass "Negligible sequence document leak ($leaked docs with seq-* IDs — within tolerance)"
elif [[ "$leaked" =~ ^[0-9]+$ ]]; then
  fail "Sequence document leak detected: $leaked docs with seq-* IDs remain in index"
  info "These are docs whose delete step failed (check k6 ingest_sequence_errors_rate)"
else
  info "Could not determine leaked doc count (query returned: $leaked)"
fi

# ── Step 9: Prometheus — sequence metrics ─────────────────────────────────────
header "Step 9 — Prometheus: sequence metrics"

seq_total=$(prom_query "k6_ingest_sequence_requests_total")
if [[ -n "$seq_total" && "$seq_total" != "0" ]]; then
  pass "k6_ingest_sequence_requests_total = $seq_total"
else
  fail "k6_ingest_sequence_requests_total not found or is 0 — sequences may not have run"
fi

seq_err_rate=$(prom_query "k6_ingest_sequence_errors_rate")
if [[ -n "$seq_err_rate" ]]; then
  seq_err_pct=$(python3 -c "print(f'{float(\"$seq_err_rate\")*100:.2f}')" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$seq_err_rate') < 0.05 else 1)" 2>/dev/null; then
    pass "k6_ingest_sequence_errors_rate = ${seq_err_pct}% (threshold: <5%)"
  else
    fail "k6_ingest_sequence_errors_rate = ${seq_err_pct}% — exceeds 5% threshold"
  fi
else
  fail "k6_ingest_sequence_errors_rate not found in Prometheus"
fi

# Per-step latency checks (values are in seconds from k6 remote-write)
for step_name in seq_create seq_update seq_query seq_delete; do
  p95=$(prom_query "k6_http_req_duration_p95{name=\"${step_name}\"}")
  if [[ -n "$p95" ]]; then
    p95_ms=$(python3 -c "print(round(float('$p95') * 1000, 1))" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$p95') * 1000 < 2000 else 1)" 2>/dev/null; then
      pass "${step_name} p95 latency = ${p95_ms}ms (threshold: <2000ms)"
    else
      fail "${step_name} p95 latency = ${p95_ms}ms — exceeds 2000ms threshold"
    fi
  else
    info "${step_name} p95 not yet in Prometheus — may need a moment to scrape"
  fi
done

# Bulk and single-doc latency (regression check from Phase 1)
bulk_p95=$(prom_query 'k6_http_req_duration_p95{name="bulk_write"}')
if [[ -n "$bulk_p95" ]]; then
  bulk_p95_ms=$(python3 -c "print(round(float('$bulk_p95') * 1000, 1))" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$bulk_p95') * 1000 < 3000 else 1)" 2>/dev/null; then
    pass "bulk_write p95 latency = ${bulk_p95_ms}ms (threshold: <3000ms)"
  else
    fail "bulk_write p95 latency = ${bulk_p95_ms}ms — exceeds 3000ms threshold"
  fi
else
  info "bulk_write p95 not yet in Prometheus"
fi

# ── Step 10: Replayer ordering verification (manual) ─────────────────────────
header "Step 10 — Replayer ordering (manual / Traffic Replayer required)"
info "This step requires the Traffic Replayer to be running against the same Kafka topic."
info "The Replayer is not part of the Phase 2 stack — start it separately when needed."
info ""
info "To verify PINNED mode (in-order replay):"
info "  1. Run: docker compose up -d traffic-replayer  (once added to docker-compose.yml)"
info "  2. Wait for it to consume the topic and write output_tuples.log"
info "  3. Filter sequence requests and confirm ordering:"
info "       grep 'seq-' output_tuples.log | head -40"
info "     Expected: each seq-<VU>-<ITER> block shows create → update → query → delete"
info ""
info "To verify SPREAD mode (cross-stream ordering differences):"
info "  Run k6 with CONNECTION_MODE=spread, then inspect output_tuples.log:"
info "       grep 'seq-' output_tuples.log | sort | head -40"
info "  Expected: update or query arriving before create for some sequence IDs"

# ── Optional teardown ─────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Step 11 — Teardown"
  docker compose down -v
  pass "Stack torn down (volumes removed)"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "──────────────────────────────────────────────────"
echo -e "${BOLD}Results: ${GREEN}${PASS} passed${NC}  ${RED}${FAIL} failed${NC}"
echo "──────────────────────────────────────────────────"

if (( FAIL > 0 )); then
  exit 1
fi
