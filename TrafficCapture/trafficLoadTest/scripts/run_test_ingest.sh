#!/usr/bin/env bash
# Validates the ingest baseline: k6 → Capture Proxy → Kafka → OpenSearch.
# Must be run from the TrafficCapture/trafficLoadTest/ directory.
#
# Usage:
#   ./scripts/run_test_ingest.sh               # checks only (stack + k6 must already be done)
#   ./scripts/run_test_ingest.sh --with-setup  # also starts the stack and runs k6 first
#   ./scripts/run_test_ingest.sh --teardown    # also tears down the stack when finished

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

# ── Argument parsing ──────────────────────────────────────────────────────────
WITH_SETUP=false; WITH_TEARDOWN=false
for arg in "$@"; do
  case $arg in
    --with-setup) WITH_SETUP=true ;;
    --teardown)   WITH_TEARDOWN=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "\n${BOLD}Ingest Baseline Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Capture Proxy image ────────────────────────────────────────────────────────
header "Step 1 — Capture Proxy image"
check_capture_proxy_image "$WITH_SETUP"

# ── Optional stack startup + k6 run ──────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Starting stack"
  "${DOCKER_COMPOSE[@]}" up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
  echo ""

  header "Step 3 — Running k6 ingest scenario"
  load_k6_env "k6-config/ingest-steady.env"
  "${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
    k6 run --out=opentelemetry /scripts/scenarios/ingest.js
  echo ""
else
  info "Skipping stack startup and k6 run (use --with-setup to include those steps)."
fi

# ── Service health ────────────────────────────────────────────────────────────
header "Step 4 — Service health"
check_service_health "" kafka opensearch-source capture-proxy

# ── Kafka ─────────────────────────────────────────────────────────────────────
header "Step 5 — Kafka topic"
check_kafka_topic

# Capture Proxy startup logs reference Kafka when the producer initialises.
header "Step 6 — Capture Proxy → Kafka connectivity"
kafka_log_lines=$("${DOCKER_COMPOSE[@]}" logs capture-proxy 2>/dev/null | grep -ic "kafka" || true)
error_log_lines=$("${DOCKER_COMPOSE[@]}" logs capture-proxy 2>/dev/null | grep -ic "error" || true)
if (( kafka_log_lines > 0 )); then
  pass "Proxy logs reference Kafka ($kafka_log_lines lines); error lines: $error_log_lines"
else
  fail "No Kafka-related lines in Capture Proxy logs — producer may have failed to initialise"
fi

# Topic describe confirms healthy partitions (no replayer in this scenario so consumer lag is N/A).
topic_describe=$("${DOCKER_COMPOSE[@]}" exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe --topic logging-traffic-topic 2>/dev/null || true)
if echo "$topic_describe" | grep -q "logging-traffic-topic"; then
  pass "logging-traffic-topic described successfully"
  info "Topic details: $(echo "$topic_describe" | head -1)"
else
  fail "Could not describe logging-traffic-topic"
fi

header "Step 7 — Kafka message count"
check_kafka_messages 1

# ── OpenSearch ────────────────────────────────────────────────────────────────
header "Step 8 — OpenSearch index"
check_opensearch_docs nyc_taxis 1

# ── Prometheus ────────────────────────────────────────────────────────────────
header "Step 9 — Prometheus metrics"
prom_check_counter "ingest_bulk_requests_total"       "ingest_bulk_requests_total"
prom_check_counter "ingest_single_doc_requests_total" "ingest_single_doc_requests_total"
# k6 Rate metrics (ingest_errors_total) count all add() calls, not just error events, so
# ingest_errors_total is the SUM of error values (0 in a healthy run), so checking it
# as a counter would always fail when there are no errors.  HTTP error rate via
# http_reqs_total{status=~"4..|5.."} is the correct signal.
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "bulk_write"  3000
prom_check_latency_p95 "single_doc"  2000

# ── Optional teardown ─────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  "${DOCKER_COMPOSE[@]}" down -v
  pass "Stack torn down (volumes removed)"
fi

print_summary
