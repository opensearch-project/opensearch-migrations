#!/usr/bin/env bash
# scripts/lib/common.sh — shared helpers sourced by all validate_*.sh scripts.
#
# Source at the top of each validate script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/lib/common.sh"

set -euo pipefail

# ── Colors and counters ────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'

PASS=0; FAIL=0

pass()   { echo -e "  ${GREEN}✓ PASS${NC}  $1"; (( PASS++ )) || true; }
fail()   { echo -e "  ${RED}✗ FAIL${NC}  $1"; (( FAIL++ )) || true; }
info()   { echo -e "  ${YELLOW}ℹ${NC}      $1"; }
header() { echo -e "\n${BOLD}$1${NC}"; }

# ── Project paths ──────────────────────────────────────────────────────────────
# common.sh lives at scripts/lib/; repo root is three levels up.
CAPTURE_IMAGE="migrations/capture_proxy:latest"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
DOCKER_SOLUTION="$PROJECT_ROOT/TrafficCapture/dockerSolution"

# ── Prometheus ─────────────────────────────────────────────────────────────────
prom_query() {
  # Returns the scalar value of a Prometheus instant query, or empty string on error.
  curl -sf "http://localhost:19090/api/v1/query" \
    --data-urlencode "query=$1" 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); r=d['data']['result']; print(r[0]['value'][1] if r else '')" 2>/dev/null \
    || true
}

prom_range_max() {
  # Max value of an expression over the last N minutes (default 10m).
  # Usage: prom_range_max "metric_expr" "5m"
  prom_query "max_over_time(($1)[${2:-10m}:1m])"
}

prom_check_counter() {
  # Assert that a Prometheus counter is non-zero.
  # Usage: prom_check_counter "k6_ingest_bulk_requests_total" "ingest bulk requests"
  local metric="$1" label="$2"
  local val
  val=$(prom_query "$metric")
  if [[ -n "$val" && "$val" != "0" ]]; then
    pass "${label} = $val"
  else
    fail "${label} not found or is 0 in Prometheus"
  fi
}

prom_check_rate() {
  # Assert that a Prometheus rate metric is below a fractional threshold.
  # Usage: prom_check_rate "k6_ingest_errors" 0.05 "ingest error rate"
  local metric="$1" threshold="$2" label="$3"
  local threshold_pct val pct
  threshold_pct=$(python3 -c "print(int(float('$threshold')*100))")
  val=$(prom_query "$metric")
  if [[ -n "$val" ]]; then
    pct=$(python3 -c "print(f'{float(\"$val\")*100:.2f}')" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$val') < $threshold else 1)" 2>/dev/null; then
      pass "${label} = ${pct}% (threshold: <${threshold_pct}%)"
    else
      fail "${label} = ${pct}% — exceeds ${threshold_pct}% threshold"
    fi
  else
    fail "${label} not found in Prometheus"
  fi
}

prom_check_http_error_rate() {
  # Assert that the HTTP 4xx/5xx error rate is below a threshold.
  # Uses http_reqs_total (Counter, reliable in OTLP) with the status label that k6
  # auto-attaches to every HTTP request.  Avoids the k6 Rate-metric export problem
  # where *_errors_total counts all samples, not just error events.
  #
  # When there are zero 4xx/5xx errors the numerator selector matches no series and
  # PromQL returns an empty vector (not 0).  `or vector(0)` coerces empty → 0 so the
  # division yields 0 and the check correctly passes.
  # Usage: prom_check_http_error_rate 0.05 "ingest HTTP error rate"
  local threshold="${1:-0.05}" label="${2:-HTTP error rate (4xx/5xx)}"
  prom_check_rate \
    "(sum(rate(http_reqs_total{status=~\"4..|5..\"}[5m])) or vector(0)) / sum(rate(http_reqs_total[5m]))" \
    "$threshold" "$label"
}

prom_check_latency_p95() {
  # Assert that a named operation's p95 latency is below a threshold in ms.
  # k6 OTLP output sends timing metrics in ms; the otel-collector prometheus exporter
  # preserves that unit, so histogram_quantile returns ms directly.
  # Usage: prom_check_latency_p95 "bulk_write" 3000
  local name="$1" threshold_ms="$2"
  local p95
  p95=$(prom_query "histogram_quantile(0.95, rate(http_req_duration_milliseconds_bucket{name=\"${name}\"}[5m]))")
  if [[ -n "$p95" ]]; then
    local p95_rounded
    p95_rounded=$(python3 -c "print(round(float('$p95'), 1))" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$p95') < ${threshold_ms} else 1)" 2>/dev/null; then
      pass "${name} p95 = ${p95_rounded}ms (threshold: <${threshold_ms}ms)"
    else
      fail "${name} p95 = ${p95_rounded}ms — exceeds ${threshold_ms}ms threshold"
    fi
  else
    info "${name} p95 not yet in Prometheus — may need a moment to scrape"
  fi
}

# ── Kafka ──────────────────────────────────────────────────────────────────────
kafka_total_offset() {
  # Print the sum of end offsets across all partitions of logging-traffic-topic.
  local total=0 line count
  while IFS= read -r line; do
    count=$(echo "$line" | awk -F: '{print $3}' | tr -d '[:space:]')
    [[ "$count" =~ ^[0-9]+$ ]] && (( total += count )) || true
  done < <(docker compose exec -T kafka \
    /opt/kafka/bin/kafka-get-offsets.sh \
    --bootstrap-server localhost:9092 \
    --topic logging-traffic-topic 2>/dev/null || true)
  echo "$total"
}

check_kafka_topic() {
  if docker compose exec -T kafka \
       /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null \
     | grep -q "logging-traffic-topic"; then
    pass "logging-traffic-topic exists"
  else
    fail "logging-traffic-topic not found — proxy may not have connected to Kafka yet"
  fi
}

check_kafka_messages() {
  # Assert that the topic has at least $1 messages (default 1).
  local min="${1:-1}"
  local total
  total=$(kafka_total_offset)
  if (( total >= min )); then
    pass "Kafka end offset: $total messages across all partitions"
  else
    fail "Kafka end offset: $total messages — expected ≥${min}"
  fi
}

# ── Docker / services ──────────────────────────────────────────────────────────
check_service_health() {
  # Check health of one or more docker compose services.
  # Usage: check_service_health "(post-burst)" kafka opensearch-source capture-proxy
  #        check_service_health "" kafka opensearch-source   # no label suffix
  local label="${1:-}"; shift
  for svc in "$@"; do
    local status
    status=$(docker compose ps --format json "$svc" 2>/dev/null \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('Health','unknown'))" 2>/dev/null \
      || docker inspect --format '{{.State.Health.Status}}' \
         "$(docker compose ps -q "$svc" 2>/dev/null)" 2>/dev/null \
      || echo "unknown")
    if [[ "$status" == "healthy" ]]; then
      pass "$svc is healthy${label:+ $label}"
    else
      fail "$svc is not healthy${label:+ $label} (status: $status)"
    fi
  done
}

check_capture_proxy_image() {
  # Check that the Capture Proxy Docker image exists; build it if --with-setup was passed.
  local with_setup="${1:-false}"
  if docker image inspect "$CAPTURE_IMAGE" &>/dev/null; then
    pass "$CAPTURE_IMAGE found locally"
  else
    if [[ "$with_setup" == "true" ]]; then
      info "$CAPTURE_IMAGE not found — building via buildDockerImages (this may take a few minutes)"
      (cd "$DOCKER_SOLUTION" && "$PROJECT_ROOT/gradlew" buildDockerImages)
      if docker image inspect "$CAPTURE_IMAGE" &>/dev/null; then
        pass "$CAPTURE_IMAGE built successfully"
      else
        fail "$CAPTURE_IMAGE still not found after build — check Gradle output above"
        exit 1
      fi
    else
      fail "$CAPTURE_IMAGE not found locally"
      echo -e "       Build it with:\n         cd TrafficCapture/dockerSolution && ../../../gradlew buildDockerImages"
      echo -e "       Or re-run this script with --with-setup to build automatically."
      exit 1
    fi
  fi
}

# ── OpenSearch ─────────────────────────────────────────────────────────────────
check_opensearch_docs() {
  # Assert that an index contains at least $2 documents (default 1).
  # Usage: check_opensearch_docs nyc_taxis 1
  local index="${1:-nyc_taxis}" min="${2:-1}"
  local doc_count
  doc_count=$(curl -sf "http://localhost:19200/${index}/_count" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null \
    || echo 0)
  if (( doc_count >= min )); then
    pass "${index} index contains $doc_count documents"
  else
    fail "${index} index has $doc_count documents — expected ≥${min}"
  fi
}

# ── k6 env file loading ────────────────────────────────────────────────────────
load_k6_env() {
  # Populate the global env_flags array with -e KEY=VALUE pairs from an env file.
  # docker compose run does not support --env-file, so flags must be passed explicitly.
  # Usage: load_k6_env "k6-config/ingest-steady.env"
  #        docker compose run --rm "${env_flags[@]}" k6 run ...
  env_flags=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    env_flags+=(-e "$line")
  done < "$1"
}

# ── Summary ────────────────────────────────────────────────────────────────────
print_summary() {
  echo ""
  echo "──────────────────────────────────────────────────"
  echo -e "${BOLD}Results: ${GREEN}${PASS} passed${NC}  ${RED}${FAIL} failed${NC}"
  echo "──────────────────────────────────────────────────"
  if (( FAIL > 0 )); then
    exit 1
  fi
}
