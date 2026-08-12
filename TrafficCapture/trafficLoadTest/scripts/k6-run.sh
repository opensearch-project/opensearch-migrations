#!/usr/bin/env bash
# Submit a k6 load-test run as a TestRun — with NO migration-console dependency.
#
# Reads the chart-rendered example for the scenario (ConfigMap k6-testrun-examples), applies
# overrides, and `kubectl create`s it. The example already carries the runner image, the scripts
# image mounted at /scripts, K6_OUT metrics, and a default preset; this only patches per-run bits.
#
# Usage:
#   ./k6-run.sh <ingest|search|mixed> [--config NAME] [--parallelism N] [--target URL]
#               [--extra-args STR] [-e KEY=VALUE]...
#   CONTEXT=<ctx> NAMESPACE=ma ./k6-run.sh ingest --config ingest-burst -e SEED_DOC_COUNT=0
#
# --config NAME selects a k6-config/*.env preset baked into the runner image; it is passed to the
# scenario as K6_PRESET, and any -e KEY=VALUE wins over the values in that preset.
#
# Prints the generated run name on success.

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

example=$(K get cm k6-testrun-examples -o "jsonpath={.data.$SCENARIO}" 2>/dev/null || true)
[[ -n "$example" ]] || { echo "no example for '$SCENARIO' — is the k6LoadTest chart installed in ns $NAMESPACE?" >&2; exit 1; }

# Build the env-override array (--target and each -e KEY=VALUE; these win over the preset's values).
env_json="[]"
add_env() { env_json=$(jq --arg n "$1" --arg v "$2" '. + [{name:$n,value:$v}]' <<<"$env_json"); }
[[ -n "$TARGET" ]] && add_env CAPTURE_PROXY_URL "$TARGET"
for kv in "${ENVS[@]:-}"; do
  [[ -z "$kv" ]] && continue
  [[ "$kv" == *=* ]] || { echo "-e needs KEY=VALUE, got '$kv'" >&2; exit 2; }
  add_env "${kv%%=*}" "${kv#*=}"
done

# Env vars are set by name rather than appended: the example already carries K6_PRESET (and the
# K6_OUT trio), and a spec with two entries of the same name would leave the winner up to the
# container runtime.
echo "$example" \
  | jq --argjson envadd "$env_json" --arg config "$CONFIG" --arg par "$PARALLELISM" --arg extra "$EXTRA" '
      def setenv($n; $v):
        .spec.runner.env = ((.spec.runner.env // []) | map(select(.name != $n)) + [{name: $n, value: $v}]);
        (if $config != "" then setenv("K6_PRESET"; $config) else . end)
      | reduce $envadd[] as $e (.; setenv($e.name; $e.value))
      | (if $par    != "" then .spec.parallelism = ($par|tonumber) else . end)
      | (if $extra  != "" then .spec.arguments = $extra else . end)' \
  | K create -f - -o jsonpath='{.metadata.name}'
echo
