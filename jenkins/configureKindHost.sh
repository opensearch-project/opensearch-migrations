#!/usr/bin/env bash

set -euo pipefail

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

# kind nodes share the host kernel's inotify limits. Ubuntu's defaults can be
# exhausted by the control plane before Fluent Bit creates its tail watcher.
ensure_sysctl_minimum fs.inotify.max_user_watches 524288
ensure_sysctl_minimum fs.inotify.max_user_instances 512
