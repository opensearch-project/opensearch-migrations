#!/usr/bin/env bash
# Launch several concurrent k6 runs (as independent Workflows) against the running data plane, to
# exercise multiple simultaneous load tests. Unlike the old docker-compose version, the runs live
# in the cluster — no background processes to babysit; list/stop them with the console CLI.
#
# Assumes the data plane is up (deployCdcLoadTestConfig.sh up).
#
# Usage:
#   ./scripts/start_multiple_k6.sh           # submit the default fleet, then wait (Ctrl+C stops all)
#   ./scripts/start_multiple_k6.sh --no-wait # submit and return immediately

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

WAIT=true
[[ "${1:-}" == "--no-wait" ]] && WAIT=false

# scenario:config pairs to launch concurrently
FLEET=(
  ingest:ingest-steady
  ingest:ingest-ramp
  ingest:ingest-burst
  search:search-steady
  search:search-ramp
  search:search-burst
  search:search-deep-paging
)

cleanup() { trap - EXIT INT; echo "Stopping all k6 runs..."; k6_stop_all; }

echo "Submitting ${#FLEET[@]} concurrent k6 runs..."
for spec in "${FLEET[@]}"; do
  scenario="${spec%%:*}"; config="${spec##*:}"
  name=$(submit_k6 --scenario "$scenario" --config "$config" --extra-args --no-thresholds || true)
  echo "  ${scenario}/${config} → ${name:-<submit failed>}"
done

echo ""
k6_list || true

if $WAIT; then
  trap cleanup EXIT INT
  echo ""
  echo "All runs submitted. Ctrl+C to stop all (or: kubectl delete wf -l app=k6-load-test)."
  # Wait until no active runs remain.
  while true; do
    sleep 15
    [[ "$(k6_active_count)" -eq 0 ]] && { echo "All runs finished."; trap - EXIT INT; break; }
  done
else
  echo "Submitted (--no-wait). Manage with: kubectl get/delete wf -l app=k6-load-test"
fi
