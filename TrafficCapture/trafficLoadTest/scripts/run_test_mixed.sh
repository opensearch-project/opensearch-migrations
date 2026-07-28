#!/usr/bin/env bash
# run_test_mixed.sh
#
# Validates the mixed ingest + search scenario with Redis/Webdis cross-VU ring buffer.
# Must be run from the TrafficCapture/trafficLoadTest/ directory.
#
# Usage:
#   ./scripts/run_test_mixed.sh               # checks only
#   ./scripts/run_test_mixed.sh --with-setup  # starts full stack + redis + runs k6
#   ./scripts/run_test_mixed.sh --teardown    # tear down after checks

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

echo -e "\n${BOLD}Mixed Ingest + Search Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Capture Proxy image ────────────────────────────────────────────────────────
header "Step 1 — Capture Proxy image"
check_capture_proxy_image "$WITH_SETUP"

# ── Optional stack startup + k6 run ──────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Starting stack (including Redis + Webdis)"
  "${DOCKER_COMPOSE[@]}" up -d --wait \
    kafka opensearch-source capture-proxy otel-collector prometheus grafana \
    redis webdis
  echo ""

  header "Step 3 — Running k6 mixed scenario"
  load_k6_env "k6-config/mixed-steady.env"
  "${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
    k6 run --out=opentelemetry /scripts/scenarios/mixed.js
  echo ""
else
  info "Skipping stack startup and k6 run (use --with-setup to include those steps)."
fi

# ── Service health ────────────────────────────────────────────────────────────
header "Step 4 — Service health"
check_service_health "" kafka opensearch-source capture-proxy redis webdis

# ── Webdis / Redis ring buffer check ─────────────────────────────────────────
header "Step 5 — Webdis PING"
webdis_pong=$(curl -sf "http://localhost:7379/PING" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
val = d.get('PING', '')
# Webdis returns {'PING': [true, 'PONG']} — accept both list and bare-string forms.
print('PONG' if val == 'PONG' or (isinstance(val, list) and 'PONG' in val) else '')
" 2>/dev/null \
  || echo "")
if [[ "$webdis_pong" == "PONG" ]]; then
  pass "Webdis responded PONG"
else
  fail "Webdis PING failed (got: '${webdis_pong:-no response}')"
fi

header "Step 6 — Redis ring buffer (recent_ids)"
ring_len=$(curl -sf "http://localhost:7379/LLEN/recent_ids" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('LLEN',0))" 2>/dev/null \
  || echo "0")
if [[ "$ring_len" =~ ^[0-9]+$ ]] && (( ring_len > 0 )); then
  pass "recent_ids ring buffer has $ring_len entries"
else
  fail "recent_ids ring buffer is empty or unreachable (LLEN=$ring_len)"
fi

# ── OpenSearch ────────────────────────────────────────────────────────────────
header "Step 7 — OpenSearch index"
check_opensearch_docs nyc_taxis 1

# ── Kafka ─────────────────────────────────────────────────────────────────────
header "Step 8 — Kafka topic and message count"
check_kafka_topic
check_kafka_messages 1

# ── Prometheus ────────────────────────────────────────────────────────────────
header "Step 9 — Prometheus: ingest metrics"
prom_check_counter "mixed_ingest_bulk_requests_total"   "mixed_ingest_bulk_requests_total"
prom_check_counter "mixed_ingest_single_requests_total" "mixed_ingest_single_requests_total"
# mixed_ingest_errors_total is the SUM of error values (0 in a healthy run), so checking
# it as a counter would always fail when there are no errors.  HTTP error rate via
# http_reqs_total{status=~"4..|5.."} is the correct signal.
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "bulk_write"  3000
prom_check_latency_p95 "single_doc"  2000

header "Step 10 — Prometheus: search metrics"
prom_check_counter "mixed_search_flat_requests_total" "mixed_search_flat_requests_total"
prom_check_counter "mixed_search_agg_requests_total"  "mixed_search_agg_requests_total"
# mixed_search_errors_total is the SUM of error values — same reasoning as Step 9.
# (HTTP error rate already checked in Step 9 above)
prom_check_latency_p95 "flat_search"  2000
prom_check_latency_p95 "agg_search"   5000

header "Step 11 — Prometheus: mixed consistency metrics"
prom_check_counter "mixed_search_consistency_reads_total"  "mixed_search_consistency_reads_total"
prom_check_counter "mixed_search_consistency_misses_total" "mixed_search_consistency_misses_total"

# ── Optional teardown ─────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  "${DOCKER_COMPOSE[@]}" down -v
  pass "Stack torn down (volumes removed)"
fi

print_summary
