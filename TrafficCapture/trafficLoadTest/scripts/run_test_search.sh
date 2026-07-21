#!/usr/bin/env bash
# run_test_search.sh
#
# Validates search scenarios: flat search, aggregation, deep-paging (scroll + search_after).
# Must be run from the TrafficCapture/trafficLoadTest/ directory.
#
# Usage:
#   ./scripts/run_test_search.sh                  # checks only
#   ./scripts/run_test_search.sh --with-setup     # also starts stack and runs k6
#   ./scripts/run_test_search.sh --deep-paging    # include deep-paging env run + checks
#   ./scripts/run_test_search.sh --teardown       # tear down after checks

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

# ── Argument parsing ──────────────────────────────────────────────────────────
WITH_SETUP=false; WITH_TEARDOWN=false; WITH_DEEP_PAGING=false
for arg in "$@"; do
  case $arg in
    --with-setup)   WITH_SETUP=true ;;
    --teardown)     WITH_TEARDOWN=true ;;
    --deep-paging)  WITH_DEEP_PAGING=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "\n${BOLD}Search Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Capture Proxy image ────────────────────────────────────────────────────────
header "Step 1 — Capture Proxy image"
check_capture_proxy_image "$WITH_SETUP"

# ── Optional stack startup + k6 run ──────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Starting stack (clean volumes)"
  # Wipe volumes to ensure a clean index state before seeding docs for search.
  "${DOCKER_COMPOSE[@]}" down -v 2>/dev/null || true
  "${DOCKER_COMPOSE[@]}" up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
  echo ""

  header "Step 3 — Running k6 ingest (seed documents)"
  load_k6_env "k6-config/ingest-steady.env"
  "${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
    k6 run --out=opentelemetry /scripts/scenarios/ingest.js
  echo ""

  header "Step 4 — Running k6 search scenario"
  load_k6_env "k6-config/search-steady.env"
  "${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
    k6 run --out=opentelemetry /scripts/scenarios/search.js
  echo ""

  if $WITH_DEEP_PAGING; then
    header "Step 4b — Running k6 search deep-paging scenario"
    load_k6_env "k6-config/search-deep-paging.env"
    "${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
      k6 run --out=opentelemetry /scripts/scenarios/search.js
    echo ""
  fi
else
  info "Skipping stack startup and k6 run (use --with-setup to include those steps)."
fi

# ── Service health ────────────────────────────────────────────────────────────
header "Step 5 — Service health"
check_service_health "" kafka opensearch-source capture-proxy

# ── OpenSearch ────────────────────────────────────────────────────────────────
header "Step 6 — OpenSearch index"
check_opensearch_docs nyc_taxis 1

# ── Scroll context leak check ─────────────────────────────────────────────────
# Open scroll contexts persist until explicitly cleared or TTL expires.
# More than a handful of lingering contexts is a sign the search.js scroll
# finaliser isn't clearing them reliably.
header "Step 7 — Scroll context leak check"
scroll_contexts=$(curl -sf "http://localhost:19200/_nodes/stats/indices" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
total = sum(
  n.get('indices', {}).get('search', {}).get('open_contexts', 0)
  for n in d.get('nodes', {}).values()
)
print(total)
" 2>/dev/null || echo "?")
if [[ "$scroll_contexts" == "0" ]]; then
  pass "No open scroll contexts"
elif [[ "$scroll_contexts" =~ ^[0-9]+$ ]] && (( scroll_contexts <= 5 )); then
  pass "Negligible open scroll contexts: $scroll_contexts (within tolerance)"
elif [[ "$scroll_contexts" =~ ^[0-9]+$ ]]; then
  fail "Open scroll contexts: $scroll_contexts — search.js may not be clearing scroll IDs"
else
  info "Could not determine open scroll context count"
fi

# ── Prometheus ────────────────────────────────────────────────────────────────
header "Step 8 — Prometheus: search metrics"
prom_check_counter "search_flat_requests_total" "search_flat_requests_total"
prom_check_counter "search_agg_requests_total"  "search_agg_requests_total"
# search_errors_total is the SUM of error values (0 in a healthy run), so checking it
# as a counter would always fail when there are no errors.  HTTP error rate via
# http_reqs_total{status=~"4..|5.."} is the correct signal.
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "flat_search"   2000
prom_check_latency_p95 "agg_search"    5000

if $WITH_DEEP_PAGING; then
  header "Step 9 — Prometheus: deep-paging metrics"
  prom_check_counter "search_scroll_sequences_total"  "search_scroll_sequences_total"
  prom_check_counter "search_after_sequences_total"   "search_after_sequences_total"
  prom_check_latency_p95 "scroll_page"       5000
  prom_check_latency_p95 "search_after_page" 5000
fi

# ── Replayer memory guidance ──────────────────────────────────────────────────
header "Step 10 — Replayer memory guidance (informational)"
info "High scroll/search_after depth stresses the Replayer's connection memory."
info "When running the Traffic Replayer against a search-heavy capture:"
info "  - Monitor JVM heap via: docker stats traffic-replayer"
info "  - Alert if heap > 80% (scroll state is held per in-flight connection)."
info "  - Deep-paging runs with scroll TTL 5m can hold contexts for minutes."
info "  - Prefer search_after over scroll in long-running production replays."

# ── Optional teardown ─────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  "${DOCKER_COMPOSE[@]}" down -v
  pass "Stack torn down (volumes removed)"
fi

print_summary
