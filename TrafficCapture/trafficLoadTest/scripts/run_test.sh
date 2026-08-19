#!/usr/bin/env bash
# Validation + example runner for the k6 load-test scenarios against a running data plane
# (bring it up first with deployment/k8s/deployCdcLoadTestConfig.sh up). Optionally submits a
# k6 run, then asserts the capture-and-replay pipeline and the scenario's Prometheus metrics via
# kubectl / PromQL. The runtime control plane (pause/resume/set-rate) is validated separately by
# run_test_chaos.sh.
#
# Usage:
#   ./scripts/run_test.sh --scenario ingest --run
#   ./scripts/run_test.sh --scenario search --run --deep-paging
#   ./scripts/run_test.sh --scenario mixed  --run                  # needs registry.enabled=true
#   ./scripts/run_test.sh --scenario sequences --run --spread
#   ./scripts/run_test.sh --scenario ingest --shape ramp --run     # ramping-arrival-rate load shape
#   ./scripts/run_test.sh --scenario ingest                        # checks only (a run must exist)
#   CONTEXT=<ctx> NAMESPACE=ma ./scripts/run_test.sh --scenario ingest --run
#
# Flags:
#   --scenario ingest|search|mixed|sequences   what to run/validate (default: ingest)
#   --shape steady|ramp|burst                  load shape / preset suffix (default: steady)
#   --run                                      submit a k6 run before checking (else checks only)
#   --config NAME                              explicit preset, overrides --scenario/--shape composition
#   --parallelism N                            runner pods for the ingest scenario (default: 1)
#   --deep-paging                              search only: also run the deep-paging preset
#   --spread                                   sequences only: Connection: close instead of keep-alive

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

SCENARIO="ingest"; SHAPE="steady"; WITH_RUN=false; CONFIG=""; PARALLELISM=1
DEEP_PAGING=false; CONNECTION_MODE="pinned"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario)    SCENARIO="$2"; shift ;;
    --shape)       SHAPE="$2"; shift ;;
    --run)         WITH_RUN=true ;;
    --config)      CONFIG="$2"; shift ;;
    --parallelism) PARALLELISM="$2"; shift ;;
    --deep-paging) DEEP_PAGING=true ;;
    --spread)      CONNECTION_MODE="spread" ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
  shift
done

case "$SCENARIO" in ingest|search|mixed|sequences) ;; *) echo "unknown scenario: $SCENARIO" >&2; exit 1 ;; esac
case "$SHAPE" in steady|ramp|burst) ;; *) echo "unknown shape: $SHAPE" >&2; exit 1 ;; esac

# Preset defaults to <scenario>-<shape>; sequences run on the ingest script/presets. --config wins.
case "$SCENARIO" in
  sequences) preset="${CONFIG:-ingest-$SHAPE}" ;;
  *)         preset="${CONFIG:-$SCENARIO-$SHAPE}" ;;
esac
# Burst intentionally saturates the proxy — latency/error thresholds will breach; don't fail on them.
extra=(); [[ "$SHAPE" == "burst" ]] && extra=(--extra-args --no-thresholds)

echo -e "\n${BOLD}k6 ${SCENARIO} validation (shape=${SHAPE}) — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Submit the run ──────────────────────────────────────────────────────────────
run_scenario() {
  case "$SCENARIO" in
    ingest)
      header "Running k6 ingest (config=$preset, parallelism=$PARALLELISM)"
      run_k6 --scenario ingest --config "$preset" --parallelism "$PARALLELISM" "${extra[@]}" ;;
    search)
      header "Seeding documents (k6 ingest)"
      run_k6 --scenario ingest --config ingest-steady
      header "Running k6 search (config=$preset)"
      run_k6 --scenario search --config "$preset" "${extra[@]}"
      if $DEEP_PAGING; then
        header "Running k6 search deep-paging scenario"
        run_k6 --scenario search --config search-deep-paging
      fi ;;
    mixed)
      header "Running k6 mixed (config=$preset, registry enabled)"
      run_k6 --scenario mixed --config "$preset" --registry-enabled "${extra[@]}" ;;
    sequences)
      header "Running k6 ingest with sequences ($CONNECTION_MODE mode, config=$preset)"
      run_k6 --scenario ingest --config "$preset" \
        -e SEQUENCE_FRACTION=0.15 -e "CONNECTION_MODE=$CONNECTION_MODE" "${extra[@]}" ;;
  esac
}

header "Step 1 — Capture Proxy reachable"
check_proxy_ready

if $WITH_RUN; then
  run_scenario
else
  info "Skipping k6 run (use --run to submit one first)."
fi

# ── Shared checks ─────────────────────────────────────────────────────────────
header "Service health"
# Kafka, the captured-traffic topic, the proxy and the replayer are all workflow-owned, so their
# readiness is the CRs' phase; the Deployment check then confirms pods are actually serving.
check_migration_resources_ready
check_workload_health
# redis/webdis back the mixed scenario's id registry, and come from the k6LoadTest chart
# (registry.enabled=true) rather than from the migration workflow.
if [[ "$SCENARIO" == "mixed" ]]; then
  check_service_health "" redis webdis
fi

# Ramp/burst shapes: report the peak arrival rate reached (constant-rate steady runs skip this).
if [[ "$SHAPE" != "steady" ]]; then
  header "Peak arrival rate ($SHAPE)"
  window="10m"; [[ "$SHAPE" == "burst" ]] && window="5m"
  peak=$(prom_range_max "sum(rate(http_reqs_total[1m]))" "$window")
  [[ -n "$peak" ]] && pass "Peak req/s (max over $window): $peak" \
                   || info "$SHAPE: rate not yet in Prometheus"
fi

# ── Scenario-specific checks ──────────────────────────────────────────────────
check_ingest() {
  header "Kafka topic and message count"
  check_kafka_topic
  check_kafka_messages 1
  header "OpenSearch index (source)"
  check_opensearch_docs nyc_taxis 1 source
  header "Prometheus: ingest metrics"
  prom_check_counter "ingest_bulk_requests_total"       "ingest_bulk_requests_total"
  prom_check_counter "ingest_single_doc_requests_total" "ingest_single_doc_requests_total"
  prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
  prom_check_latency_p95 "bulk_write" 3000
  prom_check_latency_p95 "single_doc" 2000
}

check_search() {
  header "OpenSearch index (source)"
  check_opensearch_docs nyc_taxis 1 source
  header "Scroll context leak check"
  open_contexts=$(os_query source "/_nodes/stats/indices/search?filter_path=**.open_contexts" \
    | python3 -c "import sys,json,re; d=json.load(sys.stdin); print(max([n['indices']['search']['open_contexts'] for n in d.get('nodes',{}).values()] or [0]))" 2>/dev/null || echo "?")
  if [[ "$open_contexts" == "0" ]]; then
    pass "No leaked scroll contexts (open_contexts=0)"
  elif [[ "$open_contexts" =~ ^[0-9]+$ ]]; then
    info "open_contexts=$open_contexts — may include in-flight scrolls; re-check after runs settle"
  else
    info "Could not read open_contexts"
  fi
  header "Prometheus: search metrics"
  prom_check_counter "search_flat_requests_total" "search_flat_requests_total"
  prom_check_counter "search_agg_requests_total"  "search_agg_requests_total"
  prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
  prom_check_latency_p95 "flat_search" 2000
  prom_check_latency_p95 "agg_search"  5000
  if $DEEP_PAGING; then
    header "Prometheus: deep-paging metrics"
    prom_check_counter "search_scroll_sequences_total" "search_scroll_sequences_total"
    prom_check_counter "search_after_sequences_total"  "search_after_sequences_total"
    prom_check_latency_p95 "scroll_page"       5000
    prom_check_latency_p95 "search_after_page" 5000
  fi
}

check_mixed() {
  header "Webdis PING"
  webdis_raw=$(kcurl -sf "http://webdis:7379/PING" 2>/dev/null || echo "")
  if echo "$webdis_raw" | grep -q "PONG"; then
    pass "Webdis reachable (PING → PONG)"
  else
    info "Webdis PING response: '${webdis_raw:-none}' (needs the chart installed with registry.enabled=true)"
  fi
  header "OpenSearch index (source)"
  check_opensearch_docs nyc_taxis 1 source
  header "Kafka topic and message count"
  check_kafka_topic
  check_kafka_messages 1
  header "Prometheus: ingest metrics"
  prom_check_counter "mixed_ingest_bulk_requests_total"   "mixed_ingest_bulk_requests_total"
  prom_check_counter "mixed_ingest_single_requests_total" "mixed_ingest_single_requests_total"
  prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
  prom_check_latency_p95 "bulk_write" 3000
  prom_check_latency_p95 "single_doc" 2000
  header "Prometheus: search metrics"
  prom_check_counter "mixed_search_flat_requests_total" "mixed_search_flat_requests_total"
  prom_check_counter "mixed_search_agg_requests_total"  "mixed_search_agg_requests_total"
  prom_check_latency_p95 "flat_search" 2000
  prom_check_latency_p95 "agg_search"  5000
  header "Prometheus: mixed consistency metrics"
  prom_check_counter "mixed_search_consistency_reads_total"  "mixed_search_consistency_reads_total"
  prom_check_counter "mixed_search_consistency_misses_total" "mixed_search_consistency_misses_total"
}

check_sequences() {
  header "Kafka topic and message count"
  check_kafka_topic
  check_kafka_messages 1
  header "OpenSearch index (source)"
  check_opensearch_docs nyc_taxis 1 source
  header "Prometheus: sequence metrics"
  prom_check_counter "ingest_sequence_requests_total" "ingest_sequence_requests_total"
  prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
  for step in seq_create seq_update seq_query seq_delete; do
    prom_check_latency_p95 "$step" 2000
  done
  header "Prometheus: ingest regression (bulk + single-doc)"
  prom_check_latency_p95 "bulk_write" 3000
  prom_check_latency_p95 "single_doc" 2000
  header "Replayer ordering (informational)"
  info "  Run with --spread and inspect the replayer logs for out-of-order sequence replays:"
  info "  kubectl -n $NAMESPACE logs deploy/replayer"
}

"check_$SCENARIO"

print_summary
