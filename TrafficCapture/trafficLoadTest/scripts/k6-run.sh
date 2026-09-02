#!/usr/bin/env bash
# Submit a k6 load-test run — with NO migration-console dependency.
#
# A run is an Argo Workflow that creates a k6-operator TestRun and waits for it. The chart renders
# one WorkflowTemplate per load profile (k6-ingest-burst, k6-mixed-steady, …) holding the whole run
# definition: the images, the scripts image mounted at /scripts, and EVERY setting of that profile
# as a named parameter with a value. This only names the template and what the run changes.
#
# Usage:
#   ./k6-run.sh <ingest|search|mixed> [--config PROFILE] [--parallelism N] [--target URL]
#               [--auth-secret-name NAME] [--extra-args STR] [-e KEY=VALUE]...
#   CONTEXT=<ctx> NAMESPACE=ma ./k6-run.sh ingest --config ingest-burst -e BULK_BATCH_SIZE=50
#
# --config names the profile to run; without it the scenario's steady profile is used. -e KEY=VALUE
# overrides any setting the profile has, by name. See what a profile has with:
#   kubectl -n ma get workflowtemplate k6-ingest-burst -o yaml
#
# A KEY the profile does not have is refused rather than sent: Argo accepts a parameter no template
# uses and then ignores it, so a typo would look like it worked and change nothing.
#
# Prints the generated run name on success. That name is also the TestRun's, so:
#   kubectl -n ma logs -l k6_cr=<name>,runner=true -c k6 -f
#   kubectl -n ma delete wf <name>          # stops the run; the TestRun goes with it

set -euo pipefail
CONTEXT="${CONTEXT:-$(kubectl config current-context)}"
NAMESPACE="${NAMESPACE:-ma}"

usage() {
  echo "usage: k6-run.sh <ingest|search|mixed> [--config PROFILE] [--parallelism N] [--target URL] [--auth-secret-name NAME] [--extra-args STR] [-e KEY=VALUE]..." >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
SCENARIO="$1"; shift
case "$SCENARIO" in ingest|search|mixed) ;; *) echo "unknown scenario: $SCENARIO" >&2; usage ;; esac

CONFIG=""; PARALLELISM=""; TARGET=""; AUTH_SECRET=""; EXTRA=""; ENVS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)      CONFIG="$2"; shift ;;
    --parallelism) PARALLELISM="$2"; shift ;;
    --target)      TARGET="$2"; shift ;;
    --auth-secret-name) AUTH_SECRET="$2"; shift ;;
    --extra-args)  EXTRA="$2"; shift ;;
    -e)            ENVS+=("$2"); shift ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
  shift
done

command -v jq >/dev/null || { echo "k6-run.sh needs jq" >&2; exit 3; }
K() { kubectl --context "$CONTEXT" -n "$NAMESPACE" "$@"; }

PROFILE="${CONFIG:-${SCENARIO}-steady}"
TEMPLATE="k6-${PROFILE}"
# The template's parameter names, which are also the list of settings this profile has. Fetching
# them is what makes an unknown -e KEY an error here instead of a silent no-op in the cluster.
DECLARED=$(K get workflowtemplate "$TEMPLATE" \
  -o jsonpath='{.spec.arguments.parameters[*].name}' 2>/dev/null) || {
  echo "no WorkflowTemplate '$TEMPLATE' — is the k6LoadTest chart installed in ns $NAMESPACE?" >&2
  exit 1
}

declares() {  # name — true when the profile has this setting
  [[ " $DECLARED " == *" $1 "* ]]
}

PARAMS=()  # "name=value" pairs to send, collected in submission order
add_param() {  # name value
  declares "$1" || {
    echo "profile '$PROFILE' has no '$1' setting; it has: $(tr ' ' '\n' <<<"$DECLARED" |
      grep -E '^[A-Z][A-Z0-9_]*$' | sort | tr '\n' ' ')" >&2
    exit 2
  }
  PARAMS+=("$1=$2")
}

[[ -n "$TARGET" ]] && add_param CAPTURE_PROXY_URL "$TARGET"
for kv in "${ENVS[@]:-}"; do
  [[ -z "$kv" ]] && continue
  [[ "$kv" == *=* ]] || { echo "-e needs KEY=VALUE, got '$kv'" >&2; exit 2; }
  add_param "${kv%%=*}" "${kv#*=}"
done

# Values are emitted as JSON strings. YAML is a superset of JSON, so this quotes and escapes
# anything a setting can hold — RAMP_STAGES carries double quotes, which a plain YAML scalar here
# could not survive.
[[ -n "$PARALLELISM" ]] && PARAMS+=("parallelism=$PARALLELISM")
[[ -n "$AUTH_SECRET" ]] && add_param authSecretName "$AUTH_SECRET"
[[ -n "$EXTRA" ]] && PARAMS+=("arguments=$EXTRA")

# The parameter lines first, so a run that overrides nothing omits `arguments:` altogether rather
# than submitting an empty list.
param_lines=""
for kv in "${PARAMS[@]:-}"; do
  [[ -z "$kv" ]] && continue
  param_lines+=$(printf '      - name: %s\n        value: %s\n' \
    "${kv%%=*}" "$(jq -Rn --arg v "${kv#*=}" '$v')")
  param_lines+=$'\n'
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
    k6-profile: ${PROFILE}
spec:
  workflowTemplateRef:
    name: ${TEMPLATE}
EOF
  if [[ -n "$param_lines" ]]; then
    printf '  arguments:\n    parameters:\n%s' "$param_lines"
  fi
} | K create -f - -o jsonpath='{.metadata.name}'
echo
