#!/usr/bin/env bash
# NOTE: the below assumes a running stack of capture-proxy, kafka, proxy target as started up in the validate_*.sh
# scripts with --with-setup but without --teardown option (if latter is set, the setup is only available during the
# current run of validate_*.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/common.sh"

pids=()

cleanup() {
    trap - EXIT          # prevent re-entry if kill triggers another EXIT
    [[ ${#pids[@]} -eq 0 ]] && return
    echo "Stopping all k6 runs..."
    kill "${pids[@]}" 2>/dev/null || true
    wait "${pids[@]}" 2>/dev/null || true
}
trap cleanup EXIT

start_k6() {
    local env_file=$1 scenario=$2
    load_k6_env "$env_file"
    echo "Starting $env_file"
    "${DOCKER_COMPOSE[@]}" run --rm -T "${env_flags[@]}" \
        k6 run --out=opentelemetry "/scripts/scenarios/${scenario}.js" &
    pids+=($!)
}

start_k6 "k6-config/ingest-steady.env"      ingest
start_k6 "k6-config/ingest-ramp.env"        ingest
start_k6 "k6-config/ingest-burst.env"       ingest
start_k6 "k6-config/search-steady.env"      search
start_k6 "k6-config/search-ramp.env"        search
start_k6 "k6-config/search-burst.env"       search
start_k6 "k6-config/search-deep-paging.env" search

echo "All ${#pids[@]} k6 runs started. Ctrl+C to stop all."
wait "${pids[@]}"
