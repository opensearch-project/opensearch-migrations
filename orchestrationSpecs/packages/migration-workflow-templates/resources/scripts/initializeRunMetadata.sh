#!/usr/bin/env bash
set -euo pipefail

: "${WORKFLOW_NAME:?}"
: "${WORKFLOW_UID:?}"
: "${WORKFLOW_CREATION_TIMESTAMP:?}"
: "${MIGRATION_RUN_NUMBER:?}"

selector="migrations.opensearch.org/workflow=${WORKFLOW_NAME}"
patch="{\"metadata\":{\"ownerReferences\":[{\"apiVersion\":\"argoproj.io/v1alpha1\",\"kind\":\"Workflow\",\"name\":\"${WORKFLOW_NAME}\",\"uid\":\"${WORKFLOW_UID}\"}]}}"
migration_run_name="${WORKFLOW_NAME}-run-${MIGRATION_RUN_NUMBER}"

kubectl get approvalgates.migrations.opensearch.org -l "$selector" -o name \
  | xargs -r -n 1 kubectl patch --type merge -p "$patch" \
  || { echo "ERROR: failed to patch one or more approvalgate ownerReferences" >&2; exit 1; }

kubectl label migrationruns.migrations.opensearch.org "$migration_run_name" \
  "migrations.opensearch.org/workflow-uid=${WORKFLOW_UID}" \
  --overwrite=true

status_patch="$(jq -nc \
  --arg workflowUid "$WORKFLOW_UID" \
  --arg workflowCreationTimestamp "$WORKFLOW_CREATION_TIMESTAMP" \
  '{status:{workflowUid:$workflowUid,workflowCreationTimestamp:$workflowCreationTimestamp}}')"

kubectl patch migrationruns.migrations.opensearch.org "$migration_run_name" \
  --subresource=status \
  --type=merge \
  -p "$status_patch"
