#!/usr/bin/env bash
# =============================================================================
# 08-live-replay-quality.sh
#
# Serves the TUI (deployment/k8s/tui) as a web page instead of a terminal app, via
# textual-serve — same live replay-quality monitor (per-sub-query Jaccard/RBO scoring,
# hit-level diffs, the by-type breakdown view), just viewable in a browser instead of
# needing a terminal + kubeconfig on the viewer's own machine.
#
# NOTE: the TUI's 'c' (copy request) keybinding copies to the clipboard of whatever
# machine runs THIS script, not the browser viewer's machine — fine if they're the same
# machine, a real gotcha if someone else opens the URL from their own.
#
# Exposing this through a tunnel (ngrok, etc.)? Set PUBLIC_URL to the tunnel's https URL:
#   ngrok http 8321                                   # note the https://...ngrok-free.app URL it prints
#   PUBLIC_URL=https://abc123.ngrok-free.app bash 08-live-replay-quality.sh
# textual-serve hardcodes the page's websocket/static URLs from this — without it, a
# remote browser gets told to connect to YOUR localhost, which resolves to their own
# machine and silently fails. See tui/serve_web.py for why.
#
# PREREQUISITES:
#   - Same as the TUI itself (../tui/README.md) — a working kubeconfig for the `ma`
#     namespace, tuple-output topic populated by the TrafficReplayer.
# =============================================================================
set -euo pipefail

WEB_HOST="${WEB_HOST:-localhost}"
WEB_PORT="${WEB_PORT:-8321}"
NAMESPACE="${NAMESPACE:-ma}"
PUBLIC_URL="${PUBLIC_URL:-}"
export WEB_HOST WEB_PORT NAMESPACE PUBLIC_URL

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

pip install -q -r "${K8S_DIR}/tui/requirements.txt"

echo "Serving live replay-quality monitor at http://${WEB_HOST}:${WEB_PORT}"
if [ -n "${PUBLIC_URL}" ]; then
  echo "Public URL: ${PUBLIC_URL} (make sure your tunnel is already pointed at port ${WEB_PORT})"
fi
# textual-serve spawns 'python3 -m tui ...' inheriting THIS process's cwd (no cwd param
# of its own) — must run from deployment/k8s, the parent of the tui/ package, or the
# subprocess fails with "No module named tui".
cd "${K8S_DIR}"
python3 tui/serve_web.py
