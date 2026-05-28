#!/usr/bin/env bash

set -euo pipefail

: "${NAMESPACE:?}"
: "${KAFKA_CLUSTER_NAME:?}"
: "${TIMEOUT_SECONDS:?}"
: "${DIAGNOSTIC_LOG_PATH:?}"

mkdir -p "$(dirname "$DIAGNOSTIC_LOG_PATH")"
exec > >(tee "$DIAGNOSTIC_LOG_PATH") 2>&1

run_diag() {
  echo
  echo "+ $*"
  "$@" || true
}

required_diag_failures=0

run_required_diag() {
  echo
  echo "+ $*"
  if "$@"; then
    return
  else
    local rc=$?
    echo "Required diagnostic command failed with exit code $rc: $*" >&2
    required_diag_failures=$((required_diag_failures + 1))
  fi
}

resource_json() {
  local resource="$1"
  local name="$2"
  kubectl -n "$NAMESPACE" get "$resource" "$name" -o json 2>/dev/null || true
}

resource_phase() {
  local json="$1"
  if [[ -z "$json" ]]; then
    echo "NotFound"
    return
  fi
  printf '%s' "$json" | jq -r '.status.phase // "Unknown"' 2>/dev/null || echo "Unknown"
}

strimzi_conditions() {
  local json="$1"
  if [[ -z "$json" ]]; then
    echo "NotFound"
    return
  fi
  printf '%s' "$json" \
    | jq -r '[.status.conditions[]? | "\(.type)=\(.status):\(.reason // ""):\(.message // "")"] | join(" | ")' 2>/dev/null \
    || echo "Unknown"
}

strimzi_listeners() {
  local json="$1"
  if [[ -z "$json" ]]; then
    echo ""
    return
  fi
  printf '%s' "$json" \
    | jq -r '[.status.listeners[]?.bootstrapServers] | join(",")' 2>/dev/null \
    || echo ""
}

pod_summary() {
  kubectl -n "$NAMESPACE" get pods -l "strimzi.io/cluster=$KAFKA_CLUSTER_NAME" -o json 2>/dev/null \
    | jq -r '[.items[]? | "\(.metadata.name):\(.status.phase):ready=\(([.status.conditions[]? | select(.type == "Ready") | .status][0]) // "Unknown")"] | join(",")' 2>/dev/null \
    || echo ""
}

kafka_cluster_json="$(resource_json kafkaclusters.migrations.opensearch.org "$KAFKA_CLUSTER_NAME")"
strimzi_kafka_json="$(resource_json kafkas.kafka.strimzi.io "$KAFKA_CLUSTER_NAME")"
kafka_cluster_phase="$(resource_phase "$kafka_cluster_json")"
conditions="$(strimzi_conditions "$strimzi_kafka_json")"
listeners="$(strimzi_listeners "$strimzi_kafka_json")"
pods="$(pod_summary)"

message="Strimzi Kafka/$KAFKA_CLUSTER_NAME did not become Ready within ${TIMEOUT_SECONDS}s or hit a fatal readiness condition; KafkaCluster=$kafka_cluster_phase StrimziKafkaConditions=${conditions:-<none>} listeners=${listeners:-<none>} pods=${pods:-<none>}"
echo "$message"

echo
echo "Kafka readiness diagnostics for kafka/$KAFKA_CLUSTER_NAME in namespace $NAMESPACE"
run_required_diag kubectl -n "$NAMESPACE" get kafkaclusters.migrations.opensearch.org "$KAFKA_CLUSTER_NAME" -o yaml
run_required_diag kubectl -n "$NAMESPACE" get kafkas.kafka.strimzi.io "$KAFKA_CLUSTER_NAME" -o yaml
run_required_diag kubectl -n "$NAMESPACE" get kafkanodepools.kafka.strimzi.io -l "strimzi.io/cluster=$KAFKA_CLUSTER_NAME" -o yaml
run_diag kubectl -n "$NAMESPACE" get strimzipodsets.core.strimzi.io -l "strimzi.io/cluster=$KAFKA_CLUSTER_NAME" -o yaml
run_diag kubectl -n "$NAMESPACE" get kafkatopics.kafka.strimzi.io -l "strimzi.io/cluster=$KAFKA_CLUSTER_NAME" -o yaml
run_diag kubectl -n "$NAMESPACE" get kafkausers.kafka.strimzi.io -l "strimzi.io/cluster=$KAFKA_CLUSTER_NAME" -o yaml
run_required_diag kubectl -n "$NAMESPACE" get deployment,statefulset,pod,service,persistentvolumeclaim -l "strimzi.io/cluster=$KAFKA_CLUSTER_NAME" -o wide
run_diag kubectl -n "$NAMESPACE" describe pods -l "strimzi.io/cluster=$KAFKA_CLUSTER_NAME"
run_diag kubectl -n "$NAMESPACE" describe deployment "$KAFKA_CLUSTER_NAME-entity-operator"
run_diag kubectl -n "$NAMESPACE" logs deployment/"$KAFKA_CLUSTER_NAME-entity-operator" --all-containers --tail=200
run_diag kubectl -n "$NAMESPACE" get pods -l name=strimzi-cluster-operator -o wide
run_diag kubectl -n "$NAMESPACE" logs -l name=strimzi-cluster-operator --all-containers --tail=300
run_diag kubectl -n "$NAMESPACE" get pods -l strimzi.io/kind=cluster-operator -o wide
run_diag kubectl -n "$NAMESPACE" logs -l strimzi.io/kind=cluster-operator --all-containers --tail=300

echo
echo "+ kubectl -n $NAMESPACE get events --sort-by=.lastTimestamp | tail -n 120"
kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp 2>/dev/null | tail -n 120 || true

if (( required_diag_failures > 0 )); then
  echo "Kafka diagnostic collection failed: $required_diag_failures required command(s) failed" >&2
  exit 2
fi

echo "Kafka diagnostics collected successfully"
