#!/usr/bin/env bash
# Validates the mixed profile: concurrent ingest + search with a write-then-read consistency check
# via the Redis/Webdis ID registry. Needs Redis+Webdis — install the chart with registry.enabled:
#   helm upgrade k6-load-test <chart> -n ma --reuse-values --set registry.enabled=true
# Assumes the data plane is already up (deployWorkflowComponents.sh up).
#
# Usage:
#   ./scripts/run_test_mixed.sh          # checks only
#   ./scripts/run_test_mixed.sh --run    # run the mixed scenario (registry on), then check

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

WITH_RUN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run) WITH_RUN=true ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
  shift
done

echo -e "\n${BOLD}Mixed Profile Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

header "Step 1 — Capture Proxy reachable"
check_proxy_ready

if $WITH_RUN; then
  header "Step 2 — Running k6 mixed scenario (registry enabled)"
  run_k6 --scenario mixed --config mixed-steady --registry-enabled
else
  info "Skipping k6 run (use --run to submit one first)."
fi

header "Step 3 — Service health"
check_service_health "" kafka opensearch-source capture-proxy redis webdis

header "Step 4 — Webdis PING"
webdis_raw=$(kcurl -sf "http://webdis:7379/PING" 2>/dev/null || echo "")
if echo "$webdis_raw" | grep -q "PONG"; then
  pass "Webdis reachable (PING → PONG)"
else
  info "Webdis PING response: '${webdis_raw:-none}' (needs the chart installed with registry.enabled=true)"
fi

header "Step 5 — OpenSearch index (source)"
check_opensearch_docs nyc_taxis 1 source

header "Step 6 — Kafka topic and message count"
check_kafka_topic
check_kafka_messages 1

header "Step 7 — Prometheus: ingest metrics"
prom_check_counter "mixed_ingest_bulk_requests_total"   "mixed_ingest_bulk_requests_total"
prom_check_counter "mixed_ingest_single_requests_total" "mixed_ingest_single_requests_total"
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "bulk_write"  3000
prom_check_latency_p95 "single_doc"  2000

header "Step 8 — Prometheus: search metrics"
prom_check_counter "mixed_search_flat_requests_total" "mixed_search_flat_requests_total"
prom_check_counter "mixed_search_agg_requests_total"  "mixed_search_agg_requests_total"
prom_check_latency_p95 "flat_search"  2000
prom_check_latency_p95 "agg_search"   5000

header "Step 9 — Prometheus: mixed consistency metrics"
prom_check_counter "mixed_search_consistency_reads_total"  "mixed_search_consistency_reads_total"
prom_check_counter "mixed_search_consistency_misses_total" "mixed_search_consistency_misses_total"

print_summary
