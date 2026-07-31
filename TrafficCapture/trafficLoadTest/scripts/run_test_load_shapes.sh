#!/usr/bin/env bash
# Validates the ramping-arrival-rate load shapes: ramp, burst, mixed-ramp.
# Assumes the data plane is already up (deployWorkflowComponents.sh up).
#
# Usage:
#   ./scripts/run_test_load_shapes.sh --run                    # ramp only, then check
#   ./scripts/run_test_load_shapes.sh --run --with-burst       # ramp + burst
#   ./scripts/run_test_load_shapes.sh --run --with-mixed-ramp  # ramp + mixed-ramp (needs registry)
#   ./scripts/run_test_load_shapes.sh                          # checks only

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

WITH_RUN=false; WITH_BURST=false; WITH_MIXED_RAMP=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run) WITH_RUN=true ;;
    --with-burst) WITH_BURST=true ;;
    --with-mixed-ramp) WITH_MIXED_RAMP=true ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
  shift
done

echo -e "\n${BOLD}Load Shapes Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

header "Step 1 — Capture Proxy reachable"
check_proxy_ready

if $WITH_RUN; then
  header "Step 2 — Ramp load shape"
  run_k6 --scenario ingest --config ingest-ramp
  check_service_health "(post-ramp)" kafka opensearch-source capture-proxy
  check_kafka_messages 1
  ramp_rate=$(prom_range_max "sum(rate(http_reqs_total[1m]))" "10m")
  [[ -n "$ramp_rate" ]] && pass "Ramp peak req/s (max over 10m): $ramp_rate" \
                        || info "Ramp: rate not yet in Prometheus"

  if $WITH_BURST; then
    header "Step 3 — Burst load shape"
    offset_before=$(kafka_total_offset)
    run_k6 --scenario ingest --config ingest-burst --extra-args --no-thresholds
    offset_after=$(kafka_total_offset)
    check_service_health "(post-burst)" kafka opensearch-source capture-proxy
    info "Kafka messages during burst: $(( offset_after - offset_before ))"
    burst_rate=$(prom_range_max "sum(rate(http_reqs_total[1m]))" "5m")
    [[ -n "$burst_rate" ]] && pass "Burst peak req/s (max over 5m): $burst_rate" \
                           || info "Burst: rate not yet in Prometheus"
  fi

  if $WITH_MIXED_RAMP; then
    header "Step 4 — Mixed ramp load shape (needs registry.enabled)"
    run_k6 --scenario mixed --config mixed-ramp --registry-enabled
    check_service_health "(post-mixed-ramp)" kafka opensearch-source capture-proxy
    mixed_rate=$(prom_range_max "sum(rate(http_reqs_total[1m]))" "10m")
    [[ -n "$mixed_rate" ]] && pass "Mixed-ramp peak req/s (max over 10m): $mixed_rate" \
                           || info "Mixed-ramp: rate not yet in Prometheus"
  fi
else
  info "Skipping k6 runs (use --run to submit them first)."
fi

header "Step 5 — Prometheus: error rate + latency across shape runs"
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "bulk_write" 3000

print_summary
