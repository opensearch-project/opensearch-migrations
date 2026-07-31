#!/usr/bin/env bash
# Validates the search profile: queries, aggregations, optional deep paging (scroll/search_after).
# Assumes the data plane is already up (deployWorkflowComponents.sh up).
#
# Usage:
#   ./scripts/run_test_search.sh                       # checks only
#   ./scripts/run_test_search.sh --run                 # seed (ingest) + search, then check
#   ./scripts/run_test_search.sh --run --deep-paging   # also run the deep-paging preset

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

WITH_RUN=false; WITH_DEEP_PAGING=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run) WITH_RUN=true ;;
    --deep-paging) WITH_DEEP_PAGING=true ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
  shift
done

echo -e "\n${BOLD}Search Profile Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

header "Step 1 — Capture Proxy reachable"
check_proxy_ready

if $WITH_RUN; then
  header "Step 2 — Seeding documents (k6 ingest)"
  run_k6 --scenario ingest --config ingest-steady
  header "Step 3 — Running k6 search scenario"
  run_k6 --scenario search --config search-steady
  if $WITH_DEEP_PAGING; then
    header "Step 3b — Running k6 search deep-paging scenario"
    run_k6 --scenario search --config search-deep-paging
  fi
else
  info "Skipping k6 runs (use --run to submit them first)."
fi

header "Step 4 — Service health"
check_service_health "" kafka opensearch-source capture-proxy

header "Step 5 — OpenSearch index (source)"
check_opensearch_docs nyc_taxis 1 source

header "Step 6 — Scroll context leak check"
open_contexts=$(os_query source "/_nodes/stats/indices/search?filter_path=**.open_contexts" \
  | python3 -c "import sys,json,re; d=json.load(sys.stdin); print(max([n['indices']['search']['open_contexts'] for n in d.get('nodes',{}).values()] or [0]))" 2>/dev/null || echo "?")
if [[ "$open_contexts" == "0" ]]; then
  pass "No leaked scroll contexts (open_contexts=0)"
elif [[ "$open_contexts" =~ ^[0-9]+$ ]]; then
  info "open_contexts=$open_contexts — may include in-flight scrolls; re-check after runs settle"
else
  info "Could not read open_contexts"
fi

header "Step 7 — Prometheus: search metrics"
prom_check_counter "search_flat_requests_total" "search_flat_requests_total"
prom_check_counter "search_agg_requests_total"  "search_agg_requests_total"
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "flat_search"   2000
prom_check_latency_p95 "agg_search"    5000

if $WITH_DEEP_PAGING; then
  header "Step 8 — Prometheus: deep-paging metrics"
  prom_check_counter "search_scroll_sequences_total"  "search_scroll_sequences_total"
  prom_check_counter "search_after_sequences_total"   "search_after_sequences_total"
  prom_check_latency_p95 "scroll_page"       5000
  prom_check_latency_p95 "search_after_page" 5000
fi

print_summary
