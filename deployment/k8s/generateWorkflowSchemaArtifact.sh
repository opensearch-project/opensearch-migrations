#!/usr/bin/env bash

set -euo pipefail

MIGRATIONS_REPO_ROOT_DIR=$(git rev-parse --show-toplevel)

KUBE_CONTEXT="${KUBE_CONTEXT:-kind-release-schema-verification}"
NAMESPACE="${NAMESPACE:-ma}"
RELEASE_NAME="${RELEASE_NAME:-ma}"
CHART_PATH="${CHART_PATH:-deployment/k8s/charts/aggregates/migrationAssistantWithArgo}"
OUTPUT_PATH="${OUTPUT_PATH:-${PWD}/workflowMigration.schema.json}"
HELM_TIMEOUT="${HELM_TIMEOUT:-5m}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-300s}"
CHART_VALUES_FILE="${CHART_VALUES_FILE:-}"

IMAGE_REPOSITORY_PREFIX="${IMAGE_REPOSITORY_PREFIX:-}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
IMAGE_PULL_POLICY="${IMAGE_PULL_POLICY:-Always}"

CAPTURE_PROXY_IMAGE_REPOSITORY="${CAPTURE_PROXY_IMAGE_REPOSITORY:-${IMAGE_REPOSITORY_PREFIX:+${IMAGE_REPOSITORY_PREFIX}/opensearch-migrations-traffic-capture-proxy}}"
TRAFFIC_REPLAYER_IMAGE_REPOSITORY="${TRAFFIC_REPLAYER_IMAGE_REPOSITORY:-${IMAGE_REPOSITORY_PREFIX:+${IMAGE_REPOSITORY_PREFIX}/opensearch-migrations-traffic-replayer}}"
REINDEX_FROM_SNAPSHOT_IMAGE_REPOSITORY="${REINDEX_FROM_SNAPSHOT_IMAGE_REPOSITORY:-${IMAGE_REPOSITORY_PREFIX:+${IMAGE_REPOSITORY_PREFIX}/opensearch-migrations-reindex-from-snapshot}}"
MIGRATION_CONSOLE_IMAGE_REPOSITORY="${MIGRATION_CONSOLE_IMAGE_REPOSITORY:-${IMAGE_REPOSITORY_PREFIX:+${IMAGE_REPOSITORY_PREFIX}/opensearch-migrations-console}}"
INSTALLER_IMAGE_REPOSITORY="${INSTALLER_IMAGE_REPOSITORY:-${MIGRATION_CONSOLE_IMAGE_REPOSITORY}}"

if [[ -z "${CAPTURE_PROXY_IMAGE_REPOSITORY}" || -z "${TRAFFIC_REPLAYER_IMAGE_REPOSITORY}" || -z "${REINDEX_FROM_SNAPSHOT_IMAGE_REPOSITORY}" || -z "${MIGRATION_CONSOLE_IMAGE_REPOSITORY}" || -z "${INSTALLER_IMAGE_REPOSITORY}" ]]; then
  echo "Either IMAGE_REPOSITORY_PREFIX or all component image repositories must be provided" >&2
  exit 1
fi

TEMP_VALUES_FILE="$(mktemp /tmp/schema-verification-values.XXXXXX.yaml)"
cleanup() {
  rm -f "${TEMP_VALUES_FILE}"
}
trap cleanup EXIT

cat > "${TEMP_VALUES_FILE}" <<EOF
conditionalPackageInstalls:
  kyverno: false
  argo-workflows: true
  cert-manager: true
  fluent-bit: false
  kube-prometheus-stack: false
  strimzi-kafka-operator: true
  migration-console: true
  localstack: false
  jaeger: false
images:
  captureProxy:
    repository: ${CAPTURE_PROXY_IMAGE_REPOSITORY}
    tag: ${IMAGE_TAG}
    pullPolicy: ${IMAGE_PULL_POLICY}
  trafficReplayer:
    repository: ${TRAFFIC_REPLAYER_IMAGE_REPOSITORY}
    tag: ${IMAGE_TAG}
    pullPolicy: ${IMAGE_PULL_POLICY}
  reindexFromSnapshot:
    repository: ${REINDEX_FROM_SNAPSHOT_IMAGE_REPOSITORY}
    tag: ${IMAGE_TAG}
    pullPolicy: ${IMAGE_PULL_POLICY}
  migrationConsole:
    repository: ${MIGRATION_CONSOLE_IMAGE_REPOSITORY}
    tag: ${IMAGE_TAG}
    pullPolicy: ${IMAGE_PULL_POLICY}
  installer:
    repository: ${INSTALLER_IMAGE_REPOSITORY}
    tag: ${IMAGE_TAG}
    pullPolicy: ${IMAGE_PULL_POLICY}
EOF

cd "${MIGRATIONS_REPO_ROOT_DIR}"

helm dependency update "${CHART_PATH}"

HELM_ARGS=(
  --kube-context "${KUBE_CONTEXT}"
  upgrade --install --create-namespace
  -n "${NAMESPACE}"
  "${RELEASE_NAME}"
  "${CHART_PATH}"
  --wait --timeout "${HELM_TIMEOUT}"
)

if [[ -n "${CHART_VALUES_FILE}" ]]; then
  HELM_ARGS+=(-f "${CHART_VALUES_FILE}")
fi

HELM_ARGS+=(-f "${TEMP_VALUES_FILE}")

helm "${HELM_ARGS[@]}"

kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" rollout status \
  statefulset/migration-console --timeout="${ROLLOUT_TIMEOUT}"

mkdir -p "$(dirname "${OUTPUT_PATH}")"
kubectl --context "${KUBE_CONTEXT}" -n "${NAMESPACE}" cp \
  migration-console-0:/root/schema/workflowMigration.schema.json \
  "${OUTPUT_PATH}"

echo "Copied schema artifact to ${OUTPUT_PATH}"
