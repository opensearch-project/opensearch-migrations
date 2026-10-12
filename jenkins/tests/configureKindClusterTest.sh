#!/usr/bin/env bash

set -euo pipefail

readonly repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly script_under_test="${repo_root}/jenkins/configureKindCluster.sh"
readonly kind_config="${repo_root}/deployment/k8s/kindClusterConfigSingleNode.yaml"
readonly test_dir="$(mktemp -d)"
readonly docker_log="${test_dir}/docker.log"
readonly hosts_log="${test_dir}/hosts.toml"

cleanup() {
    rm -rf "${test_dir}"
}
trap cleanup EXIT

kind() {
    [[ "$*" == "get nodes --name ma" ]]
    printf '%s\n' ma-control-plane ma-worker
}

aws() {
    [[ "$*" == "ecr get-login-password --region us-east-1" ]]
    printf '%s' temporary-password
}

docker() {
    printf '%s\n' "$*" >> "${docker_log}"

    if [[ "$*" == *"grep -Fq config_path"* ]]; then
        return 0
    fi

    if [[ "$*" == *"sh -c umask 077"* ]]; then
        cat >> "${hosts_log}"
    fi
}

export -f kind aws docker
export docker_log hosts_log

grep -Fq '[plugins."io.containerd.grpc.v1.cri".registry]' "${kind_config}"
grep -Fq 'config_path = "/etc/containerd/certs.d"' "${kind_config}"

skip_output="$(ECR_PULL_THROUGH_ENDPOINT= bash "${script_under_test}")"
grep -Fq "skipping kind pull-through cache configuration" <<< "${skip_output}"

if ECR_PULL_THROUGH_ENDPOINT=not-an-ecr-endpoint bash "${script_under_test}" >/dev/null 2>&1; then
    echo "Expected an invalid ECR endpoint to fail" >&2
    exit 1
fi

ECR_PULL_THROUGH_ENDPOINT=123456789012.dkr.ecr.us-east-1.amazonaws.com \
    KIND_BIN=kind \
    KIND_CLUSTER_NAME=ma \
    bash "${script_under_test}"

for expected in \
    'https://123456789012.dkr.ecr.us-east-1.amazonaws.com/v2/docker-hub' \
    'https://123456789012.dkr.ecr.us-east-1.amazonaws.com/v2/quay' \
    'https://123456789012.dkr.ecr.us-east-1.amazonaws.com/v2/ecr-public' \
    'https://123456789012.dkr.ecr.us-east-1.amazonaws.com/v2/k8s' \
    'authorization = "Basic QVdTOnRlbXBvcmFyeS1wYXNzd29yZA=="'
do
    grep -Fq "${expected}" "${hosts_log}"
done

[[ "$(grep -Fc 'server = ' "${hosts_log}")" -eq 12 ]]
[[ "$(grep -Fc 'override_path = true' "${hosts_log}")" -eq 12 ]]
if grep -Fq 'temporary-password' "${hosts_log}"; then
    echo "Raw ECR password was written to containerd configuration" >&2
    exit 1
fi

grep -Fq 'exec ma-control-plane grep -Fq config_path' "${docker_log}"
grep -Fq 'exec ma-worker grep -Fq config_path' "${docker_log}"
