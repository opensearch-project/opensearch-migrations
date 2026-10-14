#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="${NAMESPACE:-ma}"
# Replayers consume as replayer-<target label>. Empty lets the console infer the group when there's
# only one.
CONSUMER_GROUP="${CONSUMER_GROUP:-}"
CONSOLE_POD="${CONSOLE_POD:-migration-console-0}"
REPLAYER_SELECTOR="${REPLAYER_SELECTOR:-migrations.opensearch.org/task=trafficReplayer}"
CAPTURE_SELECTOR="${CAPTURE_SELECTOR:-migrations.opensearch.org/task=captureProxy}"
PROMETHEUS_SERVICE="${PROMETHEUS_SERVICE:-kube-prometheus-stack-prometheus}"
SINCE="${SINCE:-15m}"
EVENT_PATTERN='event=(replayer_|replay_|kafka_|capture_protocol_|source_connection_|target_|tuple_)'

usage() {
  echo "Usage: $0 {baseline|lag|events|self-test}"
  echo "Environment: NAMESPACE CONSUMER_GROUP CONSOLE_POD REPLAYER_SELECTOR CAPTURE_SELECTOR PROMETHEUS_SERVICE SINCE"
}

# Parses the broker's consumer-group table (GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG
# ...), as printed by kafka-consumer-groups.sh --describe and by console kafka describe-consumer-group,
# which follows it with a REPLAY WATERMARK section that isn't part of the lag.
parse_lag() {
  awk '
    /REPLAY WATERMARK/ { exit }
    $1 == "GROUP" { next }
    NF >= 6 && $6 ~ /^[0-9]+$/ {
      if (group == "") group = $1
      total += $6
      if ($6 > maximum) maximum = $6
      partitions += 1
    }
    END {
      printf "event=kafka_consumer_group_lag group=%s partition_count=%d total_lag=%d max_partition_lag=%d\n",
        group, partitions, total, maximum
    }
  ' 
}

filter_replayer_events() {
  grep -E "$EVENT_PATTERN"
}

# The console already holds the Kafka client config (SCRAM over TLS by default), so ask it rather
# than a broker's plaintext listener.
lag() {
  local output
  if ! output="$(kubectl -n "$NAMESPACE" exec "$CONSOLE_POD" -- \
      console kafka describe-consumer-group ${CONSUMER_GROUP:+"$CONSUMER_GROUP"} 2>&1)"; then
    echo "event=gameday_check_failed check=kafka_lag reason=console_command_failed" >&2
    printf '%s\n' "$output" >&2
    return 1
  fi
  printf '%s\n' "$output" | parse_lag
}

events() {
  echo "event=gameday_log_snapshot component=replayer selector=$REPLAYER_SELECTOR since=$SINCE"
  # -l alone shows only the last 10 lines of each pod.
  kubectl -n "$NAMESPACE" logs -l "$REPLAYER_SELECTOR" --all-containers --tail=-1 --since="$SINCE" \
    | filter_replayer_events \
    | tail -n 200 || true
  echo "event=gameday_log_snapshot component=capture selector=$CAPTURE_SELECTOR since=$SINCE"
  kubectl -n "$NAMESPACE" logs -l "$CAPTURE_SELECTOR" --all-containers --tail=-1 --since="$SINCE" \
    | grep -E 'event=capture_' \
    | tail -n 200 || true
}

prometheus_query() {
  local query="$1"
  local encoded
  encoded="$(printf '%s' "$query" | jq -sRr @uri)"
  kubectl -n "$NAMESPACE" get --raw \
    "/api/v1/namespaces/${NAMESPACE}/services/http:${PROMETHEUS_SERVICE}:9090/proxy/api/v1/query?query=${encoded}"
}

metric_value() {
  local name="$1"
  local query="$2"
  local value
  value="$(prometheus_query "$query" | jq -r '.data.result[0].value[1] // "0"')"
  echo "event=gameday_metric_snapshot metric=$name value=$value"
}

baseline() {
  lag
  metric_value replay_active_contexts 'sum({__name__=~"replayActiveContexts.*"})'
  metric_value replay_oldest_record_age_ms 'max({__name__=~"replayOldestKafkaRecordAge.*"})'
  metric_value replay_owner_operations 'sum({__name__=~"replayActiveOwnerOperations.*"})'
  metric_value tuple_writes_pending 'sum({__name__=~"tupleDurabilityWritesPending.*"})'
  metric_value tuple_oldest_pending_age_ms 'max({__name__=~"replayOldestContextAgeByActivity.*",activity="comparingResults"})'
  metric_value tuple_s3_objects_retrying 'sum({__name__=~"tupleSinkUploadsRetrying.*"})'
  metric_value tuple_s3_retry_attempts 'sum({__name__=~"tupleSinkUploadRetryAttempts.*"})'
  metric_value tuple_s3_recoveries 'sum({__name__=~"tupleSinkUploadRecoveries.*"})'
  metric_value kafka_records_outstanding 'sum({__name__=~"kafkaSourceRecordsOutstanding.*"})'
  metric_value capture_records_awaiting_ack 'sum({__name__=~"capturePublishingRecordsAwaitingAcknowledgement.*"})'
  events
}

self_test() {
  local output
  output="$(
    cat <<'SAMPLE' | parse_lag
GROUP            TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID
replayer-target  capture-proxy  0          12              17              5    consumer-a
replayer-target  capture-proxy  1          20              23              3    consumer-b

REPLAY WATERMARK (record timestamps; not authoritative Kafka backlog drain time)
TOPIC          PARTITION  COMMITTED-OFFSET  OFFSET-LAG  REPLAYED-THROUGH (UTC)    LATEST-RECORD (UTC)
capture-proxy  0          12                5           2026-10-14T04:40:49.862Z  2026-10-14T04:40:49.862Z
SAMPLE
  )"
  [[ "$output" == *"group=replayer-target"* ]]
  [[ "$output" == *"partition_count=2"* ]]
  [[ "$output" == *"total_lag=8"* ]]
  [[ "$output" == *"max_partition_lag=5"* ]]
  local event_output
  event_output="$(
    printf '%s\n' \
      'event=tuple_sink_upload_degraded retrying_objects=1' \
      'unstructured line' \
      'event=tuple_sink_upload_recovered retrying_objects=0' \
      | filter_replayer_events
  )"
  [[ "$event_output" == *"event=tuple_sink_upload_degraded"* ]]
  [[ "$event_output" == *"event=tuple_sink_upload_recovered"* ]]
  echo "event=gameday_self_test outcome=passed"
}

case "${1:-}" in
  baseline) baseline ;;
  lag) lag ;;
  events) events ;;
  self-test) self_test ;;
  *) usage; exit 2 ;;
esac
