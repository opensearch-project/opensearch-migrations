#!/usr/bin/env bash
# Developer check for general-work-pool overrides: runs helm lint with a --helm-values file and
# prints the NodePool it would render. Needs only helm; no AWS access or cluster. aws-bootstrap.sh
# validates the same NodePool against the cluster's CRD once the cluster exists.
#
# Usage:
#   ./deployment/k8s/aws/examples/check-nodepool-overrides.sh <values.yaml> [<values.yaml> ...]
#
# Example:
#   ./deployment/k8s/aws/examples/check-nodepool-overrides.sh deployment/k8s/aws/examples/nodepool-overrides.yaml

set -euo pipefail

if [[ $# -eq 0 ]]; then
  sed -n '6,7p' "$0" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
chart_dir="$(cd "${script_dir}/../../charts/aggregates/migrationAssistantWithArgo" && pwd)"

values_flags=(-f "${chart_dir}/values.yaml" -f "${chart_dir}/valuesEks.yaml")
for f in "$@"; do
  values_flags+=(-f "$f")
done
# Placeholders for values the bootstrap normally supplies; they do not affect the NodePool.
set_flags=(--set stageName=dev --set aws.region=us-east-2 --set aws.account=123456789012)

echo "=== helm lint ==="
helm lint "$chart_dir" --kube-version 1.35.0 "${values_flags[@]}" "${set_flags[@]}"

echo
echo "=== Rendered general-work-pool NodePool ==="
helm template ma "$chart_dir" "${values_flags[@]}" "${set_flags[@]}" \
  --show-only templates/resources/aws/workloadsNodePool.yaml
