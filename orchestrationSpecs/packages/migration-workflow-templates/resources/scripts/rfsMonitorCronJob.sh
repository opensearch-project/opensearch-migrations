#!/usr/bin/env bash
set -eu

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

make_backfill_status_patch() {
    phase="$1"
    message="$2"
    status_json_text="${3:-}"
    now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

    if [ -n "$status_json_text" ] && printf '%s' "$status_json_text" | jq -e 'type == "object"' >/dev/null 2>&1; then
        jq -nc \
            --arg phase "$phase" \
            --arg updatedAt "$now" \
            --arg message "$message" \
            --argjson raw "$status_json_text" \
            '($raw // {}) as $r |
             {status:{documentBackfill:{
                phase:$phase,
                updatedAt:$updatedAt,
                message:$message,
                summary:{
                    percentageCompleted:($r.percentage_completed // 0),
                    etaMs:$r.eta_ms,
                    started:$r.started,
                    finished:$r.finished,
                    shardsTotal:($r.shard_total // 0),
                    shardsMigrated:($r.shard_complete // 0),
                    shardsInProgress:($r.shard_in_progress // 0),
                    shardsWaiting:($r.shard_waiting // 0)
                }
             }}}'
        return 0
    fi

    jq -nc \
        --arg phase "$phase" \
        --arg updatedAt "$now" \
        --arg message "$message" \
        '{status:{documentBackfill:{
            phase:$phase,
            updatedAt:$updatedAt,
            message:$message,
            summary:{
                percentageCompleted:0,
                etaMs:null,
                started:null,
                finished:null,
                shardsTotal:0,
                shardsMigrated:0,
                shardsInProgress:0,
                shardsWaiting:0
            }
        }}}'
}

patch_backfill_status() {
    phase="$1"
    message="$2"
    status_json_text="${3:-}"
    patch_body="$(make_backfill_status_patch "$phase" "$message" "$status_json_text")"
    kubectl patch snapshotmigration "$SNAPSHOT_MIGRATION_NAME" \
        --subresource=status --type=merge -p "$patch_body"
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

if [ "$status" != "Completed" ]; then
    progress="$(printf '%s' "$status_json" | jq -r '"complete: \(.percentage_completed // 0)%, shards: \(.shard_complete // 0)/\(.shard_total // 0)"')"
    echo "phase 3: still working ($progress)"
    exit 0
fi

# Backfill is done. Lock-on-Complete VAP freezes spec the instant this
# PATCH returns, so there is no spec-change window between commit and the
# cleanup below. If the status PATCH fails, this execution exits non-zero and
# leaves the runtime + CronJob in place so a future run can retry safely.
echo "phase 3: backfill complete; committing and cleaning up"
patch_body="$(jq -nc \
    --arg cc "$CONFIG_CHECKSUM" \
    --arg cr "$CHECKSUM_FOR_REPLAYER" \
    --argjson backfill "$(make_backfill_status_patch "$status" "$status_message" "$status_json" | jq -c '.status.documentBackfill')" \
    '{status:{phase:"Completed",configChecksum:$cc,checksumForReplayer:$cr,documentBackfill:$backfill}}')"
kubectl patch snapshotmigration "$SNAPSHOT_MIGRATION_NAME" \
    --subresource=status --type=merge -p "$patch_body"

delete_runtime_resources
delete_cronjob_with_preconditions || true
exit 0
