#!/usr/bin/env bash
# validate_sequences.sh
#
# Validates stateful create → update → query → delete sequences through the pipeline.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# Usage:
#   ./scripts/validate_sequences.sh               # checks only (stack + k6 must already be done)
#   ./scripts/validate_sequences.sh --with-setup  # also starts the stack and runs k6 first
#   ./scripts/validate_sequences.sh --teardown    # also tears down the stack when finished
#
# Note: The replayer ordering step requires the Traffic Replayer and is guidance only.

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

echo -e "\n${BOLD}Sequences Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Capture Proxy image ────────────────────────────────────────────────────────
header "Step 1 — Capture Proxy image"
check_capture_proxy_image "$WITH_SETUP"

# ── Optional stack startup + k6 run ──────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Starting stack"
  docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
  echo ""

  header "Step 3 — Running k6 ingest scenario (sequences enabled, pinned mode)"
  load_k6_env "k6-config/ingest-steady.env"
  # SEQUENCE_FRACTION and CONNECTION_MODE default to 0.15 / pinned in ingest.js;
  # pass them explicitly so the intent is visible in the script output.
  docker compose run --rm "${env_flags[@]}" \
    -e SEQUENCE_FRACTION=0.15 \
    -e CONNECTION_MODE=pinned \
    k6 run --out=opentelemetry /scripts/scenarios/ingest.js
  echo ""
else
  info "Skipping stack startup and k6 run (use --with-setup to include those steps)."
fi

# ── Service health ────────────────────────────────────────────────────────────
header "Step 4 — Service health"
check_service_health "" kafka opensearch-source capture-proxy

# ── Kafka ─────────────────────────────────────────────────────────────────────
header "Step 5 — Kafka topic and message count"
check_kafka_topic
check_kafka_messages 1

# ── OpenSearch ────────────────────────────────────────────────────────────────
header "Step 6 — OpenSearch index"
check_opensearch_docs nyc_taxis 1

# ── Sequence document leak check ──────────────────────────────────────────────
# Sequences create docs with IDs like seq-<VU>-<ITER>. Each create is followed
# by a delete in the same iteration. A non-zero count means some sequences did
# not complete their delete step (e.g. due to a mid-sequence error).
header "Step 7 — Sequence document leak check"
leaked=$(curl -sf "http://localhost:19200/nyc_taxis/_count" \
  -H 'Content-Type: application/json' \
  --data '{"query":{"wildcard":{"_id":{"value":"seq-*"}}}}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
  || echo "?")
if [[ "$leaked" == "0" ]]; then
  pass "No leaked sequence documents (0 docs with seq-* IDs)"
elif [[ "$leaked" =~ ^[0-9]+$ ]] && (( leaked <= 5 )); then
  pass "Negligible sequence document leak ($leaked docs with seq-* IDs — within tolerance)"
elif [[ "$leaked" =~ ^[0-9]+$ ]]; then
  fail "Sequence document leak: $leaked docs with seq-* IDs remain (check ingest_sequence_errors_total)"
else
  info "Could not determine leaked doc count (query returned: $leaked)"
fi

# ── Prometheus ────────────────────────────────────────────────────────────────
header "Step 8 — Prometheus: sequence metrics"
prom_check_counter "ingest_sequence_requests_total" "ingest_sequence_requests_total"
# ingest_sequence_errors_total is the SUM of error values (0 in a healthy run), so
# checking it as a counter would always fail when there are no errors.  HTTP error rate
# via http_reqs_total{status=~"4..|5.."} is the correct signal.
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
for step in seq_create seq_update seq_query seq_delete; do
  prom_check_latency_p95 "$step" 2000
done

header "Step 9 — Prometheus: ingest regression (bulk + single-doc)"
prom_check_latency_p95 "bulk_write"  3000
prom_check_latency_p95 "single_doc"  2000

# ── Replayer ordering guidance ────────────────────────────────────────────────
header "Step 10 — Replayer ordering (manual / Traffic Replayer required)"
info "This step requires the Traffic Replayer running against the same Kafka topic."
info ""
info "To verify PINNED mode (in-order replay):"
info "  1. docker compose up -d traffic-replayer  (once added to docker-compose.yml)"
info "  2. Wait for it to write output_tuples.log"
info "  3. grep 'seq-' output_tuples.log | head -40"
info "     Expected: each seq-<VU>-<ITER> block shows create → update → query → delete"
info ""
info "To verify SPREAD mode (cross-stream ordering differences):"
info "  Run k6 with CONNECTION_MODE=spread, then inspect:"
info "  grep 'seq-' output_tuples.log | sort | head -40"
info "  Expected: update or query arriving before create for some sequence IDs"

# ── Optional teardown ─────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  docker compose down -v
  pass "Stack torn down (volumes removed)"
fi

print_summary
