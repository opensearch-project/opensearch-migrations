#!/usr/bin/env bash
set -eu

: "${CRONJOB_NAME:?}"
: "${SESSION_NAME:?}"
: "${WORKFLOW_NAME:?}"
: "${WORKFLOW_UID:?}"
: "${PARENT_WORKFLOW_NAME:?}"
: "${PARENT_WORKFLOW_UID:?}"
: "${SNAPSHOT_MIGRATION_NAME:?}"
: "${SM_UID:?}"
: "${CONFIG_CHECKSUM:?}"
: "${CHECKSUM_FOR_REPLAYER:?}"
: "${RFS_DEPLOYMENT_NAME:?}"
: "${RFS_COORDINATOR_NAME:?}"
: "${USES_DEDICATED_COORDINATOR:?}"
: "${CONSOLE_IMAGE:?}"
: "${CONSOLE_IMAGE_PULL_POLICY:?}"
: "${FROM_SNAPSHOT_MIGRATION_LABEL:?}"
: "${CONSOLE_CONFIG_BASE64:?}"
: "${WORKFLOW_SCRIPTS_ROOT:?}"
: "${RFS_MONITOR_WORKFLOW_UID_LABEL:?}"
: "${RFS_MONITOR_SESSION_LABEL:?}"

: "${STARTUP_GRACE_SECONDS:=600}"
CLAIMED_AT="$(date -u +%s)"

WORKFLOW_UID_LABEL="${RFS_MONITOR_WORKFLOW_UID_LABEL}"
SESSION_LABEL="${RFS_MONITOR_SESSION_LABEL}"


render_cronjob_yaml() {
    cat <<YAML
apiVersion: batch/v1
kind: CronJob
metadata:
  name: ${CRONJOB_NAME}
  ownerReferences:
    - apiVersion: migrations.opensearch.org/v1alpha1
      kind: SnapshotMigration
      name: ${SNAPSHOT_MIGRATION_NAME}
      uid: ${SM_UID}
      controller: false
      blockOwnerDeletion: true
  labels:
    ${WORKFLOW_UID_LABEL}: "${WORKFLOW_UID}"
    ${SESSION_LABEL}: "${SESSION_NAME}"
    workflows.argoproj.io/workflow: "${WORKFLOW_NAME}"
    migrations.opensearch.org/from-snapshot-migration: "${FROM_SNAPSHOT_MIGRATION_LABEL}"
spec:
  schedule: "*/1 * * * *"
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 1
  failedJobsHistoryLimit: 3
  jobTemplate:
    metadata:
      labels:
        ${WORKFLOW_UID_LABEL}: "${WORKFLOW_UID}"
        ${SESSION_LABEL}: "${SESSION_NAME}"
        workflows.argoproj.io/workflow: "${WORKFLOW_NAME}"
        migrations.opensearch.org/from-snapshot-migration: "${FROM_SNAPSHOT_MIGRATION_LABEL}"
    spec:
      template:
        metadata:
          labels:
            ${WORKFLOW_UID_LABEL}: "${WORKFLOW_UID}"
            ${SESSION_LABEL}: "${SESSION_NAME}"
            workflows.argoproj.io/workflow: "${WORKFLOW_NAME}"
            migrations.opensearch.org/from-snapshot-migration: "${FROM_SNAPSHOT_MIGRATION_LABEL}"
        spec:
          serviceAccountName: argo-workflow-executor
          restartPolicy: Never
          containers:
            - name: rfs-monitor
              image: "${CONSOLE_IMAGE}"
              imagePullPolicy: "${CONSOLE_IMAGE_PULL_POLICY}"
              command: ["/bin/bash", "-lc"]
              args:
                - exec "$WORKFLOW_SCRIPTS_ROOT/rfsMonitorCronJob.sh"
              env:
                - {name: WORKFLOW_UID,               value: "${WORKFLOW_UID}"}
                - {name: PARENT_WORKFLOW_NAME,       value: "${PARENT_WORKFLOW_NAME}"}
                - {name: PARENT_WORKFLOW_UID,        value: "${PARENT_WORKFLOW_UID}"}
                - {name: CLAIMED_AT,                 value: "${CLAIMED_AT}"}
                - {name: SNAPSHOT_MIGRATION_NAME,    value: "${SNAPSHOT_MIGRATION_NAME}"}
                - {name: CONFIG_CHECKSUM,            value: "${CONFIG_CHECKSUM}"}
                - {name: CHECKSUM_FOR_REPLAYER,      value: "${CHECKSUM_FOR_REPLAYER}"}
                - {name: RFS_DEPLOYMENT_NAME,        value: "${RFS_DEPLOYMENT_NAME}"}
                - {name: RFS_COORDINATOR_NAME,       value: "${RFS_COORDINATOR_NAME}"}
                - {name: USES_DEDICATED_COORDINATOR, value: "${USES_DEDICATED_COORDINATOR}"}
                - {name: RFS_CRONJOB_NAME,           value: "${CRONJOB_NAME}"}
                - {name: STARTUP_GRACE_SECONDS,      value: "${STARTUP_GRACE_SECONDS}"}
                - {name: CONSOLE_CONFIG_BASE64,      value: "${CONSOLE_CONFIG_BASE64}"}
                - {name: WORKFLOW_SCRIPTS_ROOT,      value: "${WORKFLOW_SCRIPTS_ROOT}"}
                - {name: RFS_MONITOR_WORKFLOW_UID_LABEL, value: "${RFS_MONITOR_WORKFLOW_UID_LABEL}"}
YAML
}

# Try-create. On 201, skip drain. On 409 (AlreadyExists), patch the
# workflow-uid label, fixed schedule, and jobTemplate, then drain prior-claim Jobs/Pods.
echo "applying CronJob $CRONJOB_NAME"
create_out="$(render_cronjob_yaml | kubectl create -f - 2>&1 || true)"
if echo "$create_out" | grep -q "created"; then
    echo "$create_out"
    echo "fresh create — no drain needed"
else
    if ! echo "$create_out" | grep -qi "AlreadyExists"; then
        echo "create failed unexpectedly: $create_out" >&2
        exit 1
    fi
    echo "exists — patching jobTemplate and draining prior claim"

    patch_payload="$(render_cronjob_yaml \
        | kubectl create --dry-run=client -f - -o json \
        | jq -c --arg uidkey "$WORKFLOW_UID_LABEL" \
            '{
                metadata:{labels:{($uidkey):.metadata.labels[$uidkey]}},
                spec:{schedule:.spec.schedule,jobTemplate:.spec.jobTemplate}
            }')"
    kubectl patch cronjob "$CRONJOB_NAME" --type=merge -p "$patch_payload"

    OLD_FILTER="$SESSION_LABEL=$SESSION_NAME,$WORKFLOW_UID_LABEL!=$WORKFLOW_UID"
    kubectl delete jobs -l "$OLD_FILTER" --force --grace-period=0 --ignore-not-found || true
    kubectl delete pods -l "$OLD_FILTER" --force --grace-period=0 --ignore-not-found || true
fi

# Seed the typed progress status so UI tooling has an authoritative CR status
# field to read before the monitor's first scheduled poll.
now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
seed_status_patch="$(jq -nc \
    --arg updatedAt "$now" \
    '{status:{documentBackfill:{
        phase:"Pending",
        updatedAt:$updatedAt,
        message:"Backfill monitor is installed",
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
    }}}')"
kubectl patch snapshotmigration "$SNAPSHOT_MIGRATION_NAME" \
    --subresource=status --type=merge -p "$seed_status_patch"

# Read-back loop until labels and env converge.
deadline=$(( $(date +%s) + 60 ))
while :; do
    cj="$(kubectl get cronjob "$CRONJOB_NAME" -o json 2>/dev/null || true)"
    if [ -n "$cj" ]; then
        ok=true
        echo "$cj" | jq -e '.metadata.deletionTimestamp == null' >/dev/null || ok=false
        echo "$cj" | jq -e --arg k "$WORKFLOW_UID_LABEL" --arg v "$WORKFLOW_UID" \
            '.metadata.labels[$k] == $v' >/dev/null || ok=false
        echo "$cj" | jq -e --arg k "$SESSION_LABEL" --arg v "$SESSION_NAME" \
            '.metadata.labels[$k] == $v' >/dev/null || ok=false
        echo "$cj" | jq -e --arg k "$WORKFLOW_UID_LABEL" --arg v "$WORKFLOW_UID" \
            '.spec.jobTemplate.metadata.labels[$k] == $v' >/dev/null || ok=false
        echo "$cj" | jq -e --arg k "$SESSION_LABEL" --arg v "$SESSION_NAME" \
            '.spec.jobTemplate.metadata.labels[$k] == $v' >/dev/null || ok=false
        echo "$cj" | jq -e --arg cc "$CONFIG_CHECKSUM" \
            '[.spec.jobTemplate.spec.template.spec.containers[0].env[] | select(.name=="CONFIG_CHECKSUM")][0].value == $cc' \
            >/dev/null || ok=false
        echo "$cj" | jq -e --arg claimed "$CLAIMED_AT" \
            '[.spec.jobTemplate.spec.template.spec.containers[0].env[] | select(.name=="CLAIMED_AT")][0].value == $claimed' \
            >/dev/null || ok=false
        if [ "$ok" = "true" ]; then
            echo "read-back consistent"
            exit 0
        fi
    fi
    if [ "$(date +%s)" -gt "$deadline" ]; then
        echo "read-back never converged within 60s" >&2
        exit 1
    fi
    sleep 2
done
