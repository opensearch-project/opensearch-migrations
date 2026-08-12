#!/usr/bin/env bash
#
# deployCdcWorkflow.sh — stand up a CDC (capture-and-replay) pipeline the supported way: write a
# migration config, hand it to the migration console, submit the workflow, wait on the resulting
# custom resources.
#
#     k6 / client → capture-proxy → source cluster        (capture path)
#                   capture-proxy → Kafka → replayer → target cluster   (replay path)
#
# This script is a convenience driver, NOT an alternate deployment mechanism. Every component it
# brings up is created by the migration workflow from the config in deployment/k8s/configs — the
# same two commands you would run by hand inside the console pod:
#
#     workflow configure edit --stdin < config.yaml
#     workflow submit
#
# It then polls the CRs the workflow reconciles (kafkaclusters, capturedtraffics, captureproxies,
# trafficreplays) until each reports status.phase=Ready, and reports the proxy endpoint that the
# CaptureProxy CR actually published rather than a hardcoded URL.
#
# Because the topology lives entirely in the config, ANY CDC setup is expressible without touching
# this script: more proxies, more replayers, an S3 traffic source, an external Kafka, cert-manager
# TLS. The resource names to wait on are read back out of the config.
#
# Requires the control plane (Argo, Strimzi, cert-manager, migration console) and the source/target
# clusters to be up already — i.e. run kindTesting.sh first.
#
# Usage:
#   ./deployCdcWorkflow.sh up                  # configure + submit + wait + print the k6 command
#   ./deployCdcWorkflow.sh up -f my-cdc.yaml   # ... from your own migration config
#   ./deployCdcWorkflow.sh render              # dry run: print the config `up` would submit
#   ./deployCdcWorkflow.sh status              # CR phases, workflow phase, proxy endpoint
#   ./deployCdcWorkflow.sh down                # delete the migration resources + the k6 chart
#
# Env overrides:
#   CONTEXT=<kube-context>  NAMESPACE=ma  CONFIG_FILE=<path>
#   SOURCE_ENDPOINT=https://host:9200   TARGET_ENDPOINT=...   (skip cluster auto-detection)
#   SOURCE_AUTH=true  TARGET_AUTH=true  (only read when the matching *_ENDPOINT is set, since
#                                        setting one skips the probe that would decide this;
#                                        defaults to false, i.e. no authConfig in the config)
#   SOURCE_VERSION="ES 7.10.2"          (skip version auto-detection)
#   CLUSTER_USERNAME=admin  CLUSTER_PASSWORD=admin
#   PROXY_NAME=capture-proxy  PROXY_PORT=9201  PROXY_SERVICE_TYPE=ClusterIP  PROXY_REPLICAS=1
#   REPLAYER_NAME=replay1  SPEEDUP_FACTOR=2
#   READY_TIMEOUT=1800  POLL_INTERVAL=10
set -euo pipefail

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
CONSOLE_POD="${CONSOLE_POD:-migration-console-0}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="${CONFIG_FILE:-${SCRIPT_DIR}/configs/cdcLoadTest.yaml}"

# The workflow that `workflow submit` creates. Matches DEFAULT_WORKFLOW_NAME in the console CLI.
WORKFLOW_NAME="${WORKFLOW_NAME:-migration-workflow}"

# Config placeholders. Anything already exported wins, so `SOURCE_ENDPOINT=... ./deployCdcWorkflow.sh up`
# skips the corresponding auto-detection.
PROXY_NAME="${PROXY_NAME:-capture-proxy}"
PROXY_PORT="${PROXY_PORT:-9201}"
PROXY_SERVICE_TYPE="${PROXY_SERVICE_TYPE:-ClusterIP}"
PROXY_REPLICAS="${PROXY_REPLICAS:-1}"
REPLAYER_NAME="${REPLAYER_NAME:-replay1}"
SPEEDUP_FACTOR="${SPEEDUP_FACTOR:-2}"
SOURCE_SECRET="${SOURCE_SECRET:-source-creds}"
TARGET_SECRET="${TARGET_SECRET:-target-creds}"
CLUSTER_USERNAME="${CLUSTER_USERNAME:-admin}"
CLUSTER_PASSWORD="${CLUSTER_PASSWORD:-admin}"

READY_TIMEOUT="${READY_TIMEOUT:-1800}"
POLL_INTERVAL="${POLL_INTERVAL:-10}"

# Rendered config path. Global rather than function-local so the EXIT trap can still see it after
# the creating function has returned (a local would be out of scope, and `set -u` turns that into
# an error on the way out).
RENDERED_CONFIG=""
cleanup() { [[ -n "$RENDERED_CONFIG" ]] && rm -f "$RENDERED_CONFIG"; return 0; }
trap cleanup EXIT

# Service names the testClusters chart creates. Probed in order; the first that exists is used.
SOURCE_SVC_CANDIDATES=(elasticsearch-master-headless elasticsearch-master)
TARGET_SVC_CANDIDATES=(opensearch-cluster-master-headless opensearch-cluster-master)

# k6 load-test chart (operator + example TestRuns + RBAC). Installed here — NOT by the migration
# deploy.
K6_CHART="${SCRIPT_DIR}/charts/components/k6LoadTest"
K6_RELEASE="k6-load-test"
# A run pulls two images. The runner is stock grafana/k6, through GCR's Docker Hub mirror.
K6_IMAGE="${K6_IMAGE:-mirror.gcr.io/grafana/k6:latest}"
# The scenarios and presets ride in migrations/k6_scripts (built from TrafficCapture/trafficLoadTest
# by buildImages) and are mounted at /scripts. Being a migrations/* image it lives in the same
# registry as the migration's own images, so the default is derived from captureProxyImage in
# install_k6_chart rather than hardcoded. Set K6_SCRIPTS_IMAGE to point somewhere else.
K6_SCRIPTS_IMAGE="${K6_SCRIPTS_IMAGE:-}"

K() { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }
say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[1;33m!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }
img()  { K get cm migration-image-config -o jsonpath="{.data.$1}" 2>/dev/null; }

# Run a command inside the migration console. Stdin is forwarded, so this also carries the config
# into `workflow configure edit --stdin`.
console() { K exec -i "$CONSOLE_POD" -- /bin/bash -lc "$*"; }

# HTTP status code for a curl run from inside the cluster. curl's own -w prints 000 when it cannot
# connect, so a non-zero exit is not an error here — it is one of the answers we are looking for.
probe_code() {
  local out
  out="$(K exec "$CONSOLE_POD" -- curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@" 2>/dev/null)" || true
  echo "${out:-000}"
}

# Body of a successful in-cluster GET, used to read the cluster's version document.
probe_body() {
  K exec "$CONSOLE_POD" -- curl -s --max-time 10 "$@" 2>/dev/null || true
}

# ── Preflight ──────────────────────────────────────────────────────────────────
preflight() {
  say "Preflight (context=$CONTEXT namespace=$NAMESPACE)"
  kubectl --context "$CONTEXT" get ns "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace $NAMESPACE not found"
  K get cm migration-image-config >/dev/null 2>&1 \
    || die "migration-image-config missing (control plane not up? run kindTesting.sh)"
  K get pod "$CONSOLE_POD" >/dev/null 2>&1 \
    || die "$CONSOLE_POD not found (control plane not up? run kindTesting.sh)"
  K wait --for=condition=ready "pod/$CONSOLE_POD" --timeout=300s >/dev/null \
    || die "$CONSOLE_POD did not become ready"
  # The workflow creates a Strimzi Kafka; without the operator the KafkaCluster CR never goes Ready.
  K get deploy strimzi-cluster-operator >/dev/null 2>&1 \
    || die "strimzi-cluster-operator not found — the auto-created Kafka cluster cannot reconcile"
  for crd in captureproxies trafficreplays capturedtraffics kafkaclusters; do
    kubectl --context "$CONTEXT" get crd "${crd}.migrations.opensearch.org" >/dev/null 2>&1 \
      || die "CRD ${crd}.migrations.opensearch.org missing (migration assistant chart not installed?)"
  done
  K get svc otel-collector >/dev/null 2>&1 \
    || warn "otel-collector Service not found — proxy/replayer metrics won't be collected"
  ok "control plane present"
}

# ── Cluster + auth discovery ───────────────────────────────────────────────────
# Find the first candidate Service that exists, so the script works against either the headless or
# the clusterIP Service the testClusters chart creates.
find_service() {
  local svc
  for svc in "$@"; do
    if K get svc "$svc" >/dev/null 2>&1; then echo "$svc"; return 0; fi
  done
  return 1
}

# Determine scheme and whether credentials are required, by trying the combinations in the order
# that distinguishes them: plain first, then TLS, then TLS with credentials. Echoes "<scheme> <auth>".
detect_access() {
  local host="$1" scheme code
  for scheme in http https; do
    local -a tls_flag=()
    [[ "$scheme" == https ]] && tls_flag=(-k)
    code="$(probe_code "${tls_flag[@]}" "$scheme://$host:9200/")"
    [[ "$code" == 200 ]] && { echo "$scheme false"; return 0; }
    if [[ "$code" == 401 || "$code" == 403 ]]; then
      code="$(probe_code "${tls_flag[@]}" -u "$CLUSTER_USERNAME:$CLUSTER_PASSWORD" "$scheme://$host:9200/")"
      [[ "$code" == 200 ]] && { echo "$scheme true"; return 0; }
      die "$host answered $code and rejected $CLUSTER_USERNAME credentials — set CLUSTER_USERNAME/CLUSTER_PASSWORD"
    fi
  done
  return 1
}

# "ES 7.10.2" / "OS 2.19.1", read off the cluster's own root document.
detect_version() {
  local endpoint="$1" auth="$2" body
  local -a args=(-k)
  [[ "$auth" == true ]] && args+=(-u "$CLUSTER_USERNAME:$CLUSTER_PASSWORD")
  body="$(probe_body "${args[@]}" "$endpoint/")"
  python3 -c '
import json, sys
try:
    v = json.loads(sys.stdin.read()).get("version", {})
except Exception:
    sys.exit(1)
number = v.get("number")
if not number:
    sys.exit(1)
print(("OS " if v.get("distribution") == "opensearch" else "ES ") + number)
' <<<"$body"
}

# Emit the `authConfig:` stanza for a cluster, indented to sit under the cluster key, or nothing at
# all when the cluster takes no auth. authConfig is an optional union in the schema, so "no auth"
# has to mean the key is absent — an empty `authConfig: {}` fails validation.
auth_block() {
  local needed="$1" secret="$2"
  [[ "$needed" == true ]] || return 0
  printf '    authConfig:\n      basic:\n        secretName: %s' "$secret"
}

# Idempotent: `up` is meant to be re-runnable, and `credentials create` refuses to clobber an
# existing secret. On the second run we rotate it instead, so a changed CLUSTER_PASSWORD actually
# takes effect rather than silently reusing a stale one.
ensure_credentials() {
  local secret="$1" out
  say "Ensure HTTP Basic credentials '$secret'"
  if out="$(printf '%s:%s' "$CLUSTER_USERNAME" "$CLUSTER_PASSWORD" \
            | console "workflow configure credentials create --stdin --silent '$secret'" 2>&1)"; then
    ok "created $secret"
    return 0
  fi
  if grep -qi "already exist" <<<"$out"; then
    if out="$(printf '%s:%s' "$CLUSTER_USERNAME" "$CLUSTER_PASSWORD" \
              | console "workflow configure credentials update --stdin --silent '$secret'" 2>&1)"; then
      ok "updated existing $secret"
      return 0
    fi
  fi
  sed 's/^/  /' <<<"$out"
  die "could not create or update credentials '$secret'"
}

discover_clusters() {
  say "Discover source + target clusters"

  local source_auth=false target_auth=false

  if [[ -n "${SOURCE_ENDPOINT:-}" ]]; then
    ok "source endpoint from environment: $SOURCE_ENDPOINT"
    [[ -n "${SOURCE_AUTH:-}" ]] && source_auth="$SOURCE_AUTH"
  else
    local svc scheme auth
    svc="$(find_service "${SOURCE_SVC_CANDIDATES[@]}")" \
      || die "no source cluster Service found (${SOURCE_SVC_CANDIDATES[*]}). Install the testClusters chart or set SOURCE_ENDPOINT."
    read -r scheme auth < <(detect_access "$svc") \
      || die "could not reach source Service '$svc' on 9200 over http or https"
    SOURCE_ENDPOINT="$scheme://$svc:9200"
    source_auth="$auth"
    ok "source: $SOURCE_ENDPOINT (auth=$auth)"
  fi

  if [[ -n "${TARGET_ENDPOINT:-}" ]]; then
    ok "target endpoint from environment: $TARGET_ENDPOINT"
    [[ -n "${TARGET_AUTH:-}" ]] && target_auth="$TARGET_AUTH"
  else
    local svc scheme auth
    svc="$(find_service "${TARGET_SVC_CANDIDATES[@]}")" \
      || die "no target cluster Service found (${TARGET_SVC_CANDIDATES[*]}). Install the testClusters chart or set TARGET_ENDPOINT."
    read -r scheme auth < <(detect_access "$svc") \
      || die "could not reach target Service '$svc' on 9200 over http or https"
    TARGET_ENDPOINT="$scheme://$svc:9200"
    target_auth="$auth"
    ok "target: $TARGET_ENDPOINT (auth=$auth)"
  fi

  if [[ -z "${SOURCE_VERSION:-}" ]]; then
    SOURCE_VERSION="$(detect_version "$SOURCE_ENDPOINT" "$source_auth")" \
      || die "could not read the source cluster version — set SOURCE_VERSION explicitly"
    ok "source version: $SOURCE_VERSION"
  fi

  [[ "$source_auth" == true ]] && ensure_credentials "$SOURCE_SECRET"
  [[ "$target_auth" == true ]] && ensure_credentials "$TARGET_SECRET"

  SOURCE_AUTH_BLOCK="$(auth_block "$source_auth" "$SOURCE_SECRET")"
  TARGET_AUTH_BLOCK="$(auth_block "$target_auth" "$TARGET_SECRET")"
  export SOURCE_ENDPOINT TARGET_ENDPOINT SOURCE_VERSION SOURCE_AUTH_BLOCK TARGET_AUTH_BLOCK

  # The capture proxy forwards to the source without adding credentials, so a load test has to send
  # whatever the SOURCE wants. Carry that through to the k6 command printed at the end.
  K6_AUTH_ARGS=""
  if [[ "$source_auth" == true ]]; then
    K6_AUTH_ARGS=" -e AUTH_USERNAME=$CLUSTER_USERNAME -e AUTH_PASSWORD=$CLUSTER_PASSWORD"
  fi
}

# ── Config rendering + submission ──────────────────────────────────────────────
# Substitute ONLY the documented placeholders, so a fully-literal config passed with -f survives
# untouched (a bare `envsubst` would eat any other $NAME in the file).
render_config() {
  local out="$1"
  export PROXY_NAME PROXY_PORT PROXY_SERVICE_TYPE PROXY_REPLICAS REPLAYER_NAME SPEEDUP_FACTOR
  envsubst '${SOURCE_ENDPOINT} ${TARGET_ENDPOINT} ${SOURCE_VERSION} ${SOURCE_AUTH_BLOCK}
            ${TARGET_AUTH_BLOCK} ${PROXY_NAME} ${PROXY_PORT} ${PROXY_SERVICE_TYPE}
            ${PROXY_REPLICAS} ${REPLAYER_NAME} ${SPEEDUP_FACTOR}' \
    < "$CONFIG_FILE" > "$out"
  python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$out" \
    || die "rendered config is not valid YAML: $out"
}

# Keys of a nested mapping in the config, one per line — the resource names the workflow will
# create, and therefore the ones to wait on.
config_keys() {
  python3 -c '
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1])) or {}
cur = doc
for part in sys.argv[2].split("."):
    cur = (cur or {}).get(part) or {}
if isinstance(cur, dict):
    print("\n".join(cur.keys()))
' "$1" "$2"
}

submit_workflow() {
  local config="$1"
  say "Load configuration into the migration console"
  console 'workflow configure edit --stdin' < "$config" 2>&1 | sed 's/^/  /'
  say "Submit the migration workflow"
  console 'workflow submit' 2>&1 | sed 's/^/  /'
}

# ── Readiness ──────────────────────────────────────────────────────────────────
workflow_phase() {
  K get workflow.argoproj.io "$WORKFLOW_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true
}

dump_cr_diagnostics() {
  local kind="$1" name="$2"
  warn "$kind/$name diagnostics:"
  K get "$kind.migrations.opensearch.org" "$name" -o jsonpath='{.status.message}' 2>/dev/null | sed 's/^/    /' || true
  echo
  K describe "$kind.migrations.opensearch.org" "$name" 2>&1 | tail -25 | sed 's/^/    /' || true
}

MIGRATION_KINDS="kafkaclusters,capturedtraffics,captureproxies,trafficreplays"

# One line of "<Kind> <name> <phase>" per migration CR in the namespace.
cr_inventory() {
  K get $MIGRATION_KINDS \
    -o jsonpath='{range .items[*]}{.kind}{" "}{.metadata.name}{" "}{.status.phase}{"\n"}{end}' 2>/dev/null || true
}

# How many CRs of each kind this config should produce. Counts, NOT names: only the KafkaCluster and
# CaptureProxy CRs are named after their config key. The others are composed by the config
# processor — a CapturedTraffic is "<proxy>-topic", and a TrafficReplay is
# "<proxy>-<target>-<replayer>". Reproducing those rules here would silently rot the moment they
# change, so the script counts what it expects and discovers the actual names from the cluster.
expected_counts() {
  local config="$1"
  python3 -c '
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1])) or {}
traffic = doc.get("traffic") or {}
proxies = len(traffic.get("proxies") or {})
s3 = len(traffic.get("s3Sources") or {})
print("KafkaCluster", len(doc.get("kafkaClusterConfiguration") or {}))
print("CapturedTraffic", proxies + s3)
print("CaptureProxy", proxies)
print("TrafficReplay", len(traffic.get("replayers") or {}))
' "$config"
}

# Gate on CR readiness, with the workflow as a fail-fast signal. Success is every expected CR
# present and Ready. If the workflow succeeds but produced a different number of CRs than counted
# (a config shape that skips one, say), that is reported rather than silently waited out.
wait_for_resources() {
  local config="$1" deadline=$(( SECONDS + READY_TIMEOUT ))
  local inventory wf last="" summary
  say "Wait for migration resources to become Ready (timeout=${READY_TIMEOUT}s)"

  local -A expected=()
  while read -r kind count; do expected["$kind"]="$count"; done < <(expected_counts "$config")

  while (( SECONDS < deadline )); do
    inventory="$(cr_inventory)"
    wf="$(workflow_phase)"

    if grep -q ' Error$' <<<"$inventory"; then
      local kind name
      while read -r kind name phase; do
        [[ "$phase" == Error ]] && dump_cr_diagnostics "$(tr '[:upper:]' '[:lower:]' <<<"$kind")" "$name"
      done <<<"$inventory"
      die "a migration resource entered Error phase"
    fi
    if [[ "$wf" == Failed || "$wf" == Error ]]; then
      warn "workflow/$WORKFLOW_NAME phase=$wf — inspect with: workflow status"
      sed 's/^/    /' <<<"$inventory"
      die "migration workflow failed before all resources became Ready"
    fi

    # Per kind: how many exist, and how many of those are Ready.
    local kind want have ready all_ready=true all_present=true
    summary=""
    for kind in KafkaCluster CapturedTraffic CaptureProxy TrafficReplay; do
      want="${expected[$kind]:-0}"
      (( want == 0 )) && continue
      have="$(grep -c "^$kind " <<<"$inventory" || true)"
      ready="$(grep -c "^$kind .* Ready$" <<<"$inventory" || true)"
      summary+="${kind}=${ready}/${want} "
      (( have >= want )) || all_present=false
      (( ready >= want )) || all_ready=false
    done

    if [[ "$all_ready" == true ]]; then
      say "All migration resources Ready"
      sed 's/^/  /' <<<"$inventory"
      return 0
    fi

    # The workflow patches each CR Ready as its final step for that resource, so a Succeeded
    # workflow means nothing further is coming. Anything still not Ready is a genuine mismatch.
    if [[ "$wf" == Succeeded ]]; then
      if [[ "$all_present" == true ]]; then
        warn "workflow Succeeded but not every resource reports Ready:"
      else
        warn "workflow Succeeded having created fewer resources than the config implies ($summary):"
      fi
      sed 's/^/    /' <<<"$inventory"
      die "migration resources did not all reach Ready"
    fi

    if [[ "$summary" != "$last" ]]; then
      printf '  … %s(workflow=%s)\n' "$summary" "${wf:-<none>}"
      last="$summary"
    fi
    sleep "$POLL_INTERVAL"
  done

  warn "state at timeout:"
  sed 's/^/    /' <<<"$(cr_inventory)"
  die "timed out after ${READY_TIMEOUT}s waiting for migration resources to become Ready"
}

# The endpoint the CaptureProxy CR published — the authoritative one, whatever the config asked for.
proxy_endpoint() {
  local name="$1" svc lb
  svc="$(K get captureproxy.migrations.opensearch.org "$name" -o jsonpath='{.status.serviceEndpoint}' 2>/dev/null || true)"
  lb="$(K get captureproxy.migrations.opensearch.org "$name" -o jsonpath='{.status.loadBalancerEndpoint}' 2>/dev/null || true)"
  [[ -n "$lb" ]] && { echo "https://$lb"; return 0; }
  [[ -n "$svc" ]] && { echo "https://$svc"; return 0; }
  # A proxy that exists but has not published an endpoint yet is not an error — callers print
  # nothing for it. Returning non-zero here would abort them under `set -e`.
  return 0
}

install_k6_chart() {
  say "Install k6 load-test chart (operator + example TestRuns + RBAC)"
  command -v helm >/dev/null || die "helm not found (needed to install the k6LoadTest chart)"
  if [ -z "$K6_SCRIPTS_IMAGE" ]; then
    # Reuse the registry the migration's own images came from: strip everything from "migrations/"
    # onward off captureProxyImage and append the scripts image. A registry that flattens images
    # into one repo (ECR: <repo>:migrations_capture_proxy_latest) has no such prefix to reuse —
    # pass K6_SCRIPTS_IMAGE explicitly there.
    local proxy_img prefix; proxy_img=$(img captureProxyImage)
    case "$proxy_img" in
      *migrations/*) prefix="${proxy_img%migrations/*}" ;;
      *)             prefix="" ;;
    esac
    K6_SCRIPTS_IMAGE="${prefix}migrations/k6_scripts:latest"
  fi
  helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
  # Vendor the k6-operator subchart: offline from Chart.lock if already vendored, else fetch it.
  helm dependency build "$K6_CHART" >/dev/null 2>&1 \
    || helm dependency update "$K6_CHART" >/dev/null 2>&1 \
    || die "helm dependency build failed for $K6_CHART"
  local repo="${K6_IMAGE%:*}" tag="${K6_IMAGE##*:}"
  local s_repo="${K6_SCRIPTS_IMAGE%:*}" s_tag="${K6_SCRIPTS_IMAGE##*:}"
  # Always re-pull the scripts image: it is rebuilt in place under a moving tag while iterating on
  # scenarios, so IfNotPresent would pin runner pods to whatever the node cached first.
  helm --kube-context "$CONTEXT" upgrade --install "$K6_RELEASE" "$K6_CHART" -n "$NAMESPACE" \
    --set image.repository="$repo" --set image.tag="$tag" --set image.pullPolicy=IfNotPresent \
    --set scriptsImage.repository="$s_repo" --set scriptsImage.tag="$s_tag" \
    --set scriptsImage.pullPolicy=Always \
    --timeout 300s 2>&1 | sed 's/^/  /'
  K rollout status deploy -l app.kubernetes.io/name=k6-operator --timeout=180s 2>&1 | tail -1 | sed 's/^/  /' || true
  ok "k6 operator + example TestRuns installed (runner: $K6_IMAGE, scripts: $K6_SCRIPTS_IMAGE)"
}

cmd_up() {
  preflight
  discover_clusters

  RENDERED_CONFIG="$(mktemp -t cdc-config-XXXXXX.yaml)"
  say "Render migration config from $(basename "$CONFIG_FILE")"
  render_config "$RENDERED_CONFIG"
  sed 's/^/  /' "$RENDERED_CONFIG"

  submit_workflow "$RENDERED_CONFIG"
  wait_for_resources "$RENDERED_CONFIG"
  install_k6_chart

  # Read the proxy name back from the cluster rather than the config: the CaptureProxy CR is the
  # thing that actually published an endpoint.
  local first_proxy endpoint
  first_proxy="$(K get captureproxies.migrations.opensearch.org \
    -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  endpoint="$(proxy_endpoint "$first_proxy")"

  cmd_status
  say "Ready — run a load test"
  cat <<EOF
  Capture proxy is up at:  ${endpoint}

  Run k6 against it (from the migration console pod, or anywhere with cluster context):
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- workflow k6 run \\
      --scenario ingest --config ingest-steady --target ${endpoint}${K6_AUTH_ARGS}
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- workflow k6 list

  Traffic: k6 → ${first_proxy} → source, and ${first_proxy} → Kafka → replayer → target
  Tear down with:  $0 down
EOF
}

# Dry run: discover, render, validate, and print the config that `up` would submit. Creates
# nothing in the cluster except the credential secrets discovery decides are needed.
cmd_render() {
  preflight
  discover_clusters
  RENDERED_CONFIG="$(mktemp -t cdc-config-XXXXXX.yaml)"
  say "Rendered migration config (from $(basename "$CONFIG_FILE"))"
  render_config "$RENDERED_CONFIG"
  cat "$RENDERED_CONFIG"
  say "Resources this config would create"
  local name
  while read -r name; do [[ -n "$name" ]] && echo "  kafkacluster/$name"; done < <(config_keys "$RENDERED_CONFIG" kafkaClusterConfiguration)
  while read -r name; do [[ -n "$name" ]] && echo "  capturedtraffic/$name + captureproxy/$name"; done < <(config_keys "$RENDERED_CONFIG" traffic.proxies)
  while read -r name; do [[ -n "$name" ]] && echo "  trafficreplay/$name"; done < <(config_keys "$RENDERED_CONFIG" traffic.replayers)
}

cmd_status() {
  say "Migration resources (namespace=$NAMESPACE)"
  # kubectl still exits 0 and prints a lone header row when nothing matches, so emptiness is
  # detected from the inventory rather than from the exit status.
  local inventory wf
  inventory="$(cr_inventory)"
  if [[ -z "$inventory" ]]; then
    warn "no migration resources — nothing deployed (bring one up with: $0 up)"
  else
    K get $MIGRATION_KINDS \
      -o custom-columns='KIND:.kind,NAME:.metadata.name,PHASE:.status.phase' 2>/dev/null \
      | sed 's/^/  /'
  fi
  wf="$(workflow_phase)"
  printf '  workflow/%s phase=%s\n' "$WORKFLOW_NAME" "${wf:-<none>}"

  local name endpoint
  while read -r name; do
    [[ -z "$name" ]] && continue
    endpoint="$(proxy_endpoint "$name")"
    [[ -n "$endpoint" ]] && printf '  proxy %s endpoint: %s\n' "$name" "$endpoint"
  done < <(K get captureproxies.migrations.opensearch.org -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)
}

cmd_down() {
  say "Uninstall k6 load-test chart (operator + example TestRuns + RBAC)"
  if command -v helm >/dev/null; then
    helm --kube-context "$CONTEXT" uninstall "$K6_RELEASE" -n "$NAMESPACE" 2>&1 | sed 's/^/  /' || true
  fi

  say "Delete the migration resources"
  # --include-proxies because capture proxies are protected by default: they carry live client
  # traffic, so `reset` will not remove one unless asked. --delete-storage drops the Kafka PVCs so a
  # later `up` starts from an empty topic rather than replaying whatever this run captured.
  console "workflow reset --all --cascade --include-proxies --delete-storage" 2>&1 | sed 's/^/  /' || true

  say "Delete the migration workflow"
  K delete workflow.argoproj.io "$WORKFLOW_NAME" --ignore-not-found 2>&1 | sed 's/^/  /' || true

  ok "migration resources + k6 chart torn down (control plane and clusters left intact)"
}

COMMAND="${1:-up}"
[[ $# -gt 0 ]] && shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f|--config) CONFIG_FILE="$2"; shift 2 ;;
    *) die "unknown option '$1'" ;;
  esac
done

case "$COMMAND" in
  up)     [[ -f "$CONFIG_FILE" ]] || die "config file not found: $CONFIG_FILE"; cmd_up ;;
  render) [[ -f "$CONFIG_FILE" ]] || die "config file not found: $CONFIG_FILE"; cmd_render ;;
  status) cmd_status ;;
  down)   cmd_down ;;
  -h|--help) sed -n '2,42p' "$0" ;;
  *) die "unknown command '${COMMAND}' (use up | render | status | down)" ;;
esac
