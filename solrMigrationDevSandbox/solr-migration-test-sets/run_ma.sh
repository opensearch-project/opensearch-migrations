#!/usr/bin/env bash
#
# run_ma.sh — automate step 7 (Run Migration Assistant) of steps_standalone.md
#
# Given one of the MA config files under ./ma_configs/ this script:
#   1. resets prior migration resources so the run isn't a no-op
#        (workflow reset --all)
#   2. uploads the config into the migration-console pod
#        (workflow configure edit --stdin)
#   3. deletes any pre-existing target index so the migration starts clean
#        (DELETE <target-endpoint>/<index>, run from inside the console pod)
#   4. submits the migration workflow and polls it to completion, reporting
#        SUCCESS or FAILURE (we poll the real Argo `migration-workflow` object
#        and the SnapshotMigration resource rather than `workflow submit --wait`,
#        whose --wait path watches a run-name that doesn't match the CRD)
#   5. reports the document count in the target index
#
# Why the reset (step 1): the migration is gated on the SnapshotMigration
# resource's state, not on whether the target index exists. If a prior run left
# that resource in phase=Completed with a matching config checksum, a fresh
# submit is a no-op (no backfill pods spawn) — so deleting the index alone gives
# you a "Succeeded" workflow with an empty/missing index. `workflow reset --all`
# clears those resources so the next submit actually re-runs the migration.
#
# The target endpoint is read out of the config's targetClusters.*.endpoint, so
# you only have to point this at the config file you want to run.

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults / config
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

CONFIG=""                       # required: path to an ma_configs/.../*.json file
INDEX="nyc_taxis"               # target index to DELETE before / count after
NAMESPACE="ma"                  # kubernetes namespace
POD="migration-console-0"       # migration console pod name
WORKFLOW_NAME="migration-workflow"  # Argo workflow object name (workflow submit default)
DO_RESET=1                      # set to 0 with --no-reset
DO_DELETE=1                     # set to 0 with --no-delete
DO_SUBMIT=1                     # set to 0 with --no-submit
TIMEOUT=1800                    # seconds to wait for the workflow to finish
WAIT_INTERVAL=5                 # seconds between status polls

usage() {
    cat <<'EOF'
Usage: ./run_ma.sh [options] <ma_config.json>

Resets prior migration resources, uploads an MA config into the migration-console
pod, deletes the existing target index, submits the migration workflow, polls it
to completion, reports success/failure, and prints the target index doc count.

Arguments:
  <ma_config.json>    Path to a config file under ./ma_configs/
                      e.g. ./ma_configs/solrcloud/nyc_taxis_8_ma_config.json

Options:
  --index=NAME        Target index to DELETE before / count after (default: nyc_taxis)
  --namespace=NS      Kubernetes namespace (default: ma)
  --pod=NAME          Migration console pod name (default: migration-console-0)
  --workflow-name=NM  Argo workflow object name to poll (default: migration-workflow)
  --timeout=SECONDS   Max time to wait for the workflow to finish (default: 1800)
  --wait-interval=SEC Seconds between status polls (default: 5)
  --no-reset          Skip `workflow reset --all` (prior resources may no-op the run)
  --no-delete         Skip the DELETE of the target index
  --no-submit         Only reset/upload/delete; do not submit the workflow
  -h, --help          Show this help

Examples:
  ./run_ma.sh ./ma_configs/solrcloud/nyc_taxis_8_ma_config.json
  ./run_ma.sh --timeout=3600 ./ma_configs/standalone/nyc_taxis_8_ma_config.json
  ./run_ma.sh --no-submit ./ma_configs/solrcloud/nyc_taxis_9_ma_config.json
EOF
}

# ---------------------------------------------------------------------------
# Arg parsing
# ---------------------------------------------------------------------------
for arg in "$@"; do
    case "$arg" in
        --index=*)          INDEX="${arg#*=}" ;;
        --namespace=*)      NAMESPACE="${arg#*=}" ;;
        --pod=*)            POD="${arg#*=}" ;;
        --workflow-name=*)  WORKFLOW_NAME="${arg#*=}" ;;
        --timeout=*)        TIMEOUT="${arg#*=}" ;;
        --wait-interval=*)  WAIT_INTERVAL="${arg#*=}" ;;
        --no-reset)         DO_RESET=0 ;;
        --no-delete)        DO_DELETE=0 ;;
        --no-submit)        DO_SUBMIT=0 ;;
        -h|--help)          usage; exit 0 ;;
        --*)                echo "Unknown option: $arg" >&2; usage; exit 1 ;;
        *)                  CONFIG="$arg" ;;
    esac
done

cd "$SCRIPT_DIR"

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARN: %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[[ -n "$CONFIG" ]] || { usage; die "no config file given"; }
[[ -f "$CONFIG" ]] || die "config file not found: $CONFIG"
command -v kubectl >/dev/null 2>&1 || die "kubectl not found on PATH"

# ---------------------------------------------------------------------------
# Pull the target endpoint out of the config (first targetClusters entry).
# ---------------------------------------------------------------------------
target_endpoint() {
    python - "$CONFIG" <<'PY'
import json, sys
cfg = json.load(open(sys.argv[1]))
targets = cfg.get("targetClusters", {})
for t in targets.values():
    ep = t.get("endpoint")
    if ep:
        print(ep.rstrip("/"))
        break
PY
}

# Convenience wrapper: run a command inside the migration-console pod.
in_pod() {
    kubectl exec -i "$POD" -n "$NAMESPACE" -- "$@"
}

# ---------------------------------------------------------------------------
# Step 1 — reset prior migration resources (so the submit isn't a no-op).
# ---------------------------------------------------------------------------
reset_migration() {
    log "Resetting prior migration resources (workflow reset --all)"
    in_pod workflow reset --all || warn "workflow reset --all reported an error (continuing)"
}

# ---------------------------------------------------------------------------
# Step 2 — upload config
# ---------------------------------------------------------------------------
upload_config() {
    log "Uploading config ${CONFIG} into ${POD} (namespace ${NAMESPACE})"
    in_pod workflow configure edit --stdin < "$CONFIG"
}

# ---------------------------------------------------------------------------
# Step 3 — delete the existing target index
# ---------------------------------------------------------------------------
delete_target_index() {
    local endpoint="$1"
    [[ -n "$endpoint" ]] || die "could not read targetClusters.*.endpoint from $CONFIG"
    local url="${endpoint}/${INDEX}"
    log "Deleting existing target index: ${url}"
    # Run from inside the console pod — the target cluster is typically only
    # reachable from within the migration namespace.
    in_pod curl -s -X DELETE "$url" >/dev/null 2>&1 || warn "DELETE of ${url} failed"
}

# ---------------------------------------------------------------------------
# Step 4a — submit the workflow (without --wait; we poll the CRD ourselves).
# ---------------------------------------------------------------------------
submit_workflow() {
    log "Submitting workflow '${WORKFLOW_NAME}'"
    in_pod workflow submit --workflow-name "$WORKFLOW_NAME" \
        || die "workflow submit failed"
}

# ---------------------------------------------------------------------------
# Step 4b — poll the real Argo workflow object until it reaches a terminal phase
# or we time out. Sets the global WORKFLOW_PHASE.
#
# We query the workflow CRD directly (not `workflow submit --wait`) because the
# console's --wait path watches a run-suffixed name that doesn't exist as a CRD.
# ---------------------------------------------------------------------------
WORKFLOW_PHASE=""
poll_workflow() {
    log "Polling workflow '${WORKFLOW_NAME}' (timeout ${TIMEOUT}s, every ${WAIT_INTERVAL}s)"
    local deadline=$(( SECONDS + TIMEOUT ))
    local phase="" progress=""
    while (( SECONDS < deadline )); do
        phase="$(kubectl get workflows.argoproj.io "$WORKFLOW_NAME" -n "$NAMESPACE" \
            -o jsonpath='{.status.phase}' 2>/dev/null || true)"
        progress="$(kubectl get workflows.argoproj.io "$WORKFLOW_NAME" -n "$NAMESPACE" \
            -o jsonpath='{.status.progress}' 2>/dev/null || true)"
        printf '\r    phase=%-10s progress=%-8s ' "${phase:-<pending>}" "${progress:-?}"
        case "$phase" in
            Succeeded|Failed|Error|Stopped|Terminated)
                echo
                WORKFLOW_PHASE="$phase"
                return 0 ;;
        esac
        sleep "$WAIT_INTERVAL"
    done
    echo
    WORKFLOW_PHASE="Timeout(last=${phase:-none})"
    warn "Timed out after ${TIMEOUT}s waiting for '${WORKFLOW_NAME}' to finish"
}

# ---------------------------------------------------------------------------
# Step 4c — report the SnapshotMigration backfill summary (the real signal for
# how many docs/shards actually migrated).
# ---------------------------------------------------------------------------
report_backfill() {
    local crs
    crs="$(kubectl get snapshotmigration -n "$NAMESPACE" -o name 2>/dev/null || true)"
    [[ -n "$crs" ]] || { warn "no SnapshotMigration resources found to report"; return; }
    local cr phase migrated total pct
    for cr in $crs; do
        phase="$(kubectl get "$cr" -n "$NAMESPACE" -o jsonpath='{.status.documentBackfill.phase}' 2>/dev/null || true)"
        migrated="$(kubectl get "$cr" -n "$NAMESPACE" -o jsonpath='{.status.documentBackfill.summary.shardsMigrated}' 2>/dev/null || true)"
        total="$(kubectl get "$cr" -n "$NAMESPACE" -o jsonpath='{.status.documentBackfill.summary.shardsTotal}' 2>/dev/null || true)"
        pct="$(kubectl get "$cr" -n "$NAMESPACE" -o jsonpath='{.status.documentBackfill.summary.percentageCompleted}' 2>/dev/null || true)"
        log "Backfill [${cr##*/}]: phase=${phase:-?} shards=${migrated:-?}/${total:-?} (${pct:-?}%)"
    done
}

# ---------------------------------------------------------------------------
# Step 5 — report the document count in the target index. Sets DOC_COUNT.
# ---------------------------------------------------------------------------
DOC_COUNT=""
count_documents() {
    local endpoint="$1"
    [[ -n "$endpoint" ]] || { warn "no target endpoint; skipping document count"; return; }
    in_pod curl -s -X POST "${endpoint}/${INDEX}/_refresh" >/dev/null 2>&1 || true
    local resp
    resp="$(in_pod curl -s "${endpoint}/${INDEX}/_count" 2>/dev/null || true)"
    DOC_COUNT="$(printf '%s' "$resp" | sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
    if [[ -n "$DOC_COUNT" ]]; then
        log "Document count in target index '${INDEX}': ${DOC_COUNT}"
    else
        warn "Could not read document count for '${INDEX}'. Response was: ${resp}"
    fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    local endpoint=""
    endpoint="$(target_endpoint)" || true

    if [[ "$DO_RESET" -eq 1 ]]; then
        reset_migration
    else
        log "Skipping migration reset (--no-reset)"
    fi

    upload_config

    if [[ "$DO_DELETE" -eq 1 ]]; then
        delete_target_index "$endpoint"
    else
        log "Skipping target index DELETE (--no-delete)"
    fi

    if [[ "$DO_SUBMIT" -eq 0 ]]; then
        log "Skipping workflow submit (--no-submit). Config loaded; drive it yourself:"
        printf '    kubectl exec -it %s -n %s -- /bin/bash\n' "$POD" "$NAMESPACE"
        return 0
    fi

    submit_workflow
    poll_workflow
    report_backfill
    count_documents "$endpoint"

    # Success requires the workflow to have Succeeded AND the index to hold docs.
    # (A "Succeeded" workflow with 0 docs is the classic no-op symptom.)
    local count_ok=0
    [[ "${DOC_COUNT:-}" =~ ^[0-9]+$ ]] && (( DOC_COUNT > 0 )) && count_ok=1

    if [[ "$WORKFLOW_PHASE" == "Succeeded" && "$count_ok" -eq 1 ]]; then
        log "✅ Migration SUCCEEDED — phase=${WORKFLOW_PHASE}, '${INDEX}' has ${DOC_COUNT} docs"
        return 0
    elif [[ "$WORKFLOW_PHASE" == "Succeeded" ]]; then
        warn "⚠️  Workflow phase=Succeeded but '${INDEX}' has ${DOC_COUNT:-0} docs — likely a no-op run."
        warn "    Re-run (reset is on by default), or inspect: kubectl get snapshotmigration -n ${NAMESPACE}"
        return 1
    else
        warn "❌ Migration did NOT succeed (phase: ${WORKFLOW_PHASE})"
        warn "    Inspect it with: kubectl exec -it ${POD} -n ${NAMESPACE} -- workflow status --all"
        return 1
    fi
}

main
