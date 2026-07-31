#!/usr/bin/env bash
# Validates the ingest baseline: k6 → Capture Proxy → Kafka → OpenSearch.
# Assumes the data plane is already up (buildImages/scripts/deployWorkflowComponents.sh up).
#
# Usage:
#   ./scripts/run_test_ingest.sh          # checks only (a k6 run must already be done)
#   ./scripts/run_test_ingest.sh --run    # submit a k6 ingest run first, then check
#   CONTEXT=<ctx> NAMESPACE=ma ./scripts/run_test_ingest.sh --run --config ingest-steady

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

WITH_RUN=false; CONFIG="ingest-steady"; PARALLELISM=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run) WITH_RUN=true ;;
    --config) CONFIG="$2"; shift ;;
    --parallelism) PARALLELISM="$2"; shift ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
  shift
done

echo -e "\n${BOLD}Ingest Baseline Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

header "Step 1 — Capture Proxy reachable"
check_proxy_ready

if $WITH_RUN; then
  header "Step 2 — Running k6 ingest scenario (config=$CONFIG, parallelism=$PARALLELISM)"
  run_k6 --scenario ingest --config "$CONFIG" --parallelism "$PARALLELISM"
else
  info "Skipping k6 run (use --run to submit one first)."
fi

header "Step 3 — Service health"
check_service_health "" kafka opensearch-source capture-proxy

header "Step 4 — Kafka topic"
check_kafka_topic

header "Step 5 — Kafka message count"
check_kafka_messages 1

header "Step 6 — OpenSearch index (source)"
check_opensearch_docs nyc_taxis 1 source

header "Step 7 — Prometheus metrics"
prom_check_counter "ingest_bulk_requests_total"       "ingest_bulk_requests_total"
prom_check_counter "ingest_single_doc_requests_total" "ingest_single_doc_requests_total"
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "bulk_write"  3000
prom_check_latency_p95 "single_doc"  2000

print_summary
