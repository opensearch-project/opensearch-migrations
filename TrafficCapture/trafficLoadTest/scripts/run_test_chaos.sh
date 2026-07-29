#!/usr/bin/env bash
# run_test_chaos.sh
#
# Validates the runtime control plane: pause/resume and dynamic rate changes
# via Webdis → Redis signals read by k6's lib/control.js.
# Must be run from the TrafficCapture/trafficLoadTest/ directory.
#
# Usage:
#   ./scripts/run_test_chaos.sh               # chaos checks only (k6 must already be running)
#   ./scripts/run_test_chaos.sh --with-setup  # start stack + k6 background process, then check
#   ./scripts/run_test_chaos.sh --teardown    # kill background k6 and tear down when done

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

K6_PID_FILE="/tmp/k6-chaos.pid"
K6_LOG="/tmp/k6-chaos.log"

# ── Webdis control helpers ────────────────────────────────────────────────────
# Write a value to a Redis key via Webdis.
# Values with colons (e.g. "set-rate:10") must be URL-encoded as "set-rate%3A10"
# since Webdis URL-decodes path segments before forwarding to Redis.
webdis_set() {
  local key="$1" value="$2"
  curl -sf "http://localhost:7379/SET/${key}/${value}" >/dev/null
}

# Read a value back from Redis via Webdis (returns empty string on error or missing key).
webdis_get() {
  local key="$1"
  curl -sf "http://localhost:7379/GET/${key}" 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('GET') or '')" 2>/dev/null \
    || echo ''
}

echo -e "\n${BOLD}Chaos / Control-Plane Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

# ── Capture Proxy image ────────────────────────────────────────────────────────
header "Step 1 — Capture Proxy image"
check_capture_proxy_image "$WITH_SETUP"

# ── Optional stack startup + background k6 ───────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Starting stack (including Redis + Webdis)"
  "${DOCKER_COMPOSE[@]}" up -d --wait \
    kafka opensearch-source capture-proxy otel-collector prometheus grafana \
    redis webdis
  echo ""

  header "Step 3 — Starting k6 in background (ingest-steady)"
  load_k6_env "k6-config/ingest-steady.env"

  # Remove any stale container from a previous run before starting a new one.
  docker rm -f k6-chaos 2>/dev/null || true

  # Run WITHOUT -d so the shell holds a live PID for clean teardown and so
  # all -e flags are reliably inherited (docker compose run -d has quirks with
  # env var delivery in some Compose versions).
  # -T: disable TTY allocation (background process, stdout → file).
  # --no-thresholds: pause inflates p95 latency beyond k6's
  # threshold limits, causing k6 to abort before chaos tests complete.
  "${DOCKER_COMPOSE[@]}" run --rm -T --name k6-chaos "${env_flags[@]}" \
    -e CONTROL_ENABLED=true \
    k6 run --out=opentelemetry \
           --no-thresholds \
           /scripts/scenarios/ingest.js > "$K6_LOG" 2>&1 &
  echo $! > "$K6_PID_FILE"
  info "k6 started (PID $(cat "$K6_PID_FILE"), log: $K6_LOG)"

  # Poll until k6 is confirmed producing Kafka traffic (signals that setup()
  # finished and VU iterations are live). kafka_total_offset takes ~5s per call;
  # minimum wait is therefore ~10s — sufficient for k6 init + index setup().
  info "Waiting for k6 to start producing traffic..."
  _baseline=$(kafka_total_offset)
  _ready=false
  for _i in {1..12}; do
    sleep 5
    _cur=$(kafka_total_offset)
    if (( _cur > _baseline + 5 )); then
      info "k6 is live (Kafka offset: $_baseline → $_cur)"
      _ready=true
      break
    fi
  done
  if ! $_ready; then
    fail "k6 did not produce traffic within 60 seconds — check $K6_LOG"
    print_summary; exit 1
  fi
  sleep 3  # let rate stabilize at full speed before chaos begins
  echo ""
else
  info "Skipping stack startup and k6 launch (use --with-setup to include those steps)."
fi

# ── Service health ────────────────────────────────────────────────────────────
header "Step 4 — Service health"
check_service_health "" kafka opensearch-source capture-proxy redis webdis

# ── Pause scenario ────────────────────────────────────────────────────────────
header "Step 5 — Pause"
offset_before_pause=$(kafka_total_offset)

webdis_set "control_cmd" "pause"
# Verify the key landed in Redis before sleeping.
_stored=$(webdis_get "control_cmd")
if [[ "$_stored" == "pause" ]]; then
  info "Pause signal verified in Redis (control_cmd=pause)"
else
  info "Unexpected Webdis GET after SET: '${_stored}' (expected 'pause') — control may not work"
fi
sleep 8  # allow k6 to read the flag and stop sending

offset_after_pause=$(kafka_total_offset)
pause_delta=$(( offset_after_pause - offset_before_pause ))

if (( pause_delta < 20 )); then
  pass "Kafka messages during pause: $pause_delta (expected ~0 — in-flight requests may complete)"
else
  fail "Kafka messages during pause: $pause_delta — k6 may not have respected the pause signal"
fi

# ── Resume scenario ───────────────────────────────────────────────────────────
header "Step 6 — Resume"
offset_before_resume=$(kafka_total_offset)

webdis_set "control_cmd" "resume"
info "Sent resume signal (control_cmd=resume)"
sleep 10  # allow k6 to restart sending

offset_after_resume=$(kafka_total_offset)
resume_delta=$(( offset_after_resume - offset_before_resume ))

if (( resume_delta > 0 )); then
  pass "Kafka messages after resume: $resume_delta (k6 is producing again)"
else
  fail "Kafka messages after resume: $resume_delta — k6 may not have resumed"
fi

# ── Rate throttle scenario ────────────────────────────────────────────────────
header "Step 7 — Rate throttle (set-rate:10)"
offset_before_throttle=$(kafka_total_offset)

# Colon must be URL-encoded as %3A so Webdis parses the path correctly.
# Redis stores "set-rate:10"; k6 reads it back decoded and matches cmd.startsWith('set-rate:').
webdis_set "control_cmd" "set-rate%3A10"
info "Sent set-rate:10 signal (control_cmd=set-rate:10, encoded as set-rate%3A10 in URL)"
sleep 15  # observe throttled throughput

offset_after_throttle=$(kafka_total_offset)
throttle_delta=$(( offset_after_throttle - offset_before_throttle ))

# At 10 rps for 15s the expected window is ≈150 messages; full rate would be ≈750.
info "Messages during 15s throttle window: $throttle_delta (10 rps target ≈ 150; full rate ≈ 750)"
if (( throttle_delta > 0 )); then
  pass "k6 continued producing during throttle window: $throttle_delta messages in 15s"
else
  fail "No messages during throttle window — k6 may have stopped"
fi

# ── Prometheus: k6 metrics pipeline ──────────────────────────────────────────
# Verify k6 → OTLP → otel-collector → Prometheus scrape pipeline is live.
# A range subquery ([5m:1m]) fails here because the entire chaos run is < 5 min
# and all evaluation points before k6 started are empty.  A plain instant
# counter sum is both simpler and more robust for a short test window.
header "Step 8 — Prometheus: k6 metrics pipeline"
prom_check_counter "sum(http_reqs_total)" "HTTP requests visible in Prometheus (OTLP pipeline working)"

# ── Optional teardown ─────────────────────────────────────────────────────────
if $WITH_TEARDOWN; then
  header "Teardown"
  if [[ -f "$K6_PID_FILE" ]]; then
    kill "$(cat "$K6_PID_FILE")" 2>/dev/null || true
    wait "$(cat "$K6_PID_FILE")" 2>/dev/null || true
    rm -f "$K6_PID_FILE"
    info "Background k6 process stopped"
  fi
  "${DOCKER_COMPOSE[@]}" down -v
  pass "Stack torn down (volumes removed)"
fi

print_summary
