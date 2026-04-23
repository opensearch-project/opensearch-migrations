#!/usr/bin/env bash

set -euo pipefail

CLUSTER_NAME="${WORKFLOW_TEST_KIND_CLUSTER_NAME:-console-link-test}"

command -v kind >/dev/null 2>&1 || {
  echo "Missing required command: kind" >&2
  exit 1
}

if kind get clusters | grep -Fxq "${CLUSTER_NAME}"; then
  echo "Deleting kind cluster ${CLUSTER_NAME}..."
  kind delete cluster --name "${CLUSTER_NAME}"
  echo "Deleted ${CLUSTER_NAME}"
else
  echo "Kind cluster ${CLUSTER_NAME} does not exist; nothing to delete."
fi
