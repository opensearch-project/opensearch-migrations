#!/usr/bin/env bash
# validate_phase_5.sh
#
# Validates Phase 5 load shapes against a live stack.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# Flags (combinable):
#   --with-setup       Reset stack, start services, run the ramp scenario
#   --with-burst       Run the burst scenario and check stack survives the spike
#   --with-mixed-ramp  Start Redis+Webdis, run the mixed-ramp scenario, check
#                      ingest+search metrics and MIN_RING_FILL delay effectiveness
#   --teardown         Tear the stack down when all checks are done
#
# Examples:
#   ./scripts/validate_phase_5.sh                                          # checks only
#   ./scripts/validate_phase_5.sh --with-setup                            # ramp only
#   ./scripts/validate_phase_5.sh --with-setup --with-burst               # ramp + burst
#   ./scripts/validate_phase_5.sh --with-setup --with-mixed-ramp          # ramp + mixed
#   ./scripts/validate_phase_5.sh --with-setup --with-burst --with-mixed-ramp --teardown

set -euo pipefail

# ── Helpers ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'

PASS=0; FAIL=0

pass()   { echo -e "  ${GREEN}✓ PASS${NC}  $1"; (( PASS++ )) || true; }
fail()   { echo -e "  ${RED}✗ FAIL${NC}  $1"; (( FAIL++ )) || true; }
info()   { echo -e "  ${YELLOW}ℹ${NC}      $1"; }
header() { echo -e "\n${BOLD}$1${NC}"; }

prom_query() {
  curl -sf "http://localhost:19090/api/v1/query" \
    --data-urlencode "query=$1" 2>/dev/null \
    | python3 -c "
import sys, json
d = json.load(sys.stdin)
r = d['data']['result']
print(r[0]['value'][1] if r else '')
" 2>/dev/null || true
}

# Max value of a metric over the last N minutes (subquery via max_over_time).
# Usage: prom_range_max "metric_expr" "5m"
prom_range_max() {
  prom_query "max_over_time(($1)[${2:-10m}:1m])"
}

# Current total end offset across all partitions of logging-traffic-topic.
kafka_total_offset() {
  local total=0 line count
  while IFS= read -r line; do
    count=$(echo "$line" | awk -F: '{print $3}' | tr -d '[:space:]')
    [[ "$count" =~ ^[0-9]+$ ]] && (( total += count )) || true
  done < <(docker compose exec -T kafka \
    /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server localhost:9092 \
    --topic logging-traffic-topic 2>/dev/null || true)
  echo "$total"
}

check_service_health() {
  local label="$1"; shift
  for svc in "$@"; do
    local status
    status=$(docker compose ps --format json "$svc" 2>/dev/null \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health','unknown'))" 2>/dev/null \
      || docker inspect --format '{{.State.Health.Status}}' \
         "$(docker compose ps -q "$svc" 2>/dev/null)" 2>/dev/null \
      || echo "unknown")
    if [[ "$status" == "healthy" ]]; then
      pass "$svc healthy ${label}"
    else
      fail "$svc not healthy ${label}(status: $status)"
    fi
  done
}

# ── Argument parsing ───────────────────────────────────────────────────────────
WITH_SETUP=false
WITH_BURST=false
WITH_MIXED_RAMP=false
WITH_TEARDOWN=false
for arg in "$@"; do
  case $arg in
    --with-setup)      WITH_SETUP=true ;;
    --with-burst)      WITH_BURST=true ;;
    --with-mixed-ramp) WITH_MIXED_RAMP=true ;;
    --teardown)        WITH_TEARDOWN=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "\n${BOLD}Phase 5 Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"
echo "Profiles: ramp$(${WITH_BURST} && echo ' + burst' || true)$(${WITH_MIXED_RAMP} && echo ' + mixed-ramp' || true)"

# ── Step 1: capture proxy image ────────────────────────────────────────────────
header "Step 1 — Capture Proxy image"

CAPTURE_IMAGE="migrations/capture_proxy:latest"
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DOCKER_SOLUTION="$PROJECT_ROOT/TrafficCapture/dockerSolution"

if docker image inspect "$CAPTURE_IMAGE" &>/dev/null; then
  pass "$CAPTURE_IMAGE found locally"
else
  if $WITH_SETUP; then
    info "$CAPTURE_IMAGE not found — building via buildDockerImages (this may take a few minutes)"
    (cd "$DOCKER_SOLUTION" && "$PROJECT_ROOT/gradlew" buildDockerImages)
    if docker image inspect "$CAPTURE_IMAGE" &>/dev/null; then
      pass "$CAPTURE_IMAGE built successfully"
    else
      fail "$CAPTURE_IMAGE still not found after build — check Gradle output above"
      exit 1
    fi
  else
    fail "$CAPTURE_IMAGE not found locally"
    echo -e "       Build it with:\n         cd TrafficCapture/dockerSolution && ../../../gradlew buildDockerImages"
    echo -e "       Or re-run with --with-setup to build automatically."
    exit 1
  fi
fi

# ── Step 2: stack startup + ramp run ──────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Resetting stack and running ramp scenario (~6 minutes)"
  docker compose down -v 2>/dev/null || true
  docker compose up -d --wait kafka opensearch-source capture-proxy otel-collector prometheus grafana
  echo ""

  info "Running ingest-ramp.env (0→150 req/s over 2m, hold 3m, ramp-down 1m) …"
  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < k6-config/ingest-ramp.env
  k6_exit=0
  docker compose run --rm "${env_flags[@]}" \
    k6 run --out=experimental-prometheus-rw /scripts/scenarios/ingest.js || k6_exit=$?
  if (( k6_exit == 0 )); then
    info "k6 exited 0 — all thresholds passed"
  else
    info "k6 exited $k6_exit — threshold breach near saturation point (informational)"
  fi
  echo ""
else
  info "Skipping stack startup and ramp run (use --with-setup to include)."
fi

# ── Step 3: service health (after ramp) ───────────────────────────────────────
header "Step 3 — Service health"
check_service_health "" kafka opensearch-source capture-proxy

# ── Step 4: Kafka messages (ramp baseline) ─────────────────────────────────────
header "Step 4 — Kafka: topic and message count"

if docker compose exec -T kafka \
     /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null \
   | grep -q "logging-traffic-topic"; then
  pass "logging-traffic-topic exists"
else
  fail "logging-traffic-topic not found"
fi

ramp_total=$(kafka_total_offset)
# Ramp profile (0→150→0 over 6m, avg ~75 req/s) should produce ≥5000 messages.
if (( ramp_total >= 5000 )); then
  pass "Kafka end offset: $ramp_total messages (ramp baseline)"
elif (( ramp_total > 0 )); then
  fail "Kafka end offset: only $ramp_total messages — expected ≥5000 for a full 6-minute ramp"
else
  fail "Kafka end offset is 0 — no messages written"
fi

# ── Step 5: OpenSearch documents ──────────────────────────────────────────────
header "Step 5 — OpenSearch index"

doc_count=$(curl -sf "http://localhost:19200/nyc_taxis/_count" \
  | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
  || echo 0)
if (( doc_count > 0 )); then
  pass "nyc_taxis index contains $doc_count documents"
else
  fail "nyc_taxis index has 0 documents"
fi

# ── Step 6: Prometheus — ramp ingest metrics ───────────────────────────────────
header "Step 6 — Prometheus: ramp ingest metrics"

bulk_total=$(prom_query "k6_ingest_bulk_requests_total")
if [[ -n "$bulk_total" && "$bulk_total" != "0" ]]; then
  pass "k6_ingest_bulk_requests_total = $bulk_total"
else
  fail "k6_ingest_bulk_requests_total not found or 0"
fi

single_total=$(prom_query "k6_ingest_single_doc_requests_total")
if [[ -n "$single_total" && "$single_total" != "0" ]]; then
  pass "k6_ingest_single_doc_requests_total = $single_total"
else
  fail "k6_ingest_single_doc_requests_total not found or 0"
fi

ingest_err=$(prom_query "k6_ingest_errors_rate")
if [[ -n "$ingest_err" ]]; then
  ingest_err_pct=$(python3 -c "print(f'{float(\"$ingest_err\")*100:.2f}')" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$ingest_err') < 0.05 else 1)" 2>/dev/null; then
    pass "k6_ingest_errors_rate = ${ingest_err_pct}% (<5%)"
  else
    fail "k6_ingest_errors_rate = ${ingest_err_pct}% — exceeds 5%; proxy may have saturated during hold"
  fi
else
  fail "k6_ingest_errors_rate not found in Prometheus"
fi

# ── Step 7: rate variation (confirms ramping executor ran) ─────────────────────
header "Step 7 — Prometheus: rate variation (ramping executor confirmed)"

# irate over a 2-minute resolution window; max over the last 10 minutes.
ramp_peak=$(prom_range_max \
  "irate(k6_ingest_bulk_requests_total[2m]) + irate(k6_ingest_single_doc_requests_total[2m])" \
  "10m")
if [[ -n "$ramp_peak" ]]; then
  ramp_peak_r=$(python3 -c "print(round(float('$ramp_peak'), 1))" 2>/dev/null || echo "?")
  if python3 -c "import sys; sys.exit(0 if float('$ramp_peak') > 50 else 1)" 2>/dev/null; then
    pass "Ramp peak ingest rate = ${ramp_peak_r} req/s (ramping executor ran above constant-rate baseline)"
  else
    fail "Ramp peak ingest rate = ${ramp_peak_r} req/s — expected >50 req/s during 150 req/s hold"
  fi
else
  info "Peak rate not yet in Prometheus — irate requires ≥2 scrape points"
fi

# ── Step 8: per-op latency (ramp, whole-run p95) ──────────────────────────────
header "Step 8 — Prometheus: per-op latency (ramp)"

declare -A RAMP_LATENCY=([bulk_write]=3000 [single_doc]=2000)
for op in "${!RAMP_LATENCY[@]}"; do
  threshold_ms="${RAMP_LATENCY[$op]}"
  p95=$(prom_query "k6_http_req_duration_p95{name=\"${op}\"}")
  if [[ -n "$p95" ]]; then
    p95_ms=$(python3 -c "print(round(float('$p95') * 1000, 1))" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$p95') * 1000 < ${threshold_ms} else 1)" 2>/dev/null; then
      pass "${op} p95 = ${p95_ms}ms (<${threshold_ms}ms)"
    else
      fail "${op} p95 = ${p95_ms}ms — whole-run p95 exceeds ${threshold_ms}ms (likely driven by hold phase)"
    fi
  else
    info "${op} p95 not yet in Prometheus"
  fi
done

# ── Step 9: burst scenario ─────────────────────────────────────────────────────
if $WITH_BURST; then
  header "Step 9 — Burst scenario (ingest-burst.env, ~4m 30s)"
  info "Baseline: 20 req/s warm-up → 200 req/s spike (30s) → hold (30s) → recover → cool-down"

  pre_burst=$(kafka_total_offset)

  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < k6-config/ingest-burst.env
  k6_exit=0
  docker compose run --rm "${env_flags[@]}" \
    k6 run --out=experimental-prometheus-rw /scripts/scenarios/ingest.js || k6_exit=$?
  if (( k6_exit != 0 )); then
    info "k6 exited $k6_exit — threshold breach expected at 200 req/s spike (saturation finding)"
  else
    info "k6 exited 0 — all thresholds passed; proxy handled the burst without saturation"
  fi

  post_burst=$(kafka_total_offset)
  burst_delta=$(( post_burst - pre_burst ))
  if (( burst_delta > 0 )); then
    pass "Burst produced $burst_delta Kafka messages (proxy stayed alive through the spike)"
  else
    fail "No Kafka messages produced during burst — k6 may not have run or proxy crashed"
  fi

  # Service health: most important post-burst check.
  check_service_health "(post-burst)" kafka opensearch-source capture-proxy

  # Error rate: informational — high rate means saturation was reached, which is the finding.
  burst_err=$(prom_query "k6_ingest_errors_rate")
  if [[ -n "$burst_err" ]]; then
    burst_err_pct=$(python3 -c "print(f'{float(\"$burst_err\")*100:.2f}')" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$burst_err') >= 0.05 else 1)" 2>/dev/null; then
      info "Burst error rate = ${burst_err_pct}% — proxy saturated during spike (saturation point found)"
    else
      info "Burst error rate = ${burst_err_pct}% — proxy absorbed burst without error-rate breach"
    fi
    pass "Burst error rate reported (${burst_err_pct}%)"
  else
    info "Burst error rate not yet in Prometheus"
  fi

  # Peak rate: confirms the spike actually reached above the warm-up baseline.
  # Use a 6m window (covers the ~4.5m burst run); expect >100 req/s peak (between
  # 20 req/s warm-up and 200 req/s spike target).
  burst_peak=$(prom_range_max \
    "irate(k6_ingest_bulk_requests_total[1m]) + irate(k6_ingest_single_doc_requests_total[1m])" \
    "6m")
  if [[ -n "$burst_peak" ]]; then
    burst_peak_r=$(python3 -c "print(round(float('$burst_peak'), 1))" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$burst_peak') > 100 else 1)" 2>/dev/null; then
      pass "Burst peak ingest rate = ${burst_peak_r} req/s (spike reached above 20 req/s warm-up baseline)"
    else
      fail "Burst peak ingest rate = ${burst_peak_r} req/s — expected >100 req/s during 200 req/s spike"
    fi
  else
    info "Burst peak rate not yet in Prometheus"
  fi
fi

# ── Step 10: mixed-ramp scenario ───────────────────────────────────────────────
if $WITH_MIXED_RAMP; then
  header "Step 10 — Mixed-ramp scenario (mixed-ramp.env, ~6 minutes)"
  info "Ingest: 0→80 req/s; Search: 0→50 req/s; MIN_RING_FILL=50 delays search VUs"

  # Bring up Redis + Webdis if not already running.
  docker compose up -d --wait redis webdis
  pass "Redis and Webdis services running"

  pre_mixed=$(kafka_total_offset)

  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < k6-config/mixed-ramp.env
  k6_exit=0
  docker compose run --rm "${env_flags[@]}" \
    k6 run --out=experimental-prometheus-rw /scripts/scenarios/mixed.js || k6_exit=$?
  if (( k6_exit != 0 )); then
    info "k6 exited $k6_exit — possible threshold breach during ramp-up; check per-metric results below"
  else
    info "k6 exited 0 — all thresholds passed"
  fi

  post_mixed=$(kafka_total_offset)
  mixed_delta=$(( post_mixed - pre_mixed ))
  if (( mixed_delta > 0 )); then
    pass "Mixed-ramp produced $mixed_delta Kafka messages"
  else
    fail "No Kafka messages during mixed-ramp — ingest VUs may not have run"
  fi

  # Redis ID ring must have entries — confirms ingest VUs registered single-doc IDs.
  ring_size=$(curl -sf "http://localhost:7379/LLEN/recent_ids" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('LLEN',0))" 2>/dev/null \
    || echo "?")
  if [[ "$ring_size" =~ ^[0-9]+$ ]] && (( ring_size > 0 )); then
    pass "Redis recent_ids ring has $ring_size entries (ingest VUs registered IDs)"
  else
    fail "Redis recent_ids ring is empty or unreachable (got: $ring_size)"
  fi

  # Ingest stream metrics.
  mixed_bulk=$(prom_query "k6_mixed_ingest_bulk_requests_total")
  if [[ -n "$mixed_bulk" && "$mixed_bulk" != "0" ]]; then
    pass "k6_mixed_ingest_bulk_requests_total = $mixed_bulk"
  else
    fail "k6_mixed_ingest_bulk_requests_total not found or 0"
  fi

  mixed_single=$(prom_query "k6_mixed_ingest_single_requests_total")
  if [[ -n "$mixed_single" && "$mixed_single" != "0" ]]; then
    pass "k6_mixed_ingest_single_requests_total = $mixed_single"
  else
    fail "k6_mixed_ingest_single_requests_total not found or 0"
  fi

  mixed_ingest_err=$(prom_query "k6_mixed_ingest_errors_rate")
  if [[ -n "$mixed_ingest_err" ]]; then
    mixed_ingest_err_pct=$(python3 -c "print(f'{float(\"$mixed_ingest_err\")*100:.2f}')" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$mixed_ingest_err') < 0.05 else 1)" 2>/dev/null; then
      pass "k6_mixed_ingest_errors_rate = ${mixed_ingest_err_pct}% (<5%)"
    else
      fail "k6_mixed_ingest_errors_rate = ${mixed_ingest_err_pct}% — exceeds 5%"
    fi
  else
    fail "k6_mixed_ingest_errors_rate not found in Prometheus"
  fi

  # Search stream metrics.
  mixed_flat=$(prom_query "k6_mixed_search_flat_requests_total")
  if [[ -n "$mixed_flat" && "$mixed_flat" != "0" ]]; then
    pass "k6_mixed_search_flat_requests_total = $mixed_flat"
  else
    fail "k6_mixed_search_flat_requests_total not found or 0"
  fi

  mixed_agg=$(prom_query "k6_mixed_search_agg_requests_total")
  if [[ -n "$mixed_agg" && "$mixed_agg" != "0" ]]; then
    pass "k6_mixed_search_agg_requests_total = $mixed_agg"
  else
    fail "k6_mixed_search_agg_requests_total not found or 0"
  fi

  mixed_search_err=$(prom_query "k6_mixed_search_errors_rate")
  if [[ -n "$mixed_search_err" ]]; then
    mixed_search_err_pct=$(python3 -c "print(f'{float(\"$mixed_search_err\")*100:.2f}')" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$mixed_search_err') < 0.05 else 1)" 2>/dev/null; then
      pass "k6_mixed_search_errors_rate = ${mixed_search_err_pct}% (<5%)"
    else
      fail "k6_mixed_search_errors_rate = ${mixed_search_err_pct}% — exceeds 5%"
    fi
  else
    fail "k6_mixed_search_errors_rate not found in Prometheus"
  fi

  # Consistency reads: the key check for MIN_RING_FILL.
  # If > 0, search VUs found IDs in the ring when they started — the startTime
  # delay was long enough for ingest VUs to populate the ring before reads began.
  consistency_reads=$(prom_query "k6_mixed_search_consistency_reads_total")
  if [[ -n "$consistency_reads" && "$consistency_reads" != "0" ]]; then
    pass "k6_mixed_search_consistency_reads_total = $consistency_reads (ring had IDs when search VUs started — MIN_RING_FILL delay effective)"
  else
    fail "k6_mixed_search_consistency_reads_total = 0 — search VUs found no IDs in the ring; increase MIN_RING_FILL or check ingest rate"
  fi

  # Miss rate: informational — quantifies how often search VUs hit an empty ring slot.
  consistency_misses=$(prom_query "k6_mixed_search_consistency_misses_total")
  if [[ -n "$consistency_reads" && -n "$consistency_misses" ]]; then
    miss_pct=$(python3 -c "
reads = float('$consistency_reads') if '$consistency_reads' else 0
misses = float('$consistency_misses') if '$consistency_misses' else 0
total = reads + misses
print(f'{misses/total*100:.1f}' if total > 0 else '0.0')
" 2>/dev/null || echo "?")
    info "Consistency miss rate = ${miss_pct}% (reads=${consistency_reads}, misses=${consistency_misses:-0})"
  fi

  # Service health after mixed run.
  check_service_health "(post-mixed-ramp)" kafka opensearch-source capture-proxy
fi

# ── Optional teardown ──────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  docker compose down -v
  pass "Stack torn down (volumes removed)"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
echo "──────────────────────────────────────────────────"
echo -e "${BOLD}Results: ${GREEN}${PASS} passed${NC}  ${RED}${FAIL} failed${NC}"
echo "──────────────────────────────────────────────────"

if (( FAIL > 0 )); then
  exit 1
fi
