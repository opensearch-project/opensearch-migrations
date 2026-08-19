#!/usr/bin/env bash
#
# deployCdcWorkflow.sh — stand up a CDC (capture-and-replay) pipeline the supported way: hand a
# migration config to the migration console, submit the workflow, wait on the resulting custom
# resources.
#
#     k6 / client → capture-proxy → source cluster        (capture path)
#                   capture-proxy → Kafka → replayer → target cluster   (replay path)
#
# This is a convenience driver, NOT an alternate deployment mechanism. Every component it brings up
# is created by the migration workflow from the config in deployment/k8s/configs — the same two
# commands you would run by hand inside the console pod:
#
#     workflow configure edit --stdin < config.yaml
#     workflow submit
#
# It then polls the CRs the workflow reconciles (kafkaclusters, capturedtraffics, captureproxies,
# trafficreplays) until each reports status.phase=Ready, and reports the proxy endpoint that the
# CaptureProxy CR actually published rather than a hardcoded URL.
#
# The topology lives entirely in the config, so ANY CDC setup is expressible without touching this
# script: more proxies, more replayers, an S3 traffic source, an external Kafka, cert-manager TLS.
# Edit the config, or pass your own with -f.
#
# Requires the control plane (Argo, Strimzi, cert-manager, migration console) and the source/target
# clusters to be up already — i.e. run kindTesting.sh first.
#
# Usage:
#   ./deployCdcWorkflow.sh up                  # configure + submit + wait + print the k6 command
#   ./deployCdcWorkflow.sh up -f my-cdc.yaml   # ... from your own migration config
#   ./deployCdcWorkflow.sh render              # dry run: print the config `up` would submit
#   ./deployCdcWorkflow.sh status              # resource tree + proxy endpoint
#   ./deployCdcWorkflow.sh down                # delete the migration resources + the k6 chart
#
# Env overrides:
#   CONTEXT=<kube-context>  NAMESPACE=ma  CONFIG_FILE=<path>
#   PROXY_SERVICE_TYPE=ClusterIP        (LoadBalancer on a cloud cluster)
#   CLUSTER_USERNAME=admin  CLUSTER_PASSWORD=admin   (used only if a cluster asks for credentials)
#   READY_TIMEOUT=1800  POLL_INTERVAL=10
set -euo pipefail

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
CONSOLE_POD="${CONSOLE_POD:-migration-console-0}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="${CONFIG_FILE:-${SCRIPT_DIR}/configs/cdcLoadTest.yaml}"

# The workflow that `workflow submit` creates. Matches DEFAULT_WORKFLOW_NAME in the console CLI.
WORKFLOW_NAME="${WORKFLOW_NAME:-migration-workflow}"

PROXY_SERVICE_TYPE="${PROXY_SERVICE_TYPE:-ClusterIP}"
SOURCE_SECRET="${SOURCE_SECRET:-source-creds}"
TARGET_SECRET="${TARGET_SECRET:-target-creds}"
CLUSTER_USERNAME="${CLUSTER_USERNAME:-admin}"
CLUSTER_PASSWORD="${CLUSTER_PASSWORD:-admin}"

READY_TIMEOUT="${READY_TIMEOUT:-1800}"
POLL_INTERVAL="${POLL_INTERVAL:-10}"

MIGRATION_KINDS="kafkaclusters,capturedtraffics,captureproxies,trafficreplays"

# k6 load-test chart (operator + example TestRuns + RBAC). Installed here — NOT by the migration
# deploy.
K6_CHART="${SCRIPT_DIR}/charts/components/k6LoadTest"
K6_RELEASE="k6-load-test"
# Repository only — the k6 version is pinned once in the chart's values.yaml, so overriding the
# registry here (a Docker Hub mirror) leaves that pin in force. Set K6_IMAGE with an explicit
# `:tag` to override the version too.
K6_IMAGE="${K6_IMAGE:-mirror.gcr.io/grafana/k6}"
# The scenarios and presets ride in migrations/k6_scripts (built from TrafficCapture/trafficLoadTest
# by buildImages) and are mounted at /scripts. Being a migrations/* image it lives in the same
# registry as the migration's own images, so the default is derived from captureProxyImage in
# install_k6_chart rather than hardcoded. Set K6_SCRIPTS_IMAGE to point somewhere else.
K6_SCRIPTS_IMAGE="${K6_SCRIPTS_IMAGE:-}"

# Rendered config path. Global rather than function-local so the EXIT trap can still see it after
# the creating function has returned (a local would be out of scope, and `set -u` turns that into
# an error on the way out).
RENDERED_CONFIG=""
cleanup() { [[ -n "$RENDERED_CONFIG" ]] && rm -f "$RENDERED_CONFIG"; return 0; }
trap cleanup EXIT

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
  out="$(K exec "$CONSOLE_POD" -- curl -sk -o /dev/null -w '%{http_code}' --max-time 10 "$@" 2>/dev/null)" || true
  echo "${out:-000}"
}

# ── Preflight ──────────────────────────────────────────────────────────────────
preflight() {
  say "Preflight (context=$CONTEXT namespace=$NAMESPACE)"
  kubectl --context "$CONTEXT" get ns "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace $NAMESPACE not found"
  K get cm migration-image-config >/dev/null 2>&1 \
    || die "migration-image-config missing (control plane not up? run kindTesting.sh)"
  K wait --for=condition=ready "pod/$CONSOLE_POD" --timeout=300s >/dev/null 2>&1 \
    || die "$CONSOLE_POD not ready (control plane not up? run kindTesting.sh)"
  # The workflow creates a Strimzi Kafka; without the operator the KafkaCluster CR never goes Ready.
  K get deploy strimzi-cluster-operator >/dev/null 2>&1 \
    || die "strimzi-cluster-operator not found — the auto-created Kafka cluster cannot reconcile"
  kubectl --context "$CONTEXT" get crd captureproxies.migrations.opensearch.org >/dev/null 2>&1 \
    || die "migration CRDs missing (migration assistant chart not installed?)"
  ok "control plane present"
}

# ── Config rendering ───────────────────────────────────────────────────────────
# Substitute ONLY the documented placeholders, so a fully-literal config passed with -f survives
# untouched (a bare `envsubst` would eat any other $NAME in the file).
render_config() {
  local out="$1"
  export SOURCE_AUTH_BLOCK TARGET_AUTH_BLOCK PROXY_SERVICE_TYPE
  envsubst '${SOURCE_AUTH_BLOCK} ${TARGET_AUTH_BLOCK} ${PROXY_SERVICE_TYPE}' < "$CONFIG_FILE" > "$out"
  python3 -c 'import sys, yaml; yaml.safe_load(open(sys.argv[1]))' "$out" \
    || die "rendered config is not valid YAML: $out"
}

# Read a value out of a rendered config with python, since the shell has no YAML parser.
config_query() {
  python3 -c '
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1])) or {}

def first_endpoint(section):
    for cluster in (doc.get(section) or {}).values():
        ep = (cluster or {}).get("endpoint")
        if ep:
            return ep
    return ""

if sys.argv[2] == "kinds":
    # Which CR kinds this config should produce. Kinds, NOT names: only KafkaCluster and
    # CaptureProxy are named after their config key — the config processor composes the others
    # ("<proxy>-topic", "<proxy>-<target>-<replayer>"), and reproducing those rules here would rot
    # the moment they change. Names are discovered from the cluster instead.
    traffic = doc.get("traffic") or {}
    proxies, s3 = traffic.get("proxies") or {}, traffic.get("s3Sources") or {}
    if doc.get("kafkaClusterConfiguration"): print("KafkaCluster")
    if proxies or s3:                        print("CapturedTraffic")
    if proxies:                              print("CaptureProxy")
    if traffic.get("replayers"):             print("TrafficReplay")
else:
    print(first_endpoint(sys.argv[2]))
' "$1" "$2"
}

# ── Auth discovery ─────────────────────────────────────────────────────────────
# Whether a cluster wants credentials is the one thing that genuinely varies with how the test
# clusters were deployed (values.yaml vs valuesNoAuth.yaml), so it is probed rather than configured.
# Everything else about the clusters is literal in the config file.
needs_auth() {
  local endpoint="$1" code
  code="$(probe_code "$endpoint/")"
  [[ "$code" == 200 ]] && { echo false; return 0; }
  if [[ "$code" == 401 || "$code" == 403 ]]; then
    code="$(probe_code -u "$CLUSTER_USERNAME:$CLUSTER_PASSWORD" "$endpoint/")"
    [[ "$code" == 200 ]] && { echo true; return 0; }
    die "$endpoint answered $code and rejected the '$CLUSTER_USERNAME' credentials — set CLUSTER_USERNAME/CLUSTER_PASSWORD"
  fi
  die "could not reach $endpoint (HTTP $code) — is it deployed, and does the config point at it?"
}

# Emit the `authConfig:` stanza for a cluster, indented to sit under the cluster key, or nothing at
# all when the cluster takes no auth. See the config template for why absence matters.
auth_block() {
  [[ "$1" == true ]] || return 0
  printf '    authConfig:\n      basic:\n        secretName: %s' "$2"
}

# Idempotent: `up` is meant to be re-runnable, and `credentials create` refuses to clobber an
# existing secret. On a later run we rotate it instead, so a changed CLUSTER_PASSWORD takes effect
# rather than silently reusing a stale one.
ensure_credentials() {
  local secret="$1" out
  if out="$(printf '%s:%s' "$CLUSTER_USERNAME" "$CLUSTER_PASSWORD" \
            | console "workflow configure credentials create --stdin --silent '$secret'" 2>&1)"; then
    ok "created credentials $secret"; return 0
  fi
  if grep -qi "already exist" <<<"$out"; then
    if out="$(printf '%s:%s' "$CLUSTER_USERNAME" "$CLUSTER_PASSWORD" \
              | console "workflow configure credentials update --stdin --silent '$secret'" 2>&1)"; then
      ok "updated credentials $secret"; return 0
    fi
  fi
  sed 's/^/  /' <<<"$out"
  die "could not create or update credentials '$secret'"
}

# Probe the clusters the config names and decide the two auth stanzas. Rendering happens twice: once
# with the stanzas empty purely so the file parses and the endpoints can be read out of it, then
# again for real once the answer is known.
discover_auth() {
  say "Probe the configured clusters for authentication"
  local probe source_ep target_ep source_auth target_auth
  probe="$(mktemp -t cdc-probe-XXXXXX.yaml)"
  SOURCE_AUTH_BLOCK="" TARGET_AUTH_BLOCK="" render_config "$probe"
  source_ep="$(config_query "$probe" sourceClusters)"
  target_ep="$(config_query "$probe" targetClusters)"
  rm -f "$probe"
  [[ -n "$source_ep" ]] || die "no sourceClusters endpoint in $CONFIG_FILE"
  [[ -n "$target_ep" ]] || die "no targetClusters endpoint in $CONFIG_FILE"

  source_auth="$(needs_auth "$source_ep")"
  target_auth="$(needs_auth "$target_ep")"
  ok "source $source_ep (auth=$source_auth)"
  ok "target $target_ep (auth=$target_auth)"

  [[ "$source_auth" == true ]] && ensure_credentials "$SOURCE_SECRET"
  [[ "$target_auth" == true ]] && ensure_credentials "$TARGET_SECRET"
  SOURCE_AUTH_BLOCK="$(auth_block "$source_auth" "$SOURCE_SECRET")"
  TARGET_AUTH_BLOCK="$(auth_block "$target_auth" "$TARGET_SECRET")"

  # The proxy forwards to the source without adding credentials, so a load test has to send whatever
  # the source wants. Carry that into the k6 command printed at the end.
  K6_AUTH_ARGS=""
  [[ "$source_auth" == true ]] \
    && K6_AUTH_ARGS=" -e AUTH_USERNAME=$CLUSTER_USERNAME -e AUTH_PASSWORD=$CLUSTER_PASSWORD"
  return 0
}

# ── Readiness ──────────────────────────────────────────────────────────────────
workflow_phase() {
  K get workflow.argoproj.io "$WORKFLOW_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true
}

# One line of "<Kind> <name> <phase>" per migration CR in the namespace.
cr_inventory() {
  K get $MIGRATION_KINDS \
    -o jsonpath='{range .items[*]}{.kind}{" "}{.metadata.name}{" "}{.status.phase}{"\n"}{end}' 2>/dev/null || true
}

dump_cr_diagnostics() {
  local kind name
  while read -r kind name phase; do
    [[ "$phase" == Error ]] || continue
    warn "$kind/$name:"
    K get "${kind,,}.migrations.opensearch.org" "$name" \
      -o jsonpath='{.status.message}' 2>/dev/null | sed 's/^/    /' || true
    K describe "${kind,,}.migrations.opensearch.org" "$name" 2>&1 | tail -20 | sed 's/^/    /' || true
  done <<<"$1"
}

# Ready when every CR present reports Ready and each expected kind has appeared. Fails fast on a
# CR in Error, and on a workflow that has finished without getting everything there — the workflow
# patches each CR Ready as its final step for that resource, so once it is done nothing more is
# coming and waiting out the timeout would tell us nothing.
wait_for_resources() {
  local config="$1" deadline=$(( SECONDS + READY_TIMEOUT )) inventory wf kind last=""
  say "Wait for migration resources to become Ready (timeout=${READY_TIMEOUT}s)"

  local -a expected=()
  while read -r kind; do [[ -n "$kind" ]] && expected+=("$kind"); done < <(config_query "$config" kinds)

  while (( SECONDS < deadline )); do
    inventory="$(cr_inventory)"
    wf="$(workflow_phase)"

    if grep -q ' Error$' <<<"$inventory"; then
      dump_cr_diagnostics "$inventory"
      die "a migration resource entered Error phase"
    fi

    local pending="" summary=""
    for kind in "${expected[@]}"; do
      local ready total
      total="$(grep -c "^$kind " <<<"$inventory" || true)"
      ready="$(grep -c "^$kind .* Ready$" <<<"$inventory" || true)"
      summary+="${kind}=${ready}/${total:-0} "
      (( total > 0 && ready == total )) || pending="yes"
    done

    if [[ -z "$pending" ]]; then
      say "All migration resources Ready"
      sed 's/^/  /' <<<"$inventory"
      return 0
    fi
    if [[ "$wf" == Failed || "$wf" == Error || "$wf" == Succeeded ]]; then
      warn "workflow/$WORKFLOW_NAME finished as $wf with resources still not Ready:"
      sed 's/^/    /' <<<"$inventory"
      die "migration resources did not all reach Ready — inspect with: workflow status"
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
  # Split off a tag only when the colon comes after the last slash — otherwise a registry port
  # (host:5001/grafana/k6) would be mistaken for one. No tag means the chart's pinned version wins.
  local repo="$K6_IMAGE" tag=""
  if [[ "${K6_IMAGE##*/}" == *:* ]]; then
    repo="${K6_IMAGE%:*}"; tag="${K6_IMAGE##*:}"
  fi
  local s_repo="${K6_SCRIPTS_IMAGE%:*}" s_tag="${K6_SCRIPTS_IMAGE##*:}"
  # Always re-pull the scripts image: it is rebuilt in place under a moving tag while iterating on
  # scenarios, so IfNotPresent would pin runner pods to whatever the node cached first.
  helm --kube-context "$CONTEXT" upgrade --install "$K6_RELEASE" "$K6_CHART" -n "$NAMESPACE" \
    --set image.repository="$repo" ${tag:+--set image.tag="$tag"} --set image.pullPolicy=IfNotPresent \
    --set scriptsImage.repository="$s_repo" --set scriptsImage.tag="$s_tag" \
    --set scriptsImage.pullPolicy=Always \
    --timeout 300s 2>&1 | sed 's/^/  /'
  K rollout status deploy -l app.kubernetes.io/name=k6-operator --timeout=180s 2>&1 | tail -1 | sed 's/^/  /' || true
  ok "k6 operator + example TestRuns installed (runner: $K6_IMAGE, scripts: $K6_SCRIPTS_IMAGE)"
}

cmd_up() {
  preflight
  discover_auth

  RENDERED_CONFIG="$(mktemp -t cdc-config-XXXXXX.yaml)"
  say "Render migration config from $(basename "$CONFIG_FILE")"
  render_config "$RENDERED_CONFIG"
  sed 's/^/  /' "$RENDERED_CONFIG"

  say "Load configuration into the migration console"
  console 'workflow configure edit --stdin' < "$RENDERED_CONFIG" 2>&1 | sed 's/^/  /'
  say "Submit the migration workflow"
  console 'workflow submit' 2>&1 | sed 's/^/  /'

  wait_for_resources "$RENDERED_CONFIG"
  install_k6_chart

  # Read the proxy name back from the cluster rather than the config: the CaptureProxy CR is the
  # thing that actually published an endpoint.
  local proxy endpoint
  proxy="$(K get captureproxies.migrations.opensearch.org -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  endpoint="$(proxy_endpoint "$proxy")"

  cmd_status
  say "Ready — run a load test"
  cat <<EOF
  Capture proxy is up at:  ${endpoint}

  Run k6 against it (from the migration console pod, or anywhere with cluster context):
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- workflow loadtest run \\
      --scenario ingest --config ingest-steady --target ${endpoint}${K6_AUTH_ARGS}
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- workflow loadtest list
    kubectl -n ${NAMESPACE} exec -it ${CONSOLE_POD} -- workflow loadtest   # TUI

  Traffic: k6 → ${proxy} → source, and ${proxy} → Kafka → replayer → target
  Tear down with:  $0 down
EOF
}

# Dry run: probe, render, validate and print the config that `up` would submit. Creates nothing in
# the cluster except the credentials the probe decides are needed.
cmd_render() {
  preflight
  discover_auth
  RENDERED_CONFIG="$(mktemp -t cdc-config-XXXXXX.yaml)"
  say "Rendered migration config (from $(basename "$CONFIG_FILE"))"
  render_config "$RENDERED_CONFIG"
  cat "$RENDERED_CONFIG"
  say "CR kinds this config would create"
  config_query "$RENDERED_CONFIG" kinds | sed 's/^/  /'
}

cmd_status() {
  say "Migration resources (namespace=$NAMESPACE)"
  # The console already renders these grouped by role with their spec fields; no point rebuilding
  # that here. It needs a workflow to exist, so fall back to a bare listing when there is none.
  if [[ -n "$(cr_inventory)" ]]; then
    console 'workflow status --resource-view' 2>&1 | sed 's/^/  /' || cr_inventory | sed 's/^/  /'
  else
    warn "no migration resources — nothing deployed (bring one up with: $0 up)"
  fi

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
  -h|--help) sed -n '2,39p' "$0" ;;
  *) die "unknown command '${COMMAND}' (use up | render | status | down)" ;;
esac
