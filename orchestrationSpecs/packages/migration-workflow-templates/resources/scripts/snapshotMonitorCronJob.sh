#!/usr/bin/env bash
set -eu

SNAPSHOT_MONITOR_WORKFLOW_UID_LABEL="${SNAPSHOT_MONITOR_WORKFLOW_UID_LABEL}"
K8S_API_SERVER="https://${KUBERNETES_SERVICE_HOST}:${KUBERNETES_SERVICE_PORT_HTTPS:-443}"
K8S_SA_TOKEN_FILE="/var/run/secrets/kubernetes.io/serviceaccount/token"
K8S_SA_CA_FILE="/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
K8S_SA_TOKEN="$(cat "$K8S_SA_TOKEN_FILE")"

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

cronjob_json="$(kubectl get cronjob "$SNAPSHOT_CRONJOB_NAME" -o json 2>/dev/null || true)"
if [ -z "$cronjob_json" ]; then
    echo "cronjob $SNAPSHOT_CRONJOB_NAME not found; nothing to do"
    exit 0
fi
cj_workflow_uid="$(echo "$cronjob_json" | jq -r --arg k "$SNAPSHOT_MONITOR_WORKFLOW_UID_LABEL" '.metadata.labels[$k] // ""')"
if [ "$cj_workflow_uid" != "$WORKFLOW_UID" ]; then
    echo "superseded: cronjob workflow-uid=$cj_workflow_uid env.WORKFLOW_UID=$WORKFLOW_UID"
    exit 0
fi
cj_uid="$(echo "$cronjob_json" | jq -r '.metadata.uid')"
cj_rv="$(echo "$cronjob_json" | jq -r '.metadata.resourceVersion')"
cj_namespace="$(echo "$cronjob_json" | jq -r '.metadata.namespace')"

delete_cronjob_with_preconditions() {
    body="$(jq -nc --arg uid "$cj_uid" --arg rv "$cj_rv" \
        '{apiVersion:"v1",kind:"DeleteOptions",preconditions:{uid:$uid,resourceVersion:$rv}}')"
    if k8s_api DELETE "/apis/batch/v1/namespaces/$cj_namespace/cronjobs/$SNAPSHOT_CRONJOB_NAME" \
        "application/json" "$body" >/dev/null 2>&1; then
        return 0
    fi
    echo "preconditioned cronjob delete returned non-zero (likely 409: superseded or RV advanced)"
    return 1
}

parent_workflow_running() {
    phase="$(kubectl get workflow "$PARENT_WORKFLOW_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    [ "$phase" = "Running" ] || [ "$phase" = "Pending" ]
}

number_or_zero() {
    case "${1:-}" in
        ''|*[!0-9]*) echo 0 ;;
        *) echo "$1" ;;
    esac
}

extract_line_value() {
    pattern="$1"
    printf '%s\n' "$SNAPSHOT_DEEP_OUTPUT" | awk -v p="$pattern" '$0 ~ p { sub(/^[^:]*:[[:space:]]*/, ""); print; exit }'
}

make_snapshot_status_patch() {
    data_snapshot_phase="$1"
    snapshot_phase="$2"
    message="$3"
    now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    total="$(number_or_zero "$(extract_line_value "Total shards")")"
    successful="$(number_or_zero "$(extract_line_value "Successful shards")")"
    failed="$(number_or_zero "$(extract_line_value "Failed shards")")"
    data_line="$(extract_line_value "Data processed")"
    data_processed="$(printf '%s' "$data_line" | awk '{print $1}')"
    data_processed_unit="$(printf '%s' "$data_line" | awk '{print $2}')"
    eta="$(extract_line_value "Estimated time to completion")"

    jq -nc \
        --arg dataSnapshotPhase "$data_snapshot_phase" \
        --arg snapshotPhase "$snapshot_phase" \
        --arg updatedAt "$now" \
        --arg message "$message" \
        --arg snapshotName "$SNAPSHOT_NAME" \
        --arg configChecksum "$CONFIG_CHECKSUM" \
        --arg checksumForSnapshotMigration "$CONFIG_CHECKSUM" \
        --argjson shardsTotal "$total" \
        --argjson shardsSuccessful "$successful" \
        --argjson shardsFailed "$failed" \
        --arg dataProcessed "$data_processed" \
        --arg dataProcessedUnit "$data_processed_unit" \
        --arg eta "$eta" \
        '{status:{
            phase:$dataSnapshotPhase,
            snapshotName:$snapshotName,
            configChecksum:$configChecksum,
            checksumForSnapshotMigration:$checksumForSnapshotMigration,
            snapshotCreation:{
                phase:$snapshotPhase,
                updatedAt:$updatedAt,
                message:$message,
                summary:{
                    shardsTotal:$shardsTotal,
                    shardsSuccessful:$shardsSuccessful,
                    shardsFailed:$shardsFailed,
                    dataProcessed:($dataProcessed | if . == "" then null else . end),
                    dataProcessedUnit:($dataProcessedUnit | if . == "" then null else . end),
                    eta:($eta | if . == "" then null else . end)
                }
            }
        }}'
}

patch_snapshot_status() {
    data_snapshot_phase="$1"
    snapshot_phase="$2"
    message="$3"
    patch_body="$(make_snapshot_status_patch "$data_snapshot_phase" "$snapshot_phase" "$message")"
    kubectl patch datasnapshot "$DATASNAPSHOT_NAME" \
        --subresource=status --type=merge -p "$patch_body"
}

ds_json="$(kubectl get datasnapshot "$DATASNAPSHOT_NAME" -o json 2>/dev/null || true)"
if [ -z "$ds_json" ]; then
    if parent_workflow_running; then
        echo "datasnapshot $DATASNAPSHOT_NAME not found yet; parent workflow still running"
        exit 0
    fi
    elapsed=$(( $(date -u +%s) - CLAIMED_AT ))
    if [ "$elapsed" -le "$STARTUP_GRACE_SECONDS" ]; then
        echo "datasnapshot $DATASNAPSHOT_NAME not found; within startup grace"
        exit 0
    fi
    echo "datasnapshot $DATASNAPSHOT_NAME missing and parent workflow is gone; self-deleting"
    delete_cronjob_with_preconditions || true
    exit 0
fi

ds_phase="$(echo "$ds_json" | jq -r '.status.phase // ""')"
ds_config_checksum="$(echo "$ds_json" | jq -r '.status.configChecksum // ""')"
if { [ "$ds_phase" = "Completed" ] || [ "$ds_phase" = "Error" ]; } && [ "$ds_config_checksum" = "$CONFIG_CHECKSUM" ]; then
    echo "datasnapshot already terminal for this config; self-deleting"
    delete_cronjob_with_preconditions || true
    exit 0
fi

status_error_file="/tmp/snapshot-status-error.txt"
status="$(console --config-file=/config/migration_services.yaml snapshot status 2>"$status_error_file" || true)"
status_error="$(cat "$status_error_file" 2>/dev/null || true)"
SNAPSHOT_DEEP_OUTPUT="$(console --config-file=/config/migration_services.yaml snapshot status --deep-check 2>>"$status_error_file" || true)"
if [ -z "$SNAPSHOT_DEEP_OUTPUT" ]; then
    SNAPSHOT_DEEP_OUTPUT="$status_error"
fi

case "$status" in
    SUCCESS)
        patch_snapshot_status "Completed" "Completed" "Snapshot completed successfully"
        delete_cronjob_with_preconditions || true
        exit 0
        ;;
    FAILED)
        patch_snapshot_status "Error" "Failed" "Snapshot failed"
        delete_cronjob_with_preconditions || true
        exit 0
        ;;
esac

case "$SNAPSHOT_DEEP_OUTPUT" in
    SUCCESS)
        patch_snapshot_status "Completed" "Completed" "Snapshot completed successfully"
        delete_cronjob_with_preconditions || true
        exit 0
        ;;
    FAILED)
        patch_snapshot_status "Error" "Failed" "Snapshot failed"
        delete_cronjob_with_preconditions || true
        exit 0
        ;;
esac

if [ -z "$status" ] && [ -z "$SNAPSHOT_DEEP_OUTPUT" ]; then
    patch_snapshot_status "Running" "Unknown" "Snapshot status check is not available yet"
    exit 0
fi

message="$(printf '%s\n' "$SNAPSHOT_DEEP_OUTPUT" | awk '
    /Total shards:/ { total = $3 }
    /Successful shards:/ { successful = $3 }
    /Data processed:/ { data = $3; unit = $4 }
    /Estimated time to completion:/ { sub(/.*: /, ""); eta = $0 }
    END {
        if (total) {
            output = "Shards: " successful "/" total
            if (data != "") output = output " | Data: " data " " unit
            if (eta != "" && eta != "0h 0m 0s") output = output " | ETA: " eta
            print output
        }
    }
')"
if [ -z "$message" ]; then
    message="$(printf '%s' "${status_error:-${SNAPSHOT_DEEP_OUTPUT:-Snapshot is running}}" | head -c 1024)"
fi

patch_snapshot_status "Running" "Running" "$message"
exit 0
