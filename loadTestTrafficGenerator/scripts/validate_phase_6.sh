#!/usr/bin/env bash
# validate_phase_6.sh
#
# Validates Phase 6 chaos control hooks against a live stack.
# Must be run from the loadTestTrafficGenerator/ directory.
#
# The script runs k6 in the background with CONTROL_ENABLED=true, then exercises
# the pause → resume → set-rate → clear command sequence while k6 is generating
# traffic. Kafka offset snapshots confirm that traffic actually stopped and restarted.
#
# Flags:
#   --with-setup   Reset stack (down -v), start all services, run the full
#                  pause/resume/set-rate control sequence (~3.5 minutes)
#   --teardown     Tear the stack down when all checks are done
#
# Examples:
#   ./scripts/validate_phase_6.sh --with-setup
#   ./scripts/validate_phase_6.sh --with-setup --teardown
#
# Prerequisites:
#   - docker compose up includes kafka, opensearch-source, capture-proxy,
#     otel-collector, prometheus, grafana, redis, webdis
#   - Webdis is exposed on host port 7379

set -euo pipefail

# ── Helpers ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'

PASS=0; FAIL=0

pass()   { echo -e "  ${GREEN}✓ PASS${NC}  $1"; (( PASS++ )) || true; }
fail()   { echo -e "  ${RED}✗ FAIL${NC}  $1"; (( FAIL++ )) || true; }
info()   { echo -e "  ${YELLOW}ℹ${NC}      $1"; }
header() { echo -e "\n${BOLD}$1${NC}"; }

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

webdis_set() {
  # Write a command to the control key via Webdis.
  # $1 = value to set (empty string = DEL the key)
  local val="$1"
  if [[ -z "$val" ]]; then
    curl -sf "http://localhost:7379/DEL/control_cmd" > /dev/null
  else
    curl -sf "http://localhost:7379/SET/control_cmd/${val}" > /dev/null
  fi
}

# ── Argument parsing ───────────────────────────────────────────────────────────
WITH_SETUP=false
WITH_TEARDOWN=false
for arg in "$@"; do
  case $arg in
    --with-setup) WITH_SETUP=true ;;
    --teardown)   WITH_TEARDOWN=true ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "\n${BOLD}Phase 6 Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"
echo "Control hooks: pause / resume / set-rate"

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
      fail "$CAPTURE_IMAGE still not found after build"
      exit 1
    fi
  else
    fail "$CAPTURE_IMAGE not found locally"
    echo -e "       Build it with:\n         cd TrafficCapture/dockerSolution && ../../../gradlew buildDockerImages"
    echo -e "       Or re-run with --with-setup to build automatically."
    exit 1
  fi
fi

# ── Step 2: stack startup ─────────────────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 2 — Resetting stack and starting services"
  docker compose down -v 2>/dev/null || true
  docker compose up -d --wait \
    kafka opensearch-source capture-proxy otel-collector prometheus grafana redis webdis
  pass "All services started"
else
  info "Skipping stack startup (use --with-setup to include)."
fi

# ── Step 3: service health ────────────────────────────────────────────────────
header "Step 3 — Service health"
check_service_health "" kafka opensearch-source capture-proxy webdis

# ── Step 4: Webdis reachable ──────────────────────────────────────────────────
header "Step 4 — Webdis control channel"

webdis_resp=$(curl -sf "http://localhost:7379/PING" 2>/dev/null || echo "")
if echo "$webdis_resp" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if 'PONG' in str(d) else 1)" 2>/dev/null; then
  pass "Webdis responds to PING at http://localhost:7379"
else
  fail "Webdis not reachable at http://localhost:7379 — control hooks require Webdis"
  echo -e "       Start it with: docker compose up -d redis webdis"
  exit 1
fi

# Ensure no stale control command from a previous run.
webdis_set ""
pass "Control key cleared (no stale command)"

# ── Step 5: k6 with CONTROL_ENABLED=true ──────────────────────────────────────
if $WITH_SETUP; then
  header "Step 5 — Launching k6 with CONTROL_ENABLED=true (~3 minutes)"
  info "Traffic will be paused, resumed, and throttled mid-run via Webdis"

  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < k6-config/ingest-steady.env

  # Run k6 in the background so we can send control commands while it runs.
  # Use DURATION=3m to give enough window for pause + resume + set-rate testing.
  K6_LOG=$(mktemp /tmp/k6-phase6-XXXXXX.log)
  k6_exit=0
  docker compose run --rm \
    "${env_flags[@]}" \
    -e DURATION=3m \
    -e CONTROL_ENABLED=true \
    -e WEBDIS_URL=http://webdis:7379 \
    k6 run --out=experimental-prometheus-rw /scripts/scenarios/ingest.js \
    > "$K6_LOG" 2>&1 &
  K6_PID=$!

  # Wait for initial Kafka traffic (up to 40 s) before exercising controls.
  info "Waiting for initial Kafka traffic (up to 40s)…"
  initial_offset=0
  for i in $(seq 1 20); do
    sleep 2
    initial_offset=$(kafka_total_offset)
    if (( initial_offset > 50 )); then
      info "Kafka offset reached $initial_offset — k6 is producing traffic"
      break
    fi
  done

  if (( initial_offset == 0 )); then
    fail "No Kafka messages after 40s — k6 may have failed to start"
    cat "$K6_LOG" || true
    wait "$K6_PID" || true
    rm -f "$K6_LOG"
    exit 1
  fi

  pass "k6 producing traffic (initial Kafka offset: $initial_offset)"
else
  info "Skipping k6 launch (use --with-setup to include)."
fi

# ── Step 6: pause → verify silence ────────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 6 — Pause: traffic should stop"

  pre_pause=$(kafka_total_offset)
  info "Sending 'pause' command to Webdis (offset before pause: $pre_pause)"
  webdis_set "pause"
  pass "pause command written to control_cmd"

  # Wait 15 s and measure how many Kafka messages arrived during the pause.
  sleep 15
  post_pause=$(kafka_total_offset)
  pause_delta=$(( post_pause - pre_pause ))

  info "Kafka offset after 15s pause: $post_pause (delta: $pause_delta)"
  # Allow a small delta for in-flight requests at the moment of pause.
  # At 50 req/s, 15 in-flight = 15 messages. Threshold: < 100.
  if (( pause_delta < 100 )); then
    pass "Pause effective: only $pause_delta Kafka messages in 15s (expected <100 at 50 req/s)"
  else
    fail "Pause may not be effective: $pause_delta Kafka messages in 15s — expected <100"
  fi
fi

# ── Step 7: resume → verify traffic restarts ──────────────────────────────────
if $WITH_SETUP; then
  header "Step 7 — Resume: traffic should restart"

  pre_resume=$(kafka_total_offset)
  info "Sending 'resume' command to Webdis (offset before resume: $pre_resume)"
  webdis_set "resume"
  pass "resume command written to control_cmd"

  sleep 20
  post_resume=$(kafka_total_offset)
  resume_delta=$(( post_resume - pre_resume ))

  info "Kafka offset after 20s resume: $post_resume (delta: $resume_delta)"
  # At 50 req/s for 20 s = ~1000 messages expected. Require at least 200.
  if (( resume_delta >= 200 )); then
    pass "Resume effective: $resume_delta Kafka messages in 20s (traffic restarted)"
  else
    fail "Resume may not be effective: only $resume_delta Kafka messages in 20s — expected ≥200"
  fi
fi

# ── Step 8: set-rate throttle ─────────────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 8 — set-rate:10 throttle (80% skip at baseRate=50)"

  pre_throttle=$(kafka_total_offset)
  info "Sending 'set-rate:10' command (expect ~20% of normal throughput)"
  webdis_set "set-rate:10"
  pass "set-rate:10 command written to control_cmd"

  sleep 20
  post_throttle=$(kafka_total_offset)
  throttle_delta=$(( post_throttle - pre_throttle ))

  # At 50 req/s with 80% skip probability, expected ~10 req/s × 20s = ~200 messages.
  # Full rate would be ~1000. Pass if delta is clearly below full rate (< 600).
  info "Kafka offset after 20s throttle: $post_throttle (delta: $throttle_delta)"
  if (( throttle_delta < 600 )); then
    pass "Throttle effective: $throttle_delta messages in 20s (below 600 full-rate threshold)"
  else
    fail "Throttle may not be effective: $throttle_delta messages in 20s — expected <600 at 20% rate"
  fi

  # Clear the throttle before k6 finishes.
  webdis_set ""
  pass "Control command cleared (back to full rate)"
fi

# ── Step 9: wait for k6 to finish ────────────────────────────────────────────
if $WITH_SETUP; then
  header "Step 9 — k6 exit status"
  info "Waiting for k6 to finish (DURATION=3m from launch; may still be running)…"
  k6_exit=0
  wait "$K6_PID" || k6_exit=$?
  rm -f "$K6_LOG"
  if (( k6_exit == 0 )); then
    pass "k6 exited 0 — all thresholds passed"
  else
    fail "k6 exited $k6_exit — threshold breach (paused VUs inflate p95 latency)"
    info "Threshold breach during pause is expected: p95 includes pause wait time."
    info "For production chaos testing, add abortOnFail:false to latency thresholds."
  fi
fi

# ── Step 10: service health (post-chaos) ─────────────────────────────────────
header "Step 10 — Service health (post-chaos)"
check_service_health "(post-chaos)" kafka opensearch-source capture-proxy

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
