#!/usr/bin/env bash
# scripts/lib/common.sh — shared helpers for the k6 load-test validation scripts.
#
# These validate the k6-operator setup against an ALREADY-RUNNING CDC pipeline (bring it up first
# with deployment/k8s/deployCdcLoadTestConfig.sh up). k6 runs are submitted as Argo Workflows through
# the migration console (`loadtest …`); assertions query the in-cluster services via kubectl.
#
# The pipeline is the one the migration workflow builds, so the things being asserted on are
# workflow-owned: the capture proxy and replayer are Deployments labelled
# migrations.opensearch.org/task, and Kafka is a Strimzi cluster — NOT the bare `kafka` /
# `opensearch-source` Deployments an older, hand-rolled data plane used to create.
#
# Kafka assertions go through `console kafka` in the migration console pod. That is a deliberate
# dependency: the workflow's Kafka exposes only a TLS listener with SCRAM auth, so talking to it
# directly from a shell would mean rebuilding the truststore and credential plumbing that the
# console already has. k6 runs themselves still go through k6-run.sh, so submitting load remains
# console-independent.
#
# Source at the top of each script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/lib/common.sh"
#
# Env overrides: CONTEXT=<kube-context>  NAMESPACE=ma  PROXY_NAME=capture-proxy
#                PROXY_URL=…  KAFKA_TOPIC=…  SOURCE_URL=…  TARGET_URL=…

set -euo pipefail

# ── Cluster targeting ──────────────────────────────────────────────────────────
CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
PROM_SVC="${PROM_SVC:-kube-prometheus-stack-prometheus:9090}"
CONSOLE_POD="${CONSOLE_POD:-migration-console-0}"

# The proxy's name is its key in the migration config's traffic.proxies, and the Kafka topic
# defaults to that same name (traffic.proxies.<name>.kafkaTopic overrides it).
PROXY_NAME="${PROXY_NAME:-capture-proxy}"
KAFKA_TOPIC="${KAFKA_TOPIC:-$PROXY_NAME}"

# Source/target endpoints. The workflow's clusters are whatever the config pointed at; these
# defaults match the testClusters chart, which serves HTTPS with basic auth.
SOURCE_URL="${SOURCE_URL:-https://elasticsearch-master-headless:9200}"
TARGET_URL="${TARGET_URL:-https://opensearch-cluster-master-headless:9200}"
CLUSTER_USERNAME="${CLUSTER_USERNAME:-admin}"
CLUSTER_PASSWORD="${CLUSTER_PASSWORD:-admin}"

# Any in-cluster pod with curl + service DNS, used for proxy/Prometheus probes. The old data-plane
# pod (deploy/opensearch-source) no longer exists — the clusters come from their own charts now —
# so the console pod is the reliable choice.
CURL_POD="${CURL_POD:-$CONSOLE_POD}"
# Console-independent run submitter (kubectl-creates a Workflow against the chart's template).
K6_RUN="${K6_RUN:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/k6-run.sh}"

K()  { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }
kcurl() { K exec "$CURL_POD" -- curl "$@"; }
console_exec() { K exec "$CONSOLE_POD" -- /bin/bash -lc "$*"; }

# The endpoint the CaptureProxy CR published, so the scripts follow the deployed listenPort instead
# of assuming one. Falls back to the workflow's default port when the CR cannot be read.
resolve_proxy_url() {
  local ep
  ep="$(K get captureproxy.migrations.opensearch.org "$PROXY_NAME" \
        -o jsonpath='{.status.serviceEndpoint}' 2>/dev/null || true)"
  echo "https://${ep:-${PROXY_NAME}:9201}"
}
PROXY_URL="${PROXY_URL:-$(resolve_proxy_url)}"

# The proxy and replayer Deployment names. The replayer's is composed by the config processor
# (<proxy>-<target>-<replayer>), so both are discovered by the label the workflow stamps on them
# rather than guessed.
deployment_for_task() {
  K get deploy -l "migrations.opensearch.org/task=$1" \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true
}

# ── Colors and counters ────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BOLD='\033[1m'; NC='\033[0m'
PASS=0; FAIL=0
pass()   { echo -e "  ${GREEN}✓ PASS${NC}  $1"; (( PASS++ )) || true; }
fail()   { echo -e "  ${RED}✗ FAIL${NC}  $1"; (( FAIL++ )) || true; }
info()   { echo -e "  ${YELLOW}ℹ${NC}      $1"; }
header() { echo -e "\n${BOLD}$1${NC}"; }

# ── k6 runs (console-independent, via k6-run.sh + kubectl) ─────────────────────
# The scripts call these with console-style flags, passed straight through to k6-run.sh. Supported:
#   --scenario X  --config NAME  --parallelism N  --auth-secret-name NAME
#   --registry-enabled  --extra-args STR  -e KEY=VAL
submit_k6() {
  # Submit a k6 run WITHOUT waiting; echo the generated run name.
  local scenario="" config="" parallelism="" auth_secret="" extra=""; local -a extra_env=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --scenario) scenario="$2"; shift ;;
      --config) config="$2"; shift ;;
      --parallelism) parallelism="$2"; shift ;;
      --auth-secret-name) auth_secret="$2"; shift ;;
      --registry-enabled) extra_env+=(-e REGISTRY_ENABLED=true) ;;
      --extra-args) extra="$2"; shift ;;
      -e) extra_env+=(-e "$2"); shift ;;
      *) echo "submit_k6: unknown flag $1" >&2; return 2 ;;
    esac
    shift
  done
  # The proxy forwards to the source without adding credentials, so runs submitted without an auth
  # Secret carry the same Basic credentials the probes use. A caller passing its own
  # -e AUTH_USERNAME still wins, since later -e entries override earlier ones.
  if [[ -z "$auth_secret" && -n "$CLUSTER_USERNAME" ]]; then
    extra_env=(-e "AUTH_USERNAME=$CLUSTER_USERNAME" -e "AUTH_PASSWORD=$CLUSTER_PASSWORD" "${extra_env[@]}")
  fi
  local -a args=("$scenario" --target "$PROXY_URL")
  [[ -n "$config" ]] && args+=(--config "$config")
  [[ -n "$parallelism" ]] && args+=(--parallelism "$parallelism")
  [[ -n "$auth_secret" ]] && args+=(--auth-secret-name "$auth_secret")
  [[ -n "$extra" ]] && args+=(--extra-args "$extra")
  [[ ${#extra_env[@]} -gt 0 ]] && args+=("${extra_env[@]}")
  CONTEXT="$CONTEXT" NAMESPACE="$NAMESPACE" bash "$K6_RUN" "${args[@]}"
}

run_k6() {
  # Submit and block until the run finishes (fails the script if it errors/times out).
  local name; name=$(submit_k6 "$@") || return 1
  [[ -n "$name" ]] || { fail "k6 submit produced no run name"; return 1; }
  wait_run "$name"
}

# A run is a Workflow that creates a TestRun and waits on it, so the workflow's phase already folds
# in the operator's verdict. Wait on the workflow, not the TestRun.
wait_run() {
  local name="$1" timeout="${2:-600}" t=0 ph
  while (( t < timeout )); do
    ph=$(K get wf "$name" -o jsonpath='{.status.phase}' 2>/dev/null || echo "")
    case "$ph" in
      Succeeded) return 0 ;;
      Failed|Error) fail "run $name ended in phase '$ph'"; return 1 ;;
    esac
    sleep 6; t=$(( t + 6 ))
  done
  fail "run $name timed out after ${timeout}s"; return 1
}

k6_list() { K get wf -l app=k6-load-test 2>/dev/null; }
# Stop by deleting the Workflow — the TestRun is owned by it and goes too. Deleting the TestRun
# instead would leave the workflow behind waiting on a resource that no longer exists.
k6_stop()     { K delete wf "$1" >/dev/null 2>&1 || true; }
k6_stop_all() { K delete wf -l app=k6-load-test >/dev/null 2>&1 || true; }
k6_active_count() {
  # Count runs not yet in a terminal phase (empty/Pending/Running all count as active).
  K get wf -l app=k6-load-test -o jsonpath='{range .items[*]}{.status.phase}{"\n"}{end}' 2>/dev/null \
    | grep -cvE 'Succeeded|Failed|Error' || true
}

# ── Valkey (chaos / consistency control bus) ──────────────────────────────────
# valkey-cli is already present in the chart's Valkey container, so host-side validation can drive
# the control bus without a permanent HTTP proxy or another client image.
valkey_set() { K exec deploy/valkey -- valkey-cli SET "$1" "$2" >/dev/null; }
valkey_get() { K exec deploy/valkey -- valkey-cli GET "$1" 2>/dev/null || true; }

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

# ── Kafka (Strimzi cluster, reached through `console kafka`) ───────────────────
# `console kafka describe-topic-records <topic>` prints a header plus one row per partition:
#   TOPIC                          PARTITION  RECORDS
#   capture-proxy                  0          180
# Summing the RECORDS column gives the same end-offset total the old kafka-get-offsets.sh call did.
kafka_total_offset() {
  local total=0 records
  while read -r _topic _partition records _rest; do
    [[ "$records" =~ ^[0-9]+$ ]] && (( total += records )) || true
  done < <(console_exec "console kafka describe-topic-records '$KAFKA_TOPIC'" 2>/dev/null | tail -n +2 || true)
  echo "$total"
}

check_kafka_topic() {
  if console_exec "console kafka list-topics" 2>/dev/null | grep -qx "$KAFKA_TOPIC"; then
    pass "$KAFKA_TOPIC exists"
  else fail "$KAFKA_TOPIC not found — proxy may not have connected to Kafka yet"; fi
}

check_kafka_messages() {
  local min="${1:-1}" total; total=$(kafka_total_offset)
  if (( total >= min )); then pass "Kafka end offset: $total messages across all partitions"
  else fail "Kafka end offset: $total messages — expected ≥${min}"; fi
}

# ── Pipeline health ────────────────────────────────────────────────────────────
# Every migration CR reports Ready. This is the workflow's own readiness signal, and it covers
# Kafka — which has no Deployment to inspect, being a Strimzi StatefulSet.
check_migration_resources_ready() {
  local label="${1:-}" inventory notready
  inventory="$(K get kafkaclusters,capturedtraffics,captureproxies,trafficreplays \
    -o jsonpath='{range .items[*]}{.kind}{"/"}{.metadata.name}{" "}{.status.phase}{"\n"}{end}' 2>/dev/null || true)"
  if [[ -z "$inventory" ]]; then
    fail "no migration resources found${label:+ $label} — is the CDC workflow deployed?"
    return
  fi
  notready="$(grep -v ' Ready$' <<<"$inventory" || true)"
  if [[ -z "$notready" ]]; then
    pass "all migration resources Ready${label:+ $label} ($(grep -c . <<<"$inventory") resources)"
  else
    fail "migration resources not Ready${label:+ $label}: $(tr '\n' ' ' <<<"$notready")"
  fi
}

# Availability of the workflow-owned workloads. Addressed by the task label the workflow stamps on
# them, because the replayer's Deployment name is composed by the config processor. Kafka has no
# Deployment to check — it is a Strimzi StatefulSet, covered by check_migration_resources_ready.
# Usage: check_workload_health "(post-burst)"
check_workload_health() {
  local label="${1:-}" task d ready found
  for task in captureProxy trafficReplayer; do
    found=false
    while read -r d; do
      [[ -z "$d" ]] && continue
      found=true
      ready=$(K get deploy "$d" -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "")
      if [[ "${ready:-0}" -ge 1 ]] 2>/dev/null; then pass "$d is available${label:+ $label}"
      else fail "$d is not available${label:+ $label} (availableReplicas: ${ready:-0})"; fi
    done < <(deployment_for_task "$task")
    if [[ "$found" == false ]]; then
      fail "no Deployment labelled task=$task${label:+ $label}"
    fi
  done
  # Explicit: the loop's last command is a test that is false on the happy path, so without this the
  # function would return 1 and `set -e` would abort the caller right after everything passed.
  return 0
}

# Plain Deployment availability by name. Used for workloads that are NOT workflow-owned and so keep
# fixed names — currently Valkey, which the k6LoadTest chart deploys when registry.enabled=true.
# Usage: check_service_health "(post-burst)" valkey
check_service_health() {
  local label="${1:-}"; shift
  for d in "$@"; do
    local ready
    ready=$(K get deploy "$d" -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "")
    if [[ "${ready:-0}" -ge 1 ]] 2>/dev/null; then pass "$d is available${label:+ $label}"
    else fail "$d is not available${label:+ $label} (availableReplicas: ${ready:-0})"; fi
  done
}

check_proxy_ready() {
  # The capture proxy answers HTTPS with a self-signed cert; -k skips verification. It forwards to
  # the source cluster rather than answering itself, so the request needs whatever credentials the
  # source wants — an unauthenticated probe comes back 401 from the source, not from the proxy.
  local code
  code=$(kcurl -sk -u "$CLUSTER_USERNAME:$CLUSTER_PASSWORD" \
           -o /dev/null -w '%{http_code}' "${PROXY_URL}/_cluster/health" 2>/dev/null || true)
  if [[ "$code" == "200" ]]; then pass "Capture Proxy is serving ($PROXY_URL)"
  else fail "Capture Proxy not reachable at $PROXY_URL (HTTP ${code:-no response})"; fi
}

# ── OpenSearch / Elasticsearch (source / target) ───────────────────────────────
os_query() {
  # Raw GET against a cluster: os_query source "/nyc_taxis/_count"
  # The clusters are provided by their own charts now, not by the data plane, so they are reached
  # over the network from CURL_POD rather than by exec'ing into them. -k because the test clusters
  # serve a self-signed cert; credentials are ignored by a cluster running without auth.
  local which="${1:-source}" path="$2" url
  case "$which" in
    source) url="$SOURCE_URL" ;;
    target) url="$TARGET_URL" ;;
    *) echo ""; return ;;
  esac
  kcurl -sk -u "$CLUSTER_USERNAME:$CLUSTER_PASSWORD" "${url}${path}" 2>/dev/null || true
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
