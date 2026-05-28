#!/usr/bin/env bash

set -euo pipefail

: "${NAMESPACE:?}"
: "${KAFKA_CLUSTER_NAME:?}"
: "${TIMEOUT_SECONDS:?}"

end=$((SECONDS + TIMEOUT_SECONDS))
last_log=-30

while (( SECONDS < end )); do
  if json="$(kubectl -n "$NAMESPACE" get kafkas.kafka.strimzi.io "$KAFKA_CLUSTER_NAME" -o json 2>/tmp/kafka-ready-wait.err)"; then
    if jq -e 'any(.status.conditions[]?; .reason == "InvalidResourceException")' >/dev/null <<<"$json"; then
      jq -r '[.status.conditions[]? | select(.reason == "InvalidResourceException") | .message // .reason] | join(" | ")' <<<"$json" >&2
      exit 2
    fi

    if jq -e 'any(.status.conditions[]?; .type == "Ready" and .status == "True") and ((.status.listeners // []) | length > 0)' >/dev/null <<<"$json"; then
      echo "Kafka/$KAFKA_CLUSTER_NAME is Ready"
      exit 0
    fi

    if (( SECONDS - last_log >= 30 )); then
      conditions="$(jq -r '[.status.conditions[]? | "\(.type)=\(.status):\(.reason // "")"] | join(" | ")' <<<"$json")"
      listeners="$(jq -r '[.status.listeners[]?.bootstrapServers] | join(",")' <<<"$json")"
      echo "Waiting for Kafka/$KAFKA_CLUSTER_NAME Ready; conditions=${conditions:-<none>} listeners=${listeners:-<none>}"
      last_log=$SECONDS
    fi
  else
    if (( SECONDS - last_log >= 30 )); then
      echo "Waiting for Kafka/$KAFKA_CLUSTER_NAME to be readable: $(cat /tmp/kafka-ready-wait.err)"
      last_log=$SECONDS
    fi
  fi

  sleep 5
done

echo "Kafka/$KAFKA_CLUSTER_NAME did not become Ready within ${TIMEOUT_SECONDS}s" >&2
exit 124
