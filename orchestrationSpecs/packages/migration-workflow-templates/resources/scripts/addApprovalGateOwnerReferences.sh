#!/usr/bin/env bash
set -euo pipefail

: "${WORKFLOW_NAME:?}"
: "${WORKFLOW_UID:?}"

selector="migrations.opensearch.org/workflow=${WORKFLOW_NAME}"
patch="{\"metadata\":{\"ownerReferences\":[{\"apiVersion\":\"argoproj.io/v1alpha1\",\"kind\":\"Workflow\",\"name\":\"${WORKFLOW_NAME}\",\"uid\":\"${WORKFLOW_UID}\"}]}}"

kubectl get approvalgates.migrations.opensearch.org -l "$selector" -o name \
  | xargs -r -n 1 kubectl patch --type merge -p "$patch" \
  || { echo "ERROR: failed to patch one or more approvalgate ownerReferences" >&2; exit 1; }
