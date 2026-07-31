#!/usr/bin/env bash
# scripts/lib/common.sh — shared helpers for the k6 load-test validation scripts.
#
# These validate the k6-operator setup against an ALREADY-RUNNING data plane (bring it up first
# with buildImages/scripts/deployWorkflowComponents.sh). k6 runs are submitted as TestRuns through
# the migration console (`workflow k6 …`); assertions query the in-cluster services via kubectl.
#
# Source at the top of each script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/lib/common.sh"
#
# Env overrides: CONTEXT=<kube-context>  NAMESPACE=ma

set -euo pipefail

# ── Cluster targeting ──────────────────────────────────────────────────────────
CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
CONSOLE_POD="${CONSOLE_POD:-migration-console-0}"
PROM_SVC="${PROM_SVC:-kube-prometheus-stack-prometheus:9090}"
PROXY_URL="${PROXY_URL:-https://capture-proxy:9200}"
KAFKA_TOPIC="${KAFKA_TOPIC:-logging-traffic-topic}"

K()  { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }
# Run the migration-console `workflow` CLI (drives k6 TestRuns). Requires a console image that
# includes the TestRun-based `workflow k6` command (i.e. built from this branch onward).
WF() { K exec "$CONSOLE_POD" -- workflow "$@"; }
# curl from inside the console pod (in-cluster DNS + TLS to services).
kcurl() { K exec "$CONSOLE_POD" -- curl "$@"; }

# ── Colors and counters ────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'
PASS=0; FAIL=0
pass()   { echo -e "  ${GREEN}✓ PASS${NC}  $1"; (( PASS++ )) || true; }
fail()   { echo -e "  ${RED}✗ FAIL${NC}  $1"; (( FAIL++ )) || true; }
info()   { echo -e "  ${YELLOW}ℹ${NC}      $1"; }
header() { echo -e "\n${BOLD}$1${NC}"; }

# ── k6 runs (via the console CLI) ──────────────────────────────────────────────
run_k6() {
  # Submit a k6 run and block until it finishes. Pass any `workflow k6 run` flags, e.g.
  #   run_k6 --scenario ingest --config ingest-steady
  #   run_k6 --scenario mixed --config mixed-steady --registry-enabled -o INGEST_RATE=80
  WF k6 run --target "$PROXY_URL" --wait "$@"
}

submit_k6() {
  # Submit a k6 run WITHOUT waiting; echo the generated run name (for background/chaos control).
  local out
  out=$(WF k6 run --target "$PROXY_URL" "$@" 2>&1) || { echo "$out" >&2; return 1; }
  echo "$out" | sed -n 's/^Submitted k6 run: //p' | head -1
}

# ── Webdis (chaos / consistency control bus) ───────────────────────────────────
# Values with ':' must be URL-encoded (e.g. "set-rate:10" -> "set-rate%3A10").
webdis_set() { kcurl -sf "http://webdis:7379/SET/${1}/${2}" >/dev/null; }
webdis_get() {
  kcurl -sf "http://webdis:7379/GET/${1}" 2>/dev/null \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('GET') or '')" 2>/dev/null || true
}

# ── Prometheus (kube-prometheus-stack, queried from the console pod) ────────────
prom_query() {
  # Scalar value of a Prometheus instant query, or empty string on error.
  kcurl -sfG "http://${PROM_SVC}/api/v1/query" --data-urlencode "query=$1" 2>/dev/null \
    | python3 -c "import sys,json; d=json.load(sys.stdin); r=d['data']['result']; print(r[0]['value'][1] if r else '')" 2>/dev/null \
    || true
}
prom_range_max() { prom_query "max_over_time(($1)[${2:-10m}:1m])"; }

prom_check_counter() {
  local metric="$1" label="$2" val
  val=$(prom_query "$metric")
  if [[ -n "$val" && "$val" != "0" ]]; then pass "${label} = $val"
  else fail "${label} not found or is 0 in Prometheus"; fi
}

prom_check_rate() {
  local metric="$1" threshold="$2" label="$3" threshold_pct val pct
  threshold_pct=$(python3 -c "print(int(float('$threshold')*100))")
  val=$(prom_query "$metric")
  if [[ -n "$val" ]]; then
    pct=$(python3 -c "print(f'{float(\"$val\")*100:.2f}')" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$val') < $threshold else 1)" 2>/dev/null; then
      pass "${label} = ${pct}% (threshold: <${threshold_pct}%)"
    else fail "${label} = ${pct}% — exceeds ${threshold_pct}% threshold"; fi
  else fail "${label} not found in Prometheus"; fi
}

prom_check_http_error_rate() {
  local threshold="${1:-0.05}" label="${2:-HTTP error rate (4xx/5xx)}"
  prom_check_rate \
    "(sum(rate(http_reqs_total{status=~\"4..|5..\"}[5m])) or vector(0)) / sum(rate(http_reqs_total[5m]))" \
    "$threshold" "$label"
}

prom_check_latency_p95() {
  local name="$1" threshold_ms="$2" p95
  p95=$(prom_query "histogram_quantile(0.95, rate(http_req_duration_milliseconds_bucket{name=\"${name}\"}[5m]))")
  if [[ -n "$p95" ]]; then
    local r; r=$(python3 -c "print(round(float('$p95'), 1))" 2>/dev/null || echo "?")
    if python3 -c "import sys; sys.exit(0 if float('$p95') < ${threshold_ms} else 1)" 2>/dev/null; then
      pass "${name} p95 = ${r}ms (threshold: <${threshold_ms}ms)"
    else fail "${name} p95 = ${r}ms — exceeds ${threshold_ms}ms threshold"; fi
  else info "${name} p95 not yet in Prometheus — may need a moment to scrape"; fi
}

# ── Kafka (data-plane deployment 'kafka') ──────────────────────────────────────
kafka_total_offset() {
  local total=0 line count
  while IFS= read -r line; do
    count=$(echo "$line" | awk -F: '{print $3}' | tr -d '[:space:]')
    [[ "$count" =~ ^[0-9]+$ ]] && (( total += count )) || true
  done < <(K exec deploy/kafka -- /opt/kafka/bin/kafka-get-offsets.sh \
             --bootstrap-server localhost:9092 --topic "$KAFKA_TOPIC" 2>/dev/null || true)
  echo "$total"
}

check_kafka_topic() {
  if K exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null \
     | grep -q "$KAFKA_TOPIC"; then pass "$KAFKA_TOPIC exists"
  else fail "$KAFKA_TOPIC not found — proxy may not have connected to Kafka yet"; fi
}

check_kafka_messages() {
  local min="${1:-1}" total; total=$(kafka_total_offset)
  if (( total >= min )); then pass "Kafka end offset: $total messages across all partitions"
  else fail "Kafka end offset: $total messages — expected ≥${min}"; fi
}

# ── Service health (data-plane deployments) ────────────────────────────────────
check_service_health() {
  # Usage: check_service_health "(post-burst)" kafka opensearch-source capture-proxy
  local label="${1:-}"; shift
  for d in "$@"; do
    local ready
    ready=$(K get deploy "$d" -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "")
    if [[ "${ready:-0}" -ge 1 ]] 2>/dev/null; then pass "$d is available${label:+ $label}"
    else fail "$d is not available${label:+ $label} (availableReplicas: ${ready:-0})"; fi
  done
}

check_proxy_ready() {
  # The capture proxy answers HTTPS with a self-signed cert; -k skips verification.
  if kcurl -sk -o /dev/null -w '%{http_code}' "${PROXY_URL}/_cluster/health" 2>/dev/null | grep -q '^200$'; then
    pass "Capture Proxy is serving ($PROXY_URL)"
  else fail "Capture Proxy not reachable at $PROXY_URL"; fi
}

# ── OpenSearch (source / target) ───────────────────────────────────────────────
os_query() {
  # Raw GET against a cluster: os_query source "/nyc_taxis/_count"
  local which="${1:-source}" path="$2"
  K exec "deploy/opensearch-${which}" -- curl -s "localhost:9200${path}" 2>/dev/null || true
}

check_opensearch_docs() {
  # Usage: check_opensearch_docs nyc_taxis 1 [source|target]
  local index="${1:-nyc_taxis}" min="${2:-1}" which="${3:-source}" doc_count
  doc_count=$(os_query "$which" "/${index}/_count" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo 0)
  if (( doc_count >= min )); then pass "${which}/${index} contains $doc_count documents"
  else fail "${which}/${index} has $doc_count documents — expected ≥${min}"; fi
}

# ── Summary ────────────────────────────────────────────────────────────────────
print_summary() {
  echo ""
  echo "──────────────────────────────────────────────────"
  echo -e "${BOLD}Results: ${GREEN}${PASS} passed${NC}  ${RED}${FAIL} failed${NC}"
  echo "──────────────────────────────────────────────────"
  (( FAIL > 0 )) && exit 1 || true
}
