#!/usr/bin/env bash
# Launch several concurrent k6 runs (as independent TestRuns) against the running data plane, to
# exercise multiple simultaneous load tests. Unlike the old docker-compose version, the runs live
# in the cluster — no background processes to babysit; list/stop them with the console CLI.
#
# Assumes the data plane is up (deployWorkflowComponents.sh up).
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

cleanup() { trap - EXIT INT; echo "Stopping all k6 runs..."; WF k6 stop --all >/dev/null 2>&1 || true; }

echo "Submitting ${#FLEET[@]} concurrent k6 runs..."
for spec in "${FLEET[@]}"; do
  scenario="${spec%%:*}"; config="${spec##*:}"
  name=$(submit_k6 --scenario "$scenario" --config "$config" --extra-args --no-thresholds || true)
  echo "  ${scenario}/${config} → ${name:-<submit failed>}"
done

echo ""
WF k6 list || true

if $WAIT; then
  trap cleanup EXIT INT
  echo ""
  echo "All runs submitted. Ctrl+C to stop all (or: workflow k6 stop --all)."
  # Wait until no active runs remain.
  while true; do
    sleep 15
    active=$(WF k6 list 2>/dev/null | grep -cE "started|created|initiali" || true)
    [[ "${active:-0}" -eq 0 ]] && { echo "All runs finished."; trap - EXIT INT; break; }
  done
else
  echo "Submitted (--no-wait). Manage with: workflow k6 list · workflow k6 stop --all"
fi
