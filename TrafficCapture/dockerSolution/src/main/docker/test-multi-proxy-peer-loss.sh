#!/usr/bin/env bash

set -euo pipefail

PROXY_ONE_URL="${PROXY_ONE_URL:-https://localhost:9200}"
PROXY_TWO_URL="${PROXY_TWO_URL:-https://localhost:9201}"
SOURCE_URL="${SOURCE_URL:-https://localhost:19200}"
TARGET_URL="${TARGET_URL:-https://localhost:29200}"
RUN_ID="$(date +%s)-$RANDOM"
TOPIC="${TOPIC:-multi-proxy-peer-loss-$RUN_ID}"
GROUP_ID="${GROUP_ID:-multi-proxy-peer-loss-$RUN_ID}"
INDEX_NAME="${INDEX_NAME:-multi-proxy-peer-loss-$RUN_ID}"
TOPIC_PARTITIONS="${TOPIC_PARTITIONS:-2}"
GRACEFUL_STOP_TIMEOUT_SECONDS="${GRACEFUL_STOP_TIMEOUT_SECONDS:-60}"
TOTAL_FLEET_RETENTION_SECONDS="${TOTAL_FLEET_RETENTION_SECONDS:-55}"
REPLACEMENT_OBSERVATION_SECONDS="${REPLACEMENT_OBSERVATION_SECONDS:-10}"
SUSTAINED_LOAD_WORKERS_PER_PROXY="${SUSTAINED_LOAD_WORKERS_PER_PROXY:-3}"
SUSTAINED_LOAD_PRIMARY_REQUESTS_PER_WORKER="${SUSTAINED_LOAD_PRIMARY_REQUESTS_PER_WORKER:-60}"
SUSTAINED_LOAD_SECONDARY_REQUESTS_PER_WORKER="${SUSTAINED_LOAD_SECONDARY_REQUESTS_PER_WORKER:-20}"
SUSTAINED_LOAD_INTERVAL_SECONDS="${SUSTAINED_LOAD_INTERVAL_SECONDS:-0.2}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-$SCRIPT_DIR/docker-compose.yml}"
PARTIAL_PID=""
PRIMARY_LOAD_PIDS=()
SECONDARY_LOAD_PIDS=()

stop_partial_request() {
    if [[ -n "$PARTIAL_PID" ]]; then
        kill "$PARTIAL_PID" >/dev/null 2>&1 || true
        wait "$PARTIAL_PID" >/dev/null 2>&1 || true
        PARTIAL_PID=""
    fi
}

stop_load_workers() {
    local pid
    for pid in "$@"; do
        kill "$pid" >/dev/null 2>&1 || true
        wait "$pid" >/dev/null 2>&1 || true
    done
}

cleanup() {
    stop_partial_request
    if ((${#PRIMARY_LOAD_PIDS[@]})); then
        stop_load_workers "${PRIMARY_LOAD_PIDS[@]}"
    fi
    if ((${#SECONDARY_LOAD_PIDS[@]})); then
        stop_load_workers "${SECONDARY_LOAD_PIDS[@]}"
    fi
}
trap cleanup EXIT

container_id() {
    docker ps \
        --filter "label=com.docker.compose.service=$1" \
        --format '{{.ID}}' \
        | head -1
}

kafka_exec() {
    local kafka_container
    kafka_container="$(container_id kafka)"
    [[ -n "$kafka_container" ]] || {
        echo "Kafka container is not running" >&2
        return 1
    }
    docker exec "$kafka_container" "$@"
}

compose_project() {
    local kafka_container
    kafka_container="$(container_id kafka)"
    [[ -n "$kafka_container" ]] || {
        echo "Kafka container is not running" >&2
        return 1
    }
    docker inspect "$kafka_container" \
        --format '{{ index .Config.Labels "com.docker.compose.project" }}'
}

recreate_traffic_services() {
    local project
    project="$(compose_project)"
    [[ -n "$project" ]] || {
        echo "Could not determine the Docker Compose project" >&2
        return 1
    }

    KAFKA_TRAFFIC_TOPIC="$TOPIC" \
        KAFKA_TRAFFIC_GROUP_ID="$GROUP_ID" \
        docker compose \
            --project-name "$project" \
            --file "$COMPOSE_FILE" \
            up \
            --detach \
            --force-recreate \
            --no-deps \
            "$@" >/dev/null
}

start_isolated_traffic_stack() {
    kafka_exec /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --create \
        --if-not-exists \
        --topic "$TOPIC" \
        --partitions "$TOPIC_PARTITIONS" \
        --replication-factor 1 >/dev/null

    recreate_traffic_services capture-proxy replayer
}

wait_for_http() {
    local url="$1"
    local username="$2"
    local password="$3"
    for _ in $(seq 1 60); do
        if curl -skf -u "$username:$password" "$url/_cluster/health" >/dev/null; then
            return 0
        fi
        sleep 1
    done
    echo "Timed out waiting for $url" >&2
    return 1
}

target_count() {
    curl -sk -u admin:myStrongPassword123! "$TARGET_URL/$INDEX_NAME/_count" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count", 0))' 2>/dev/null \
        || echo 0
}

source_count() {
    curl -sk -u admin:admin "$SOURCE_URL/$INDEX_NAME/_count" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count", 0))' 2>/dev/null \
        || echo 0
}

load_generator_count() {
    local base_url="$1"
    local username="$2"
    local password="$3"
    local generator="$4"
    curl -skf -u "$username:$password" \
        -X POST "$base_url/$INDEX_NAME/_count" \
        -H 'Content-Type: application/json' \
        -d "{\"query\":{\"term\":{\"load_generator.keyword\":\"$generator\"}}}" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin).get("count", 0))'
}

consumer_lag() {
    kafka_exec /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --describe \
        --group "$GROUP_ID" 2>/dev/null \
        | awk -v group="$GROUP_ID" -v topic="$TOPIC" '
            $1 == group && $2 == topic {
                lag += $6
                found = 1
            }
            END {
                if (found) {
                    print lag
                } else {
                    print -1
                }
            }
        '
}

record_type_count() {
    local record_type="$1"
    local record_limit
    local records
    record_limit="$(
        kafka_exec /opt/kafka/bin/kafka-get-offsets.sh \
            --bootstrap-server localhost:9092 \
            --topic "$TOPIC" \
            --time -1 2>/dev/null \
            | awk -F: '{ total += $3 } END { print total + 0 }'
    )"
    if [[ "$record_limit" == "0" ]]; then
        echo 0
        return
    fi

    records="$(
        kafka_exec /opt/kafka/bin/kafka-console-consumer.sh \
            --bootstrap-server localhost:9092 \
            --topic "$TOPIC" \
            --from-beginning \
            --max-messages "$record_limit" \
            --timeout-ms 10000 \
            --property print.headers=true \
            --property print.value=false 2>/dev/null || true
    )"
    awk -v record_type="$record_type" '
        index($0, "opensearch-capture-record-type:" record_type) {
            count++
        }
        END {
            print count + 0
        }
    ' <<<"$records"
}

traffic_record_count() {
    record_type_count traffic-stream-v1
}

wait_for_target_count() {
    local expected="$1"
    local actual=0
    for _ in $(seq 1 90); do
        actual="$(target_count)"
        if [[ "$actual" == "$expected" ]]; then
            return 0
        fi
        sleep 1
    done
    echo "Target count was $actual; expected $expected" >&2
    return 1
}

wait_for_lag() {
    local comparison="$1"
    local lag=-1
    for _ in $(seq 1 120); do
        lag="$(consumer_lag)"
        if [[ "$comparison" == "positive" && "$lag" =~ ^[0-9]+$ && "$lag" -gt 0 ]]; then
            echo "$lag"
            return 0
        fi
        if [[ "$comparison" == "zero" && "$lag" == "0" ]]; then
            echo "$lag"
            return 0
        fi
        sleep 1
    done
    echo "Consumer lag never became $comparison; last value was $lag" >&2
    return 1
}

wait_for_traffic_count_above() {
    local baseline="$1"
    local actual="$baseline"
    for _ in $(seq 1 30); do
        actual="$(traffic_record_count)"
        if [[ "$actual" =~ ^[0-9]+$ && "$actual" -gt "$baseline" ]]; then
            echo "$actual"
            return 0
        fi
        sleep 1
    done
    echo "Traffic record count never exceeded $baseline; last value was $actual" >&2
    return 1
}

wait_for_record_type_count_at_least() {
    local record_type="$1"
    local expected="$2"
    local actual=0
    for _ in $(seq 1 60); do
        actual="$(record_type_count "$record_type")"
        if [[ "$actual" =~ ^[0-9]+$ && "$actual" -ge "$expected" ]]; then
            echo "$actual"
            return 0
        fi
        sleep 1
    done
    echo "$record_type count was $actual; expected at least $expected" >&2
    return 1
}

assert_positive_lag() {
    local description="$1"
    local lag
    lag="$(consumer_lag)"
    if [[ ! "$lag" =~ ^[0-9]+$ || "$lag" -le 0 ]]; then
        echo "$description did not retain positive lag; observed $lag" >&2
        return 1
    fi
    echo "$lag"
}

wait_for_retained_head_diagnostics() {
    local replayer_container="$1"
    local since="$2"
    for _ in $(seq 1 75); do
        if docker logs --since "$since" "$replayer_container" 2>&1 \
            | grep -Eq 'KafkaHeartbeat.*inflight=[1-9][0-9]*.*commitHeads=\[\{.*ownedRecords=[1-9][0-9]*/'; then
            return 0
        fi
        sleep 1
    done
    echo "Replayer did not emit retained commit-head diagnostics after total proxy loss" >&2
    return 1
}

put_document() {
    local proxy_url="$1"
    local document_id="$2"
    curl -skf -u admin:admin \
        -X PUT "$proxy_url/$INDEX_NAME/_doc/$document_id?refresh=true" \
        -H 'Content-Type: application/json' \
        -d "{\"proxy\":\"$document_id\"}" >/dev/null
}

put_load_document() {
    local proxy_url="$1"
    local generator="$2"
    local worker="$3"
    local sequence="$4"
    local document_id="sustained-$generator-$worker-$sequence"
    curl -skf -u admin:admin \
        -X PUT "$proxy_url/$INDEX_NAME/_doc/$document_id?refresh=true" \
        -H 'Content-Type: application/json' \
        -d "{\"load_generator\":\"$generator\",\"worker\":$worker,\"sequence\":$sequence}" >/dev/null
}

run_sustained_writes() {
    local proxy_url="$1"
    local generator="$2"
    local worker="$3"
    local request_count="$4"
    local sequence
    for sequence in $(seq 1 "$request_count"); do
        put_load_document "$proxy_url" "$generator" "$worker" "$sequence"
        sleep "$SUSTAINED_LOAD_INTERVAL_SECONDS"
    done
}

wait_for_load_workers() {
    local description="$1"
    shift
    local failed=0
    local pid
    for pid in "$@"; do
        if ! wait "$pid"; then
            failed=1
        fi
    done
    if [[ "$failed" -ne 0 ]]; then
        echo "$description load generator failed" >&2
        return 1
    fi
}

any_load_worker_running() {
    local pid
    for pid in "$@"; do
        if kill -0 "$pid" >/dev/null 2>&1; then
            return 0
        fi
    done
    return 1
}

start_incomplete_request() {
    local port="$1"
    python3 - "$INDEX_NAME" "$port" <<'PY' &
import socket
import ssl
import sys
import time

index_name = sys.argv[1]
port = int(sys.argv[2])
raw_socket = socket.create_connection(("localhost", port), timeout=10)
context = ssl._create_unverified_context()
tls_socket = context.wrap_socket(raw_socket, server_hostname="localhost")
headers = (
    f"POST /{index_name}/_doc/incomplete HTTP/1.1\r\n"
    "Host: localhost\r\n"
    "Authorization: Basic YWRtaW46YWRtaW4=\r\n"
    "Content-Type: application/json\r\n"
    "Content-Length: 4194304\r\n"
    "\r\n"
).encode()
tls_socket.sendall(headers)
tls_socket.sendall(b'{"payload":"' + (b"x" * (2 * 1024 * 1024)))
time.sleep(300)
PY
    PARTIAL_PID=$!
}

gracefully_stop_proxy() {
    local service="$1"
    local container
    container="$(container_id "$service")"
    [[ -n "$container" ]] || {
        echo "$service container is not running" >&2
        return 1
    }

    docker stop --time "$GRACEFUL_STOP_TIMEOUT_SECONDS" "$container" >/dev/null
    local logs
    logs="$(docker logs "$container" 2>&1)"
    if ! grep -q "Done stopping the proxy" <<<"$logs"; then
        echo "$service did not finish its graceful shutdown hook" >&2
        return 1
    fi
    if grep -q "Unable to publish graceful Kafka writer-completion declarations" <<<"$logs"; then
        echo "$service failed to publish its graceful writer-completion declarations" >&2
        return 1
    fi
}

echo "Starting isolated traffic services on topic $TOPIC..."
start_isolated_traffic_stack

echo "Waiting for the initial capture proxy and target..."
wait_for_http "$PROXY_ONE_URL" admin admin
wait_for_http "$TARGET_URL" admin myStrongPassword123!

echo "Writing through the initial proxy..."
put_document "$PROXY_ONE_URL" proxy-one-before-graceful-stop
wait_for_target_count 1
[[ "$(source_count)" == "1" ]] || {
    echo "Source did not contain the initial document" >&2
    exit 1
}
wait_for_lag zero >/dev/null

echo "Creating non-final work before the only proxy leaves gracefully..."
declarations_before_graceful_stop="$(record_type_count proxy-no-more-writes-v1)"
traffic_records_before_graceful_stop="$(traffic_record_count)"
start_incomplete_request 9200
traffic_records_after_graceful_stop="$(wait_for_traffic_count_above "$traffic_records_before_graceful_stop")"
lag_before_graceful_stop="$(wait_for_lag positive)"
echo "Traffic records increased from $traffic_records_before_graceful_stop to $traffic_records_after_graceful_stop"
echo "Consumer lag before graceful departure: $lag_before_graceful_stop"

echo "Gracefully stopping the only proxy..."
gracefully_stop_proxy capture-proxy
stop_partial_request
expected_graceful_declarations=$((declarations_before_graceful_stop + TOPIC_PARTITIONS))
declarations_after_graceful_stop="$(
    wait_for_record_type_count_at_least proxy-no-more-writes-v1 "$expected_graceful_declarations"
)"
wait_for_lag zero >/dev/null
[[ "$(source_count)" == "1" && "$(target_count)" == "1" ]] || {
    echo "Graceful departure changed completed document counts" >&2
    exit 1
}
echo "Graceful departure emitted at least one self-declaration per partition and settled replay"
echo "No-more-writes declarations increased from $declarations_before_graceful_stop to $declarations_after_graceful_stop"

echo "Starting two proxies to verify scale-up..."
recreate_traffic_services capture-proxy capture-proxy-2
wait_for_http "$PROXY_ONE_URL" admin admin
wait_for_http "$PROXY_TWO_URL" admin admin
put_document "$PROXY_ONE_URL" proxy-one-after-scale-up
put_document "$PROXY_TWO_URL" proxy-two-after-scale-up
completed_document_count=3
wait_for_target_count "$completed_document_count"
wait_for_lag zero >/dev/null

echo "Running sustained concurrent traffic through both proxies..."
for worker in $(seq 1 "$SUSTAINED_LOAD_WORKERS_PER_PROXY"); do
    run_sustained_writes \
        "$PROXY_ONE_URL" \
        proxy-one \
        "$worker" \
        "$SUSTAINED_LOAD_PRIMARY_REQUESTS_PER_WORKER" &
    PRIMARY_LOAD_PIDS+=("$!")
    run_sustained_writes \
        "$PROXY_TWO_URL" \
        proxy-two \
        "$worker" \
        "$SUSTAINED_LOAD_SECONDARY_REQUESTS_PER_WORKER" &
    SECONDARY_LOAD_PIDS+=("$!")
done

wait_for_load_workers "Secondary proxy" "${SECONDARY_LOAD_PIDS[@]}"
SECONDARY_LOAD_PIDS=()
if ! any_load_worker_running "${PRIMARY_LOAD_PIDS[@]}"; then
    echo "Primary proxy load did not remain active until the membership transition" >&2
    exit 1
fi

declarations_before_loaded_scale_down="$(record_type_count proxy-no-more-writes-v1)"
echo "Gracefully scaling down to one proxy while primary-proxy traffic continues..."
gracefully_stop_proxy capture-proxy-2
expected_loaded_scale_down_declarations=$((declarations_before_loaded_scale_down + TOPIC_PARTITIONS))
declarations_after_loaded_scale_down="$(
    wait_for_record_type_count_at_least \
        proxy-no-more-writes-v1 \
        "$expected_loaded_scale_down_declarations"
)"
if ! any_load_worker_running "${PRIMARY_LOAD_PIDS[@]}"; then
    echo "Primary proxy load did not remain active through the membership transition" >&2
    exit 1
fi

wait_for_load_workers "Primary proxy" "${PRIMARY_LOAD_PIDS[@]}"
PRIMARY_LOAD_PIDS=()
sustained_primary_documents=$((SUSTAINED_LOAD_WORKERS_PER_PROXY * SUSTAINED_LOAD_PRIMARY_REQUESTS_PER_WORKER))
sustained_secondary_documents=$((SUSTAINED_LOAD_WORKERS_PER_PROXY * SUSTAINED_LOAD_SECONDARY_REQUESTS_PER_WORKER))
completed_document_count=$((completed_document_count + sustained_primary_documents + sustained_secondary_documents))
wait_for_target_count "$completed_document_count"
wait_for_lag zero >/dev/null

source_primary_documents="$(load_generator_count "$SOURCE_URL" admin admin proxy-one)"
source_secondary_documents="$(load_generator_count "$SOURCE_URL" admin admin proxy-two)"
target_primary_documents="$(
    load_generator_count "$TARGET_URL" admin myStrongPassword123! proxy-one
)"
target_secondary_documents="$(
    load_generator_count "$TARGET_URL" admin myStrongPassword123! proxy-two
)"
[[ "$source_primary_documents" == "$sustained_primary_documents" \
    && "$target_primary_documents" == "$sustained_primary_documents" ]] || {
    echo "Primary load count did not converge: source=$source_primary_documents target=$target_primary_documents expected=$sustained_primary_documents" >&2
    exit 1
}
[[ "$source_secondary_documents" == "$sustained_secondary_documents" \
    && "$target_secondary_documents" == "$sustained_secondary_documents" ]] || {
    echo "Secondary load count did not converge: source=$source_secondary_documents target=$target_secondary_documents expected=$sustained_secondary_documents" >&2
    exit 1
}
echo "Sustained traffic converged across both proxies; no-more-writes declarations increased from $declarations_before_loaded_scale_down to $declarations_after_loaded_scale_down"

put_document "$PROXY_ONE_URL" proxy-one-after-scale-down
completed_document_count=$((completed_document_count + 1))
wait_for_target_count "$completed_document_count"
wait_for_lag zero >/dev/null

echo "Rejoining the second proxy after scale-down..."
recreate_traffic_services capture-proxy-2
wait_for_http "$PROXY_TWO_URL" admin admin
put_document "$PROXY_TWO_URL" proxy-two-after-rejoin
completed_document_count=$((completed_document_count + 1))
wait_for_target_count "$completed_document_count"
wait_for_lag zero >/dev/null

echo "Creating a non-final Kafka record on proxy 2..."
traffic_records_before_partial="$(traffic_record_count)"
start_incomplete_request 9201
traffic_records_after_partial="$(wait_for_traffic_count_above "$traffic_records_before_partial")"
echo "Traffic records increased from $traffic_records_before_partial to $traffic_records_after_partial"
lag_before_loss="$(wait_for_lag positive)"
echo "Consumer lag before peer loss: $lag_before_loss"

proxy_two_container="$(container_id capture-proxy-2)"
[[ -n "$proxy_two_container" ]] || {
    echo "Second capture proxy container is not running" >&2
    exit 1
}
echo "Abruptly terminating proxy 2..."
docker kill "$proxy_two_container" >/dev/null
stop_partial_request

echo "Verifying the surviving proxy remains writable..."
put_document "$PROXY_ONE_URL" proxy-one-after-loss
completed_document_count=$((completed_document_count + 1))
wait_for_target_count "$completed_document_count"

echo "Waiting for replayed peer-departure evidence to clear Kafka lag..."
wait_for_lag zero >/dev/null
[[ "$(source_count)" == "$completed_document_count" ]] || {
    echo "Source document count did not converge to $completed_document_count" >&2
    exit 1
}

echo "Peer loss settled the stranded connection without lookahead scanning"

echo "Creating a non-final Kafka record before total proxy-fleet loss..."
traffic_records_before_total_loss="$(traffic_record_count)"
start_incomplete_request 9200
traffic_records_after_total_loss="$(wait_for_traffic_count_above "$traffic_records_before_total_loss")"
echo "Traffic records increased from $traffic_records_before_total_loss to $traffic_records_after_total_loss"
lag_before_total_loss="$(wait_for_lag positive)"
echo "Consumer lag before total fleet loss: $lag_before_total_loss"

proxy_one_container="$(container_id capture-proxy)"
replayer_container="$(container_id replayer)"
[[ -n "$proxy_one_container" && -n "$replayer_container" ]] || {
    echo "Surviving proxy or replayer container is not running" >&2
    exit 1
}
total_loss_started_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
echo "Abruptly terminating the final capture proxy..."
docker kill "$proxy_one_container" >/dev/null
stop_partial_request

echo "Verifying unresolved work remains retained with no surviving proxy..."
sleep "$TOTAL_FLEET_RETENTION_SECONDS"
lag_without_proxies="$(assert_positive_lag "Total proxy-fleet loss")"
wait_for_retained_head_diagnostics "$replayer_container" "$total_loss_started_at"
echo "Consumer lag remained at $lag_without_proxies with retained-head diagnostics"

echo "Starting a fresh replacement proxy..."
recreate_traffic_services capture-proxy
wait_for_http "$PROXY_ONE_URL" admin admin
sleep "$REPLACEMENT_OBSERVATION_SECONDS"
lag_after_replacement="$(assert_positive_lag "Fresh replacement proxy")"
[[ "$(source_count)" == "$completed_document_count" \
    && "$(target_count)" == "$completed_document_count" ]] || {
    echo "A replacement proxy changed completed document counts for unresolved prior-node work" >&2
    exit 1
}

echo "PASS: sustained multi-proxy load and graceful scaling converged, peer loss recovered, total fleet loss retained, and replacement did not settle predecessor work"
