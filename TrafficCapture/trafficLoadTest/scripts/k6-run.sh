#!/usr/bin/env bash
# Submit a k6 load-test run — with NO migration-console dependency.
#
# A run is an Argo Workflow that creates a k6-operator TestRun and waits for it. The chart renders
# one WorkflowTemplate per scenario holding the whole run definition (images, the scripts image
# mounted at /scripts, K6_OUT metrics, a default preset), so this only names the template and the
# parameters that differ from its defaults.
#
# Usage:
#   ./k6-run.sh <ingest|search|mixed> [--config NAME] [--parallelism N] [--target URL]
#               [--extra-args STR] [-e KEY=VALUE]...
#   CONTEXT=<ctx> NAMESPACE=ma ./k6-run.sh ingest --config ingest-burst -e SEED_DOC_COUNT=0
#
# --config NAME selects a k6-config/*.env preset baked into the scripts image; it is passed to the
# scenario as K6_PRESET, and any -e KEY=VALUE wins over the values in that preset.
#
# Prints the generated run name on success. That name is also the TestRun's, so:
#   kubectl -n ma logs -l k6_cr=<name>,runner=true -c k6 -f
#   kubectl -n ma delete wf <name>          # stops the run; the TestRun goes with it

set -euo pipefail
CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"

usage() {
  echo "usage: k6-run.sh <ingest|search|mixed> [--config NAME] [--parallelism N] [--target URL] [--extra-args STR] [-e KEY=VALUE]..." >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
SCENARIO="$1"; shift
case "$SCENARIO" in ingest|search|mixed) ;; *) echo "unknown scenario: $SCENARIO" >&2; usage ;; esac

CONFIG=""; PARALLELISM=""; TARGET=""; EXTRA=""; ENVS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)      CONFIG="$2"; shift ;;
    --parallelism) PARALLELISM="$2"; shift ;;
    --target)      TARGET="$2"; shift ;;
    --extra-args)  EXTRA="$2"; shift ;;
    -e)            ENVS+=("$2"); shift ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
  shift
done

command -v jq >/dev/null || { echo "k6-run.sh needs jq" >&2; exit 3; }
K() { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }

TEMPLATE="k6-${SCENARIO}"
K get workflowtemplate "$TEMPLATE" >/dev/null 2>&1 || {
  echo "no WorkflowTemplate '$TEMPLATE' — is the k6LoadTest chart installed in ns $NAMESPACE?" >&2
  exit 1
}

# The runner env is one parameter carrying the whole list, so start from the template's default and
# replace entries by name — appending would leave two entries of the same name and make the run
# depend on how the container runtime resolves that.
runner_env=$(K get workflowtemplate "$TEMPLATE" \
  -o "jsonpath={.spec.arguments.parameters[?(@.name=='runnerEnv')].value}")

set_env() {  # name value
  runner_env=$(jq -c --arg n "$1" --arg v "$2" \
    'map(select(.name != $n)) + [{name: $n, value: $v}]' <<<"${runner_env:-[]}")
}

[[ -n "$CONFIG" ]] && set_env K6_PRESET "$CONFIG"
[[ -n "$TARGET" ]] && set_env CAPTURE_PROXY_URL "$TARGET"
for kv in "${ENVS[@]:-}"; do
  [[ -z "$kv" ]] && continue
  [[ "$kv" == *=* ]] || { echo "-e needs KEY=VALUE, got '$kv'" >&2; exit 2; }
  set_env "${kv%%=*}" "${kv#*=}"
done

{
  cat <<EOF
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: ${TEMPLATE}-
  labels:
    app: k6-load-test
    k6-scenario: ${SCENARIO}
spec:
  workflowTemplateRef:
    name: ${TEMPLATE}
  arguments:
    parameters:
      - name: runnerEnv
        value: '${runner_env}'
EOF
  # `if` rather than `&&`: under `set -o pipefail` a false test as the group's last command would
  # fail the whole pipeline even though the run was created.
  if [[ -n "$PARALLELISM" ]]; then
    printf '      - name: parallelism\n        value: "%s"\n' "$PARALLELISM"
  fi
  if [[ -n "$EXTRA" ]]; then
    printf '      - name: arguments\n        value: "%s"\n' "$EXTRA"
  fi
} | K create -f - -o jsonpath='{.metadata.name}'
echo
