#!/usr/bin/env bash
# Validates the runtime control plane: pause / resume / dynamic rate via the Webdis control bus.
# Needs Redis+Webdis (install the chart with registry.enabled=true) and the data plane up.
#
# Usage:
#   ./scripts/run_test_chaos.sh --run    # submit a controlled k6 run, drive it, then check
#   ./scripts/run_test_chaos.sh          # checks only (a controlled run must already be live)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

WITH_RUN=false; RUN_NAME=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --run) WITH_RUN=true ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
  shift
done

cleanup() { [[ -n "$RUN_NAME" ]] && k6_stop "$RUN_NAME" || true; }
trap cleanup EXIT

echo -e "\n${BOLD}Chaos Control Validation — $(date '+%Y-%m-%d %H:%M:%S')${NC}"
echo "──────────────────────────────────────────────────"

header "Step 1 — Service health"
# The pipeline itself is workflow-owned (CR phase + Deployment); redis/webdis are the chaos control
# bus from the k6LoadTest chart, which this script requires (registry.enabled=true).
check_migration_resources_ready
check_workload_health
check_service_health "" redis webdis

if $WITH_RUN; then
  header "Step 2 — Submitting a controlled k6 run (CONTROL_ENABLED, 2m)"
  # --no-thresholds: paused VUs inflate p95 beyond k6's thresholds; not the assertion here.
  RUN_NAME=$(submit_k6 --scenario ingest --config ingest-steady \
    -e CONTROL_ENABLED=true -e DURATION=2m --extra-args --no-thresholds)
  [[ -n "$RUN_NAME" ]] && info "Submitted run: $RUN_NAME" || { fail "could not submit run"; print_summary; }
  info "Waiting for the run to start producing traffic..."
  for _ in $(seq 1 20); do
    b=$(kafka_total_offset); sleep 6; a=$(kafka_total_offset)
    (( a > b )) && break
  done
fi

header "Step 3 — Pause"
offset_before_pause=$(kafka_total_offset)
webdis_set "control_cmd" "pause"
stored=$(webdis_get "control_cmd")
[[ "$stored" == "pause" ]] && info "Pause signal verified in Redis (control_cmd=pause)" \
                           || info "Webdis GET after SET: '${stored}' (expected 'pause')"
sleep 10
offset_after_pause=$(kafka_total_offset)
pause_delta=$(( offset_after_pause - offset_before_pause ))
if (( pause_delta < 20 )); then
  pass "Kafka messages during pause: $pause_delta (expected ~0 — in-flight requests may complete)"
else
  fail "Kafka messages during pause: $pause_delta — k6 may not have respected the pause signal"
fi

header "Step 4 — Resume"
offset_before_resume=$(kafka_total_offset)
webdis_set "control_cmd" "resume"
info "Sent resume signal (control_cmd=resume)"
sleep 10
offset_after_resume=$(kafka_total_offset)
resume_delta=$(( offset_after_resume - offset_before_resume ))
if (( resume_delta > 0 )); then
  pass "Kafka messages after resume: $resume_delta (traffic restarted)"
else
  fail "Kafka messages after resume: $resume_delta — traffic did not restart"
fi

header "Step 5 — Dynamic rate (set-rate:10)"
webdis_set "control_cmd" "set-rate%3A10"
info "Sent set-rate:10 (throttle). Effective throughput should drop; observe in Grafana."

print_summary
