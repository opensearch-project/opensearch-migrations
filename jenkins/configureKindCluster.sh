#!/usr/bin/env bash

set -euo pipefail

readonly KIND_BIN="${KIND_BIN:-kind}"
readonly KIND_CLUSTER_NAME="${KIND_CLUSTER_NAME:-ma}"
readonly ECR_PULL_THROUGH_ENDPOINT="${ECR_PULL_THROUGH_ENDPOINT:-}"
readonly AWS_REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

if [[ -z "${ECR_PULL_THROUGH_ENDPOINT}" ]]; then
    echo "ECR_PULL_THROUGH_ENDPOINT is not set; skipping kind pull-through cache configuration"
    exit 0
fi

if [[ ! "${ECR_PULL_THROUGH_ENDPOINT}" =~ ^[0-9]{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com(\.cn)?$ ]]; then
    echo "Invalid ECR pull-through cache endpoint: ${ECR_PULL_THROUGH_ENDPOINT}" >&2
    exit 1
fi

for command in aws base64 docker; do
    if ! command -v "${command}" >/dev/null 2>&1; then
        echo "Required command not found: ${command}" >&2
        exit 1
    fi
done

if [[ ! -x "${KIND_BIN}" ]] && ! command -v "${KIND_BIN}" >/dev/null 2>&1; then
    echo "kind executable not found: ${KIND_BIN}" >&2
    exit 1
fi

kind_nodes=()
while IFS= read -r node; do
    [[ -n "${node}" ]] && kind_nodes+=("${node}")
done < <("${KIND_BIN}" get nodes --name "${KIND_CLUSTER_NAME}")

if [[ "${#kind_nodes[@]}" -eq 0 ]]; then
    echo "No nodes found for kind cluster ${KIND_CLUSTER_NAME}" >&2
    exit 1
fi

# ECR's registry protocol uses a 12-hour Basic auth token. The AWS CLI obtains
# it with the Jenkins agent's instance-role credentials.
ecr_password="$(aws ecr get-login-password --region "${AWS_REGION}")"
ecr_authorization="$(printf 'AWS:%s' "${ecr_password}" | base64 | tr -d '\n')"
unset ecr_password

configure_registry_mirror() {
    local node="$1"
    local source_registry="$2"
    local ecr_prefix="$3"
    local registry_dir="/etc/containerd/certs.d/${source_registry}"
    # ECR pull-through repositories sit below /v2/<rule-prefix>; this full API root is used with override_path.
    local ecr_url="https://${ECR_PULL_THROUGH_ENDPOINT}/v2/${ecr_prefix}"

    docker exec "${node}" mkdir -p "${registry_dir}"
    cat <<EOF | docker exec -i "${node}" sh -c "umask 077 && cp /dev/stdin '${registry_dir}/hosts.toml'"
server = "https://${source_registry}"

[host."${ecr_url}"]
  capabilities = ["pull", "resolve"]
  override_path = true
  [host."${ecr_url}".header]
    authorization = "Basic ${ecr_authorization}"
EOF
}

for node in "${kind_nodes[@]}"; do
    if ! docker exec "${node}" grep -Fq \
        'config_path = "/etc/containerd/certs.d"' /etc/containerd/config.toml; then
        echo "Kind node ${node} does not load registry hosts from /etc/containerd/certs.d" >&2
        exit 1
    fi

    configure_registry_mirror "${node}" docker.io docker-hub
    configure_registry_mirror "${node}" registry-1.docker.io docker-hub
    configure_registry_mirror "${node}" mirror.gcr.io docker-hub
    configure_registry_mirror "${node}" quay.io quay
    configure_registry_mirror "${node}" public.ecr.aws ecr-public
    configure_registry_mirror "${node}" registry.k8s.io k8s
done

unset ecr_authorization
echo "Configured ECR pull-through cache on ${#kind_nodes[@]} kind node(s)"
