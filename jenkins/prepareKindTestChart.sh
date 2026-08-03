#!/usr/bin/env bash

set -euo pipefail

readonly source_chart="${1:?Source chart path is required}"
readonly target_chart="${2:?Target chart path is required}"
readonly chart_file="${target_chart}/Chart.yaml"
readonly supported_requirement='kubeVersion: ">=1.35.0-0"'
readonly test_requirement='kubeVersion: ">=1.34.0-0"'

if [[ "${source_chart}" == "${target_chart}" || "${target_chart}" == "/" ]]; then
    echo "The test chart must be copied to a separate, non-root path" >&2
    exit 1
fi

rm -rf "${target_chart}"
mkdir -p "$(dirname "${target_chart}")"
cp -R "${source_chart}" "${target_chart}"

if ! grep -Fxq "${supported_requirement}" "${chart_file}"; then
    echo "Expected ${supported_requirement} in ${source_chart}/Chart.yaml" >&2
    exit 1
fi

# Jenkins agents that still use cgroup v1 cannot run Kubernetes 1.35. The local
# matrix does not exercise ImageVolume-backed transforms, so test a copied chart
# against 1.34 without changing the chart's advertised production requirement.
sed -i.bak "s/${supported_requirement}/${test_requirement}/" "${chart_file}"
rm "${chart_file}.bak"
