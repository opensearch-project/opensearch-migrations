#!/usr/bin/env bash
#
# deployCdcLoadTestConfig.sh — submit a CDC (capture-and-replay) load-test migration config to the
# migration console, wait for the resources it creates, and install the k6 load-test chart.
#
#     k6 / client → capture-proxy → source cluster                     (capture path)
#                   capture-proxy → Kafka → replayer → target cluster   (replay path)
#
# What it submits is a migration CONFIG, not a workflow. The console's config processor generates
# the Argo workflow from it. These are the same two commands you would run by hand in the console:
#
#     workflow configure edit --stdin < config.yaml
#     workflow submit
#
# The topology lives entirely in the config, so any CDC setup is expressible without touching this
# script — more proxies, more replayers, an S3 traffic source, an external Kafka. Edit
# configs/cdcLoadTest.yaml, or pass your own with -f. Resource names are read back from the cluster,
# never predicted here.
#
# Requires the control plane (Argo, Strimzi, cert-manager, migration console) and the source/target
# clusters to be up already — run kindTesting.sh first.
#
# Usage:
#   ./deployCdcLoadTestConfig.sh up                  # submit + wait + install k6 + print how to run it
#   ./deployCdcLoadTestConfig.sh up --no-auth        # ... against clusters deployed without auth
#   ./deployCdcLoadTestConfig.sh up --dry-run        # print the config that would be submitted
#   ./deployCdcLoadTestConfig.sh up -f my-cdc.yaml   # ... from your own migration config
#   ./deployCdcLoadTestConfig.sh status              # resource tree + proxy endpoint
#   ./deployCdcLoadTestConfig.sh down                # delete the migration resources + the k6 chart
#
# Env overrides:
#   CONTEXT=<kube-context>  NAMESPACE=ma  CONFIG_FILE=<path>
#   CLUSTER_USERNAME=admin  CLUSTER_PASSWORD=admin   (for the secrets the config references)
#   READY_TIMEOUT=1800  POLL_INTERVAL=10  QUERY_RETRIES=3
#   K6_IMAGE / K6_RELEASE                            (passed through to installK6Chart.sh, which
#                                                     owns both the install and the uninstall)
#
# non-zero exit codes:
#   1 = cluster access, config, or preflight
#   2 = resources did not become Ready
#   3 = teardown did not complete
set -euo pipefail

CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"
CONSOLE_POD="${CONSOLE_POD:-migration-console-0}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="${CONFIG_FILE:-${SCRIPT_DIR}/configs/cdcLoadTest.yaml}"

# The workflow that `workflow submit` creates. Matches DEFAULT_WORKFLOW_NAME in the console CLI.
WORKFLOW_NAME="${WORKFLOW_NAME:-migration-workflow}"

CLUSTER_USERNAME="${CLUSTER_USERNAME:-admin}"
CLUSTER_PASSWORD="${CLUSTER_PASSWORD:-admin}"

READY_TIMEOUT="${READY_TIMEOUT:-1800}"
POLL_INTERVAL="${POLL_INTERVAL:-10}"
# A busy API server can refuse one query. Only a repeated failure is a real fault.
QUERY_RETRIES="${QUERY_RETRIES:-3}"

MIGRATION_KINDS="kafkaclusters,capturedtraffics,captureproxies,trafficreplays"
CRD_GROUP="migrations.opensearch.org"

# Exit codes, so an unattended caller can tell the three failures apart.
EXIT_ENV=1        # cluster access, config, or preflight
EXIT_NOT_READY=2  # resources did not reach Ready, or published no endpoint
EXIT_TEARDOWN=3   # down left resources behind

NO_AUTH=false
DRY_RUN=false
SECRETS=()

K() { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }
say()  { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
ok()   { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[1;33m!\033[0m %s\n' "$*"; }
die()  { die_code "$EXIT_ENV" "$*"; }
die_code() { local c="$1"; shift; printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit "$c"; }

# Holds the stderr of the last cluster query. See cr_inventory for why a file and not a variable.
# Set after the helpers above, because it reports a failure through die.
#
# One fresh file per run, from mktemp: a leftover from an earlier run has a different name and is
# never read, and two runs at the same time cannot collide. Every `2>` redirect to it truncates as
# it opens, so no stale text can survive into a later query either.
ERR_FILE="$(mktemp -t deployCdcLoadTestConfig.XXXXXX)" \
  || die "cannot create a temporary file (is TMPDIR writable?)"
trap 'rm -f "$ERR_FILE"' EXIT

# Run a command inside the migration console.
#
# Two forms, because `kubectl exec -i` takes over the caller's stdin. Only the two calls that pipe a
# config in need that. The rest read `/dev/null`, so this script cannot block on input or consume
# the stdin of a parent script that runs it inside a `while read` loop.
console_in() { K exec -i "$CONSOLE_POD" -- /bin/bash -lc "$*"; }
console()    { K exec    "$CONSOLE_POD" -- /bin/bash -lc "$*" </dev/null; }

# ── Preflight ──────────────────────────────────────────────────────────────────
preflight() {
  say "Preflight (context=$CONTEXT namespace=$NAMESPACE)"
  kubectl --context "$CONTEXT" get ns "$NAMESPACE" >/dev/null 2>&1 \
    || die "namespace $NAMESPACE not found"
  K get cm migration-image-config >/dev/null 2>&1 \
    || die "migration-image-config missing (control plane not up? run kindTesting.sh)"
  K wait --for=condition=ready "pod/$CONSOLE_POD" --timeout=300s >/dev/null 2>&1 \
    || die "$CONSOLE_POD not ready (control plane not up? run kindTesting.sh)"
  # The config creates a Strimzi Kafka; without the operator the KafkaCluster CR never goes Ready.
  K get deploy strimzi-cluster-operator >/dev/null 2>&1 \
    || die "strimzi-cluster-operator not found — the auto-created Kafka cluster cannot reconcile"
  # Every kind this script lists, not just one of them. A partial CRD set would otherwise make each
  # inventory query fail, which reads the same as "nothing deployed yet".
  local kind
  for kind in ${MIGRATION_KINDS//,/ }; do
    kubectl --context "$CONTEXT" get crd "${kind}.${CRD_GROUP}" >/dev/null 2>&1 \
      || die "CRD ${kind}.${CRD_GROUP} missing (migration assistant chart not installed?)"
  done
  ok "control plane present"
}

# ── Config ─────────────────────────────────────────────────────────────────────
# The config is literal YAML and parses as it stands. --no-auth drops the fenced authConfig
# stanzas, because "no auth" has to mean the key is ABSENT — it is an optional union in the schema,
# so an empty `authConfig: {}` fails validation.
render_config() {
  if [[ "$NO_AUTH" == true ]]; then
    sed '/# auth-begin/,/# auth-end/d' "$CONFIG_FILE"
  else
    cat "$CONFIG_FILE"
  fi
}

# The credential secrets the config asks for. Read out of the config rather than assumed, so a
# config passed with -f gets its own secrets created.
config_secret_names() {
  render_config | grep -oE 'secretName: *[A-Za-z0-9._-]+' | awk '{print $2}' | sort -u
}

# Idempotent: `up` is meant to be re-runnable, and `credentials create` refuses to clobber an
# existing secret. On a later run we update instead, so a changed CLUSTER_PASSWORD takes effect
# rather than silently reusing a stale one.
ensure_credentials() {
  local secret="$1" verb out
  for verb in create update; do
    if out="$(printf '%s:%s' "$CLUSTER_USERNAME" "$CLUSTER_PASSWORD" \
              | console_in "workflow configure credentials $verb --stdin --silent '$secret'" 2>&1)"; then
      ok "credentials $secret ($verb)"; return 0
    fi
  done
  sed 's/^/  /' <<<"$out"
  die "could not create or update credentials '$secret'"
}

# ── Readiness ──────────────────────────────────────────────────────────────────
# No workflow yet is the normal state before `submit`, so an empty phase is not an error. Errors go
# to /dev/null to keep them out of the phase string.
workflow_phase() {
  K get workflow.argoproj.io "$WORKFLOW_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || true
}

# One line of "<Kind> <name> <phase>" per migration CR in the namespace.
#
# Returns non-zero when the QUERY failed. Callers must tell that apart from a query that worked and
# found nothing: a missing CRD, a wrong namespace, or a stale context all give empty output too, and
# reading those as "nothing deployed" hides a real fault behind a 30-minute timeout.
#
# stderr goes to a file, not into the output. Callers read this through `$( )`, which is a subshell,
# so a variable set here would not survive; and kubectl warnings merged into stdout would corrupt
# the rows the phase check parses.
cr_inventory() {
  K get $MIGRATION_KINDS \
    -o jsonpath='{range .items[*]}{.kind}{" "}{.metadata.name}{" "}{.status.phase}{"\n"}{end}' 2>"$ERR_FILE"
}

# The stderr of the last query, indented, plus what to run about it. Printed, never asked.
#
# Call this only in the failure branch of a query that redirects to ERR_FILE. The redirect truncates
# the file as it opens it, so what this prints always belongs to the query that just failed.
show_query_error() {
  if [[ -s "$ERR_FILE" ]]; then
    # kubectl repeats the same error once per requested kind. Keep the first of each, in order.
    awk '!seen[$0]++' "$ERR_FILE" | sed 's/^/    /'
  else
    printf '    (the command failed but printed no error)\n'
  fi
  warn "check the CRDs:      kubectl --context $CONTEXT get crd | grep $CRD_GROUP"
  warn "check the resources: kubectl --context $CONTEXT -n $NAMESPACE get $MIGRATION_KINDS"
}

# Ready when at least one CR exists and every one of them reports Ready. Fails fast on a CR in
# Error, and on a workflow that has finished without getting everything there — the workflow patches
# each CR Ready as its final step for that resource, so once it is done nothing more is coming and
# waiting out the timeout would tell us nothing.
wait_for_resources() {
  local deadline=$(( SECONDS + READY_TIMEOUT )) inventory wf ready total last="" fails=0
  say "Wait for migration resources to become Ready (timeout=${READY_TIMEOUT}s)"

  while (( SECONDS < deadline )); do
    if ! inventory="$(cr_inventory)"; then
      if (( ++fails >= QUERY_RETRIES )); then
        warn "cannot list migration resources after $fails tries:"
        show_query_error
        die "resource query failed — cannot tell whether the migration is Ready"
      fi
      warn "resource query failed (${fails}/${QUERY_RETRIES}), retry in ${POLL_INTERVAL}s"
      sleep "$POLL_INTERVAL"
      continue
    fi
    fails=0
    wf="$(workflow_phase)"

    if grep -q ' Error$' <<<"$inventory"; then
      warn "a migration resource entered Error phase:"
      sed 's/^/    /' <<<"$inventory"
      warn "inspect with: kubectl -n $NAMESPACE exec $CONSOLE_POD -- workflow status --resource-view"
      die_code "$EXIT_NOT_READY" "a migration resource failed"
    fi
    if [[ -n "$inventory" ]] && ! grep -qv ' Ready$' <<<"$inventory"; then
      say "All migration resources Ready"
      sed 's/^/  /' <<<"$inventory"
      return 0
    fi
    if [[ "$wf" == Failed || "$wf" == Error || "$wf" == Succeeded ]]; then
      warn "workflow/$WORKFLOW_NAME finished as $wf with resources still not Ready:"
      sed 's/^/    /' <<<"$inventory"
      warn "inspect with: kubectl -n $NAMESPACE exec $CONSOLE_POD -- workflow status --resource-view"
      die_code "$EXIT_NOT_READY" "migration resources did not all reach Ready"
    fi

    total="$(grep -c . <<<"$inventory" || true)"
    ready="$(grep -c ' Ready$' <<<"$inventory" || true)"
    if [[ "$ready/$total" != "$last" ]]; then
      printf '  … %s/%s Ready (workflow=%s)\n' "$ready" "$total" "${wf:-<none>}"
      last="$ready/$total"
    fi
    sleep "$POLL_INTERVAL"
  done

  warn "state at timeout:"
  sed 's/^/    /' <<<"$inventory"
  warn "inspect with: kubectl -n $NAMESPACE exec $CONSOLE_POD -- workflow status --resource-view"
  warn "raise the limit with: READY_TIMEOUT=<seconds> $0 up"
  die_code "$EXIT_NOT_READY" "timed out after ${READY_TIMEOUT}s waiting for migration resources to become Ready"
}

# The endpoint the CaptureProxy CR published — the authoritative one, whatever the config asked for.
proxy_endpoint() {
  local name="$1" svc lb
  # An empty name makes kubectl fail with "resource name may not be empty". The caller has nothing
  # to look up, so say nothing.
  [[ -n "$name" ]] || return 0
  svc="$(K get "captureproxy.${CRD_GROUP}" "$name" -o jsonpath='{.status.serviceEndpoint}' 2>/dev/null || true)"
  lb="$(K get "captureproxy.${CRD_GROUP}" "$name" -o jsonpath='{.status.loadBalancerEndpoint}' 2>/dev/null || true)"
  [[ -n "$lb" ]] && { echo "https://$lb"; return 0; }
  [[ -n "$svc" ]] && { echo "https://$svc"; return 0; }
  # A proxy that exists but has not published an endpoint yet is not an error — callers print
  # nothing for it. Returning non-zero here would abort them under `set -e`.
  return 0
}

cmd_up() {
  preflight

  local note=""
  [[ "$NO_AUTH" == true ]] && note=" (auth stanzas stripped)"
  say "Migration config from $(basename "$CONFIG_FILE")${note}"
  render_config | sed 's/^/  /'
  if [[ "$DRY_RUN" == true ]]; then
    ok "dry run — nothing submitted"
    return 0
  fi

  while read -r secret; do [[ -n "$secret" ]] && SECRETS+=("$secret"); done < <(config_secret_names)
  if (( ${#SECRETS[@]} )); then
    say "Create the cluster credentials the config references"
    for secret in "${SECRETS[@]}"; do ensure_credentials "$secret"; done
  fi

  say "Load configuration into the migration console"
  render_config | console_in 'workflow configure edit --stdin' 2>&1 | sed 's/^/  /'
  say "Submit the migration workflow"
  console 'workflow submit' 2>&1 | sed 's/^/  /'

  wait_for_resources

  say "Install k6 load-test chart (operator + example TestRuns + RBAC)"
  "$SCRIPT_DIR/installK6Chart.sh" install --context "$CONTEXT" --namespace "$NAMESPACE"

  # Read the proxy name back from the cluster: the CaptureProxy CR is the thing that actually
  # published an endpoint. A `range` jsonpath gives an empty string for an empty list, where
  # `.items[0]` fails with "array index out of bounds".
  local proxy endpoint auth_args=""
  proxy="$(K get "captureproxies.${CRD_GROUP}" \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null | head -n1 || true)"
  endpoint="$(proxy_endpoint "$proxy")"
  # Everything reached Ready above, so a proxy with no endpoint is a real fault. Stop here rather
  # than print a `--target` with nothing after it, which reads as a working command in a log.
  if [[ -z "$proxy" || -z "$endpoint" ]]; then
    warn "no CaptureProxy endpoint published — the migration is Ready but not reachable"
    warn "inspect with: kubectl --context $CONTEXT -n $NAMESPACE get captureproxies -o yaml"
    die_code "$EXIT_NOT_READY" "cannot report a load-test target"
  fi
  # The proxy forwards to the source without adding credentials, so a load test has to send whatever
  # the source wants.
  (( ${#SECRETS[@]} )) && auth_args=" -e AUTH_USERNAME=$CLUSTER_USERNAME -e AUTH_PASSWORD=$CLUSTER_PASSWORD"

  cmd_status || warn "status view incomplete (see above) — the migration resources are Ready"
  say "Ready — run a load test"
  cat <<EOF
  Capture proxy is up at:  ${endpoint}

  Run k6 against it (from the migration console pod, or anywhere with cluster context):
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- loadtest run \\
      --scenario ingest --config ingest-steady --target ${endpoint}${auth_args}
    kubectl -n ${NAMESPACE} exec ${CONSOLE_POD} -- loadtest list
    kubectl -n ${NAMESPACE} exec -it ${CONSOLE_POD} -- loadtest   # TUI

  Traffic: k6 → ${proxy} → source, and ${proxy} → Kafka → replayer → target
  Tear down with:  $0 down
EOF
}

# Returns EXIT_ENV when the cluster cannot be queried, so `status` on its own reports the fault to
# an unattended caller. `up` calls this only to render, and carries on after a warning.
cmd_status() {
  say "Migration resources (namespace=$NAMESPACE)"
  local inventory
  if ! inventory="$(cr_inventory)"; then
    warn "cannot list migration resources:"
    show_query_error
    return "$EXIT_ENV"
  fi

  # The console already renders these grouped by role with their spec fields; no point rebuilding
  # that here. It needs a workflow to exist, so fall back to a bare listing when there is none.
  if [[ -n "$inventory" ]]; then
    if ! console 'workflow status --resource-view' 2>&1 | sed 's/^/  /'; then
      warn "console status failed (output above) — falling back to a bare listing"
      sed 's/^/  /' <<<"$inventory"
    fi
  else
    warn "no migration resources — nothing deployed (bring one up with: $0 up)"
  fi

  local names name endpoint
  if ! names="$(K get "captureproxies.${CRD_GROUP}" \
      -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>"$ERR_FILE")"; then
    warn "cannot list capture proxies:"
    sed 's/^/    /' "$ERR_FILE"
    return "$EXIT_ENV"
  fi
  while read -r name; do
    [[ -z "$name" ]] && continue
    endpoint="$(proxy_endpoint "$name")"
    [[ -n "$endpoint" ]] && printf '  proxy %s endpoint: %s\n' "$name" "$endpoint"
  done <<<"$names"
  # Explicit: the loop above ends non-zero when the last proxy has no endpoint yet, and the return
  # value of this function now carries meaning.
  return 0
}

# Every step runs, whatever the step before it did — a stuck helm release must not keep the
# migration resources alive. But a failure is recorded, because the closing message must not claim a
# teardown that did not happen.
cmd_down() {
  local failed=0

  say "Uninstall k6 load-test chart (operator + example TestRuns + RBAC)"
  # Through the same script that installed it: it owns the release name, so the two directions
  # cannot drift.
  "$SCRIPT_DIR/installK6Chart.sh" uninstall --context "$CONTEXT" --namespace "$NAMESPACE" \
    || { warn "k6 chart uninstall failed (continuing)"; failed=1; }

  say "Delete the migration resources"
  # --include-proxies because capture proxies are protected by default: they carry live client
  # traffic, so `reset` will not remove one unless asked. --delete-storage drops the Kafka PVCs so a
  # later `up` starts from an empty topic rather than replaying whatever this run captured.
  console "workflow reset --all --cascade --include-proxies --delete-storage" 2>&1 | sed 's/^/  /' \
    || { warn "workflow reset failed (continuing)"; failed=1; }

  say "Delete the migration workflow"
  # --ignore-not-found already covers the expected case, so a failure here is a real one.
  K delete workflow.argoproj.io "$WORKFLOW_NAME" --ignore-not-found 2>&1 | sed 's/^/  /' \
    || { warn "workflow delete failed (continuing)"; failed=1; }

  if (( failed )); then
    warn "teardown incomplete — resources can remain in namespace $NAMESPACE"
    warn "to see what is left:  $0 status"
    warn "to force removal:     kubectl --context $CONTEXT -n $NAMESPACE delete $MIGRATION_KINDS --all"
    die_code "$EXIT_TEARDOWN" "down did not complete"
  fi
  ok "migration resources + k6 chart torn down (control plane and clusters left intact)"
}

COMMAND="${1:-up}"
[[ $# -gt 0 ]] && shift
while [[ $# -gt 0 ]]; do
  case "$1" in
    -f|--config) CONFIG_FILE="$2"; shift 2 ;;
    --no-auth)   NO_AUTH=true; shift ;;
    --dry-run)   DRY_RUN=true; shift ;;
    *) die "unknown option '$1'" ;;
  esac
done

case "$COMMAND" in
  up)     [[ -f "$CONFIG_FILE" ]] || die "config file not found: $CONFIG_FILE"; cmd_up ;;
  status) cmd_status || exit $? ;;
  down)   cmd_down ;;
  -h|--help) awk 'NR>1 && /^#/ {print} NR>1 && !/^#/ {exit}' "$0" ;;
  *) die "unknown command '${COMMAND}' (use up | status | down)" ;;
esac
