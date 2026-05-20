#!/usr/bin/env bash
set -euo pipefail

: "${WORKFLOW_NAME:?}"

kubectl delete approvalgates.migrations.opensearch.org \
  -l "migrations.opensearch.org/workflow=${WORKFLOW_NAME}" \
  --ignore-not-found
