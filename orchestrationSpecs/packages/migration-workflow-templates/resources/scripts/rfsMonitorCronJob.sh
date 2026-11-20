#!/usr/bin/env bash
set -eu
RFS_SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=rfsBackfillStatusLib.sh
. "$RFS_SCRIPT_DIR/rfsBackfillStatusLib.sh"

RFS_MONITOR_WORKFLOW_UID_LABEL="${RFS_MONITOR_WORKFLOW_UID_LABEL}"
K8S_API_SERVER="https://${KUBERNETES_SERVICE_HOST}:${KUBERNETES_SERVICE_PORT_HTTPS:-443}"
K8S_SA_TOKEN_FILE="/var/run/secrets/kubernetes.io/serviceaccount/token"
K8S_SA_CA_FILE="/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
K8S_SA_TOKEN="$(cat "$K8S_SA_TOKEN_FILE")"

# Materialize the console services config produced by the workflow's
# getConsoleConfig step so phase 3 can run "console backfill status".
mkdir -p /config
echo "$CONSOLE_CONFIG_BASE64" | base64 -d > /config/migration_services.yaml_
jq -f workflowConfigToServicesConfig.jq < /config/migration_services.yaml_ > /config/migration_services.yaml
export MIGRATION_USE_SERVICES_YAML_CONFIG=true

k8s_api() {
    method="$1"
    path="$2"
    content_type="$3"
    body="${4:-}"
    if [ -n "$body" ]; then
        curl -fsS \
            --cacert "$K8S_SA_CA_FILE" \
            -H "Authorization: Bearer $K8S_SA_TOKEN" \
            -H "Content-Type: $content_type" \
            -X "$method" \
            "$K8S_API_SERVER$path" \
            --data "$body"
    else
        curl -fsS \
            --cacert "$K8S_SA_CA_FILE" \
            -H "Authorization: Bearer $K8S_SA_TOKEN" \
            -H "Content-Type: $content_type" \
            -X "$method" \
            "$K8S_API_SERVER$path"
    fi
}

# 0. SUPERSESSION CHECK (INV-4) ----------------------------------------------
cronjob_json="$(kubectl get cronjob "$RFS_CRONJOB_NAME" -o json 2>/dev/null || true)"
if [ -z "$cronjob_json" ]; then
    echo "cronjob $RFS_CRONJOB_NAME not found; nothing to do"
    exit 0
fi
cj_workflow_uid="$(echo "$cronjob_json" | jq -r --arg k "$RFS_MONITOR_WORKFLOW_UID_LABEL" '.metadata.labels[$k] // ""')"
if [ "$cj_workflow_uid" != "$WORKFLOW_UID" ]; then
    echo "superseded: cronjob workflow-uid=$cj_workflow_uid env.WORKFLOW_UID=$WORKFLOW_UID"
    exit 0
fi
cj_uid="$(echo "$cronjob_json" | jq -r '.metadata.uid')"
cj_rv="$(echo "$cronjob_json" | jq -r '.metadata.resourceVersion')"
cj_namespace="$(echo "$cronjob_json" | jq -r '.metadata.namespace')"

sm_json="$(kubectl get snapshotmigration "$SNAPSHOT_MIGRATION_NAME" -o json 2>/dev/null || true)"

# delete_cronjob_with_preconditions: INV-5 atomic self-delete. kubectl
# delete does not expose --uid / --resource-version; use the raw API.
delete_cronjob_with_preconditions() {
    body="$(jq -nc --arg uid "$cj_uid" --arg rv "$cj_rv" \
        '{apiVersion:"v1",kind:"DeleteOptions",preconditions:{uid:$uid,resourceVersion:$rv}}')"
    if k8s_api DELETE "/apis/batch/v1/namespaces/$cj_namespace/cronjobs/$RFS_CRONJOB_NAME" \
        "application/json" "$body" >/dev/null 2>&1; then
        return 0
    fi
    echo "preconditioned cronjob delete returned non-zero (likely 409: superseded or RV advanced)"
    return 1
}

delete_runtime_resources() {
    kubectl delete deployment "$RFS_DEPLOYMENT_NAME" --ignore-not-found
    if [ "$USES_DEDICATED_COORDINATOR" = "true" ]; then
        kubectl delete statefulset "$RFS_COORDINATOR_NAME" --ignore-not-found
        kubectl delete service     "$RFS_COORDINATOR_NAME" --ignore-not-found
        kubectl delete secret      "$RFS_COORDINATOR_NAME-creds" --ignore-not-found
    fi
}

patch_backfill_status() {
    replace_document_backfill "$(document_backfill "$1" "$2" "${3:-}")"
}

parent_workflow_running() {
    phase="$(kubectl get workflow "$PARENT_WORKFLOW_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    [ "$phase" = "Running" ] || [ "$phase" = "Pending" ]
}

runtime_resource_exists() {
    kubectl get deployment "$RFS_DEPLOYMENT_NAME" >/dev/null 2>&1
}

# 1. RECOVERY CLEANUP (INV-6 b) ----------------------------------------------
sm_phase="$(echo "$sm_json" | jq -r '.status.phase // ""' 2>/dev/null || echo "")"
sm_status_cc="$(echo "$sm_json" | jq -r '.status.configChecksum // ""' 2>/dev/null || echo "")"
if [ -n "$sm_json" ] && [ "$sm_phase" = "Completed" ] && [ "$sm_status_cc" = "$CONFIG_CHECKSUM" ]; then
    echo "phase 1: recovery cleanup (status already reflects env.CONFIG_CHECKSUM)"
    delete_runtime_resources
    delete_cronjob_with_preconditions || true
    exit 0
fi

# 2. ORPHAN / STARTUP-WINDOW (INV-6 c) ---------------------------------------
if [ -z "$sm_json" ] || ! runtime_resource_exists; then
    if parent_workflow_running; then
        echo "phase 2: parent workflow still running; waiting"
        exit 0
    fi
    elapsed=$(( $(date -u +%s) - CLAIMED_AT ))
    if [ "$elapsed" -le "$STARTUP_GRACE_SECONDS" ]; then
        echo "phase 2: within startup grace ($elapsed of $STARTUP_GRACE_SECONDS s)"
        exit 0
    fi
    echo "phase 2: orphaned (sm or runtime missing, parent gone, grace expired); self-deleting"
    delete_cronjob_with_preconditions || true
    exit 0
fi

# 3. STATUS POLL + COMMIT (+ CLEANUP) (INV-6 a+b) ----------------------------
status_error_file="/tmp/rfs-backfill-status-error.txt"
status_json="$(console --config-file=/config/migration_services.yaml --json backfill status --deep-check 2>"$status_error_file" || true)"
status_error="$(cat "$status_error_file" 2>/dev/null || true)"
status="$(printf '%s' "$status_json" | jq -r '.status // ""' 2>/dev/null || echo "")"

if [ -z "$status_json" ] || [ -z "$status" ]; then
    status_message="$(printf '%s' "${status_error:-${status_json:-Status check is not available yet}}" | head -c 1024)"
    patch_backfill_status "Unknown" "$status_message" ""
    exit 0
fi

status_message="$(printf '%s' "$status_json" | jq -r '
    if .status == "Pending" then
        "Shards are initializing"
    else
        "Document backfill is " + (.status | tostring)
    end
')"
patch_backfill_status "$status" "$status_message" "$status_json"

# CompletedWithErrors is terminal too — treating it as in-progress would loop forever.
if [ "$status" != "Completed" ] && [ "$status" != "CompletedWithErrors" ]; then
    progress="$(printf '%s' "$status_json" | jq -r '"complete: \(.percentage_completed // 0)%, shards: \(.shard_complete // 0)/\(.shard_total // 0)"')"
    echo "phase 3: still working ($progress)"
    exit 0
fi

# Backfill is done. Lock-on-Complete VAP freezes spec the instant this
# PATCH returns, so there is no spec-change window between commit and the
# cleanup below. If the status PATCH fails, this execution exits non-zero and
# leaves the runtime + CronJob in place so a future run can retry safely.
echo "phase 3: backfill complete; committing and cleaning up"
commit_ops="$(jq -nc --arg cc "$CONFIG_CHECKSUM" --arg cr "$CHECKSUM_FOR_REPLAYER" \
    '[{op: "add", path: "/status/phase", value: "Completed"},
      {op: "add", path: "/status/configChecksum", value: $cc},
      {op: "add", path: "/status/checksumForReplayer", value: $cr}]')"
replace_document_backfill "$(document_backfill "$status" "$status_message" "$status_json")" "$commit_ops"

delete_runtime_resources
delete_cronjob_with_preconditions || true
exit 0
