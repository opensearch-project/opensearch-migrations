#!/usr/bin/env bash

set -euo pipefail

if [[ ! -f /sys/fs/cgroup/cgroup.controllers ]]; then
    echo "Kubernetes 1.35 kind nodes require a cgroup v2 host" >&2
    exit 1
fi

readonly docker_cgroup_version="$(docker info --format '{{.CgroupVersion}}')"
if [[ "${docker_cgroup_version}" != "2" ]]; then
    echo "Kubernetes 1.35 kind nodes require Docker cgroup v2; found ${docker_cgroup_version}" >&2
    exit 1
fi

ensure_sysctl_minimum() {
    local name="$1"
    local minimum="$2"
    local current
    current="$(sysctl -n "${name}")"

    if (( current < minimum )); then
        sudo sysctl -w "${name}=${minimum}"
    else
        echo "${name}=${current} already meets the minimum ${minimum}"
    fi
}

# kind nodes share the host kernel's inotify limits. Low host defaults can be
# exhausted by the control plane before Fluent Bit creates its tail watcher.
ensure_sysctl_minimum fs.inotify.max_user_watches 524288
ensure_sysctl_minimum fs.inotify.max_user_instances 512
