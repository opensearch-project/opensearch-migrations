#!/usr/bin/env bash
# validate_load_shapes.sh
#
# Validates non-steady load profiles: ramp-up and burst.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# Usage:
#   ./scripts/validate_load_shapes.sh                     # all shapes (ramp + burst + mixed-ramp)
#   ./scripts/validate_load_shapes.sh --with-burst        # burst shape only
#   ./scripts/validate_load_shapes.sh --with-mixed-ramp   # mixed-ramp shape only
#   ./scripts/validate_load_shapes.sh --with-setup        # start stack before running
#   ./scripts/validate_load_shapes.sh --teardown          # tear down after checks

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

# ── Argument parsing ──────────────────────────────────────────────────────────
WITH_SETUP=false; WITH_TEARDOWN=false
WITH_BURST=false; WITH_MIXED_RAMP=false; ALL_SHAPES=true
for arg in "$@"; do
  case $arg in
    --with-setup)      WITH_SETUP=true ;;
    --teardown)        WITH_TEARDOWN=true ;;
    --with-burst)      WITH_BURST=true; ALL_SHAPES=false ;;
    --with-mixed-ramp) WITH_MIXED_RAMP=true; ALL_SHAPES=false ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done
if $ALL_SHAPES; then WITH_BURST=true; WITH_MIXED_RAMP=true; fi

echo -e "\n${BOLD}Load Shapes Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Capture Proxy image ────────────────────────────────────────────────────────
header "Step 1 — Capture Proxy image"
check_capture_proxy_image "$WITH_SETUP"

# ── Optional stack startup ────────────────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Starting stack"
  "${DOCKER_COMPOSE[@]}" up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
  echo ""
else
  info "Skipping stack startup (use --with-setup to include that step)."
fi

# ── Ramp run ──────────────────────────────────────────────────────────────────
header "Step 3 — Ramp load shape"
load_k6_env "k6-config/ingest-ramp.env"
"${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
  k6 run --out=opentelemetry /scripts/scenarios/ingest.js
echo ""

check_service_health "(post-ramp)" kafka opensearch-source capture-proxy
check_kafka_messages 1

ramp_rate_variation=$(prom_range_max "http_reqs_total" "10m")
if [[ -n "$ramp_rate_variation" && $(python3 -c "print(1 if float('$ramp_rate_variation') > 0 else 0)") == "1" ]]; then
  pass "Ramp: non-zero rate variation detected (max_over_time=$ramp_rate_variation req/s)"
else
  fail "Ramp: could not verify rate variation (prom_range_max returned: '${ramp_rate_variation:-empty}')"
fi

# ── Burst run ─────────────────────────────────────────────────────────────────
if $WITH_BURST; then
  header "Step 4 — Burst load shape"

  offset_before=$(kafka_total_offset)

  load_k6_env "k6-config/ingest-burst.env"
  "${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
    k6 run --out=opentelemetry /scripts/scenarios/ingest.js
  echo ""

  offset_after=$(kafka_total_offset)
  offset_delta=$(( offset_after - offset_before ))

  check_service_health "(post-burst)" kafka opensearch-source capture-proxy

  if (( offset_delta > 0 )); then
    pass "Burst: $offset_delta new Kafka messages produced during burst run"
  else
    fail "Burst: no new Kafka messages during burst run (offset before=$offset_before, after=$offset_after)"
  fi

  burst_rate=$(prom_range_max "http_reqs_total" "5m")
  info "Burst peak rate (last 5m): ${burst_rate:-unknown} req/s"
fi

# ── Mixed ramp run ────────────────────────────────────────────────────────────
if $WITH_MIXED_RAMP; then
  header "Step 5 — Mixed ramp load shape"

  load_k6_env "k6-config/mixed-ramp.env"
  "${DOCKER_COMPOSE[@]}" run --rm "${env_flags[@]}" \
    k6 run --out=opentelemetry /scripts/scenarios/mixed.js
  echo ""

  check_service_health "(post-mixed-ramp)" kafka opensearch-source capture-proxy

  mixed_rate_variation=$(prom_range_max "http_reqs_total" "10m")
  if [[ -n "$mixed_rate_variation" && $(python3 -c "print(1 if float('$mixed_rate_variation') > 0 else 0)") == "1" ]]; then
    pass "Mixed ramp: non-zero rate variation detected (max=$mixed_rate_variation req/s)"
  else
    fail "Mixed ramp: could not verify rate variation (got: '${mixed_rate_variation:-empty}')"
  fi
fi

# ── Final Prometheus spot checks ──────────────────────────────────────────────
header "Step 6 — Prometheus: error rate across all shape runs"
# ingest_errors_total is the SUM of error values (0 in a healthy run), so checking it
# as a counter would always fail when there are no errors.  HTTP error rate via
# http_reqs_total{status=~"4..|5.."} is the correct signal.
prom_check_http_error_rate 0.05 "HTTP error rate (4xx/5xx)"
prom_check_latency_p95 "bulk_write" 3000

# ── Optional teardown ─────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  "${DOCKER_COMPOSE[@]}" down -v
  pass "Stack torn down (volumes removed)"
fi

print_summary
