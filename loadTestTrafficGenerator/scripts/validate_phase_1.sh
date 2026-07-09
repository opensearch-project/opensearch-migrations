#!/usr/bin/env bash
# validate_phase_1.sh
#
# Runs the Phase 1 validation checks against a live stack.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# Usage:
#   ./scripts/validate_phase_1.sh               # checks only (stack + k6 must already be done)
#   ./scripts/validate_phase_1.sh --with-setup  # also starts the stack and runs k6 first
#   ./scripts/validate_phase_1.sh --teardown    # also tears down the stack when finished

set -euo pipefail

# ── Helpers ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'

PASS=0; FAIL=0

pass() { echo -e "  ${GREEN}✓ PASS${NC}  $1"; (( PASS++ )) || true; }
fail() { echo -e "  ${RED}✗ FAIL${NC}  $1"; (( FAIL++ )) || true; }
info() { echo -e "  ${YELLOW}ℹ${NC}      $1"; }
header() { echo -e "\n${BOLD}$1${NC}"; }

prom_query() {
  # Returns the scalar value of a Prometheus instant query, or empty string on error.
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

echo -e "\n${BOLD}Phase 1 Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Step 1: capture proxy image check / build ─────────────────────────────────
header "Step 1 — Capture Proxy image"

CAPTURE_IMAGE="migrations/capture_proxy:latest"
# gradlew lives at the repo root — two levels above this script (scripts/ → POC/ → repo root)
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

  header "Step 5 — Running k6 ingest scenario"
  # docker compose run does not support --env-file; build -e flags from the file
  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < k6-config/ingest-steady.env
  docker compose run --rm "${env_flags[@]}" \
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
  fail "logging-traffic-topic not found — proxy may not have connected to Kafka yet"
fi

# ── Step 6: Capture Proxy — Kafka connectivity ────────────────────────────────
# The proxy does not log individual TrafficStream events at default verbosity.
# Instead check that the Kafka producer was initialised (appears in startup logs).
header "Step 6 — Capture Proxy Kafka connectivity"

kafka_log_lines=$(docker compose logs capture-proxy 2>/dev/null | grep -ic "kafka" || true)
error_log_lines=$(docker compose logs capture-proxy 2>/dev/null | grep -ic "error" || true)
if (( kafka_log_lines > 0 )); then
  pass "Proxy logs reference Kafka ($kafka_log_lines lines); error lines: $error_log_lines"
else
  fail "No Kafka-related lines in Capture Proxy logs — producer may have failed to initialise"
fi

# ── Step 7: Kafka topic partition info ────────────────────────────────────────
# Consumer groups only appear when a consumer (e.g. Traffic Replayer) is running.
# In Phase 1 there is no replayer, so --all-groups is always empty.
# Instead describe the topic itself to confirm it has healthy partitions.
header "Step 7 — Kafka topic partitions"

topic_describe=$(docker compose exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe --topic logging-traffic-topic 2>/dev/null || true)

if echo "$topic_describe" | grep -q "logging-traffic-topic"; then
  part_count=$(echo "$topic_describe" | grep -c "PartitionCount\|Leader:" || true)
  pass "logging-traffic-topic described successfully (no replayer in Phase 1 — consumer lag check skipped)"
  info "Topic details: $(echo "$topic_describe" | head -1)"
else
  fail "Could not describe logging-traffic-topic"
fi

# ── Step 8: raw Kafka message count ──────────────────────────────────────────
# kafka.tools.GetOffsetShell was removed in Kafka 3.x.
# kafka-get-offsets.sh is the official replacement (introduced in Kafka 2.8).
# Output format: topic:partition:offset
header "Step 8 — Kafka message count"

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

# ── Step 9: OpenSearch document count ────────────────────────────────────────
header "Step 9 — OpenSearch index"

doc_count=$(curl -sf "http://localhost:19200/nyc_taxis/_count" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
  || echo 0)

if (( doc_count > 0 )); then
  pass "nyc_taxis index contains $doc_count documents"
else
  fail "nyc_taxis index has 0 documents — writes may have failed"
fi

# ── Step 10: Prometheus metrics ───────────────────────────────────────────────
header "Step 10 — Prometheus metrics"

# k6 remote-write prefixes all metrics with "k6_" and appends its own type suffix:
#   Counter → _total, Rate → _rate, Trend stat → _p95 etc.
# Metric names in ingest.js use bare names (no type suffix) so k6 produces clean names:
#   k6_ingest_bulk_requests_total, k6_ingest_single_doc_requests_total,
#   k6_ingest_errors_rate, k6_ingest_bulk_batch_docs_p95 etc.
# Duration trend stats (k6_http_req_duration_p95) are in SECONDS (Prometheus base unit).

bulk_total=$(prom_query "k6_ingest_bulk_requests_total")
if [[ -n "$bulk_total" && "$bulk_total" != "0" ]]; then
  pass "k6_ingest_bulk_requests_total = $bulk_total"
else
  fail "k6_ingest_bulk_requests_total not found or is 0 in Prometheus"
fi

single_total=$(prom_query "k6_ingest_single_doc_requests_total")
if [[ -n "$single_total" && "$single_total" != "0" ]]; then
  pass "k6_ingest_single_doc_requests_total = $single_total"
else
  fail "k6_ingest_single_doc_requests_total not found or is 0 in Prometheus"
fi

error_rate=$(prom_query "k6_ingest_errors_rate")
if [[ -n "$error_rate" ]]; then
  error_pct=$(python3 -c "print(f'{float(\"$error_rate\")*100:.2f}')" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$error_rate') < 0.05 else 1)" 2>/dev/null; then
    pass "k6_ingest_errors_rate = ${error_pct}% (threshold: <5%)"
  else
    fail "k6_ingest_errors_rate = ${error_pct}% — exceeds 5% threshold"
  fi
else
  fail "k6_ingest_errors_rate not found in Prometheus"
fi

# Duration values are stored in seconds by k6 remote-write; multiply by 1000 for ms display/comparison.
bulk_p95=$(prom_query 'k6_http_req_duration_p95{name="bulk_write"}')
if [[ -n "$bulk_p95" ]]; then
  bulk_p95_ms=$(python3 -c "print(round(float('$bulk_p95') * 1000, 1))" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$bulk_p95') * 1000 < 3000 else 1)" 2>/dev/null; then
    pass "bulk_write p95 latency = ${bulk_p95_ms}ms (threshold: <3000ms)"
  else
    fail "bulk_write p95 latency = ${bulk_p95_ms}ms — exceeds 3000ms threshold"
  fi
else
  info "bulk_write p95 not yet in Prometheus — may need a moment to scrape"
fi

single_p95=$(prom_query 'k6_http_req_duration_p95{name="single_doc"}')
if [[ -n "$single_p95" ]]; then
  single_p95_ms=$(python3 -c "print(round(float('$single_p95') * 1000, 1))" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$single_p95') * 1000 < 2000 else 1)" 2>/dev/null; then
    pass "single_doc p95 latency = ${single_p95_ms}ms (threshold: <2000ms)"
  else
    fail "single_doc p95 latency = ${single_p95_ms}ms — exceeds 2000ms threshold"
  fi
else
  info "single_doc p95 not yet in Prometheus — may need a moment to scrape"
fi

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
