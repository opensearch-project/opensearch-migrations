#!/usr/bin/env bash
# Validates stateful create→update→query→delete sequences (connection pinning; doc leak check).
# Assumes the data plane is already up (deployWorkflowComponents.sh up).
#
# Usage:
#   ./scripts/run_test_sequences.sh          # checks only
#   ./scripts/run_test_sequences.sh --run    # run ingest with sequences enabled, then check

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

WITH_RUN=false; CONNECTION_MODE="pinned"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run) WITH_RUN=true ;;
    --spread) CONNECTION_MODE="spread" ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
  shift
done

echo -e "\n${BOLD}Stateful Sequences Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

header "Step 1 — Capture Proxy reachable"
check_proxy_ready

if $WITH_RUN; then
  header "Step 2 — Running k6 ingest with sequences ($CONNECTION_MODE mode)"
  run_k6 --scenario ingest --config ingest-steady \
    -o SEQUENCE_FRACTION=0.15 -o "CONNECTION_MODE=$CONNECTION_MODE"
else
  info "Skipping k6 run (use --run to submit one first)."
fi

header "Step 3 — Service health"
check_service_health "" kafka opensearch-source capture-proxy

header "Step 4 — Kafka topic and message count"
check_kafka_topic
check_kafka_messages 1

header "Step 5 — OpenSearch index (source)"
check_opensearch_docs nyc_taxis 1 source

header "Step 6 — Prometheus: sequence metrics"
prom_check_counter "ingest_sequence_requests_total" "ingest_sequence_requests_total"
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
for step in seq_create seq_update seq_query seq_delete; do
  prom_check_latency_p95 "$step" 2000
done

header "Step 7 — Prometheus: ingest regression (bulk + single-doc)"
prom_check_latency_p95 "bulk_write"  3000
prom_check_latency_p95 "single_doc"  2000

header "Step 8 — Replayer ordering (informational)"
info "  The replayer is deployed by deployWorkflowComponents.sh; run with --spread and inspect"
info "  its logs for out-of-order sequence replays: kubectl -n $NAMESPACE logs deploy/replayer"

print_summary
