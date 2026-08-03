#!/usr/bin/env bash

set -euo pipefail

readonly KIND_VERSION="${KIND_VERSION:-v0.31.0}"
readonly KIND_INSTALL_DIR="${KIND_INSTALL_DIR:-${HOME}/.local/bin}"
readonly KIND_CACHE_DIR="${KIND_CACHE_DIR:-${HOME}/.cache/opensearch-migrations/kind}"

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "Unsupported operating system: $(uname -s)" >&2
    exit 1
fi

case "$(uname -m)" in
    x86_64 | amd64)
        readonly kind_arch="amd64"
        readonly kind_sha256="eb244cbafcc157dff60cf68693c14c9a75c4e6e6fedaf9cd71c58117cb93e3fa"
        ;;
    aarch64 | arm64)
        readonly kind_arch="arm64"
        readonly kind_sha256="8e1014e87c34901cc422a1445866835d1e666f2a61301c27e722bdeab5a1f7e4"
        ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

readonly kind_asset="kind-linux-${kind_arch}"
readonly kind_binary="${KIND_INSTALL_DIR}/kind"
readonly kind_cache_version_dir="${KIND_CACHE_DIR}/${KIND_VERSION}"
readonly cached_kind_binary="${kind_cache_version_dir}/${kind_asset}"
kind_download=""

cleanup() {
    [[ -z "${kind_download}" ]] || rm -f "${kind_download}"
}
trap cleanup EXIT

verify_kind_binary() {
    local binary="$1"
    [[ -f "${binary}" ]] &&
        printf '%s  %s\n' "${kind_sha256}" "${binary}" | sha256sum --check --status
}

mkdir -p "${KIND_INSTALL_DIR}" "${kind_cache_version_dir}"

if verify_kind_binary "${kind_binary}"; then
    echo "Using installed ${KIND_VERSION} binary at ${kind_binary}"
    "${kind_binary}" version
    exit 0
fi

if verify_kind_binary "${cached_kind_binary}"; then
    echo "Using cached ${KIND_VERSION} binary at ${cached_kind_binary}"
else
    kind_download="$(mktemp "${kind_cache_version_dir}/.${kind_asset}.XXXXXX")"

    curl --fail --silent --show-error --location \
        --retry 3 \
        --output "${kind_download}" \
        "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/${kind_asset}"

    printf '%s  %s\n' "${kind_sha256}" "${kind_download}" | sha256sum --check -
    chmod 0755 "${kind_download}"
    mv "${kind_download}" "${cached_kind_binary}"
    kind_download=""
fi

install -m 0755 "${cached_kind_binary}" "${kind_binary}"
"${kind_binary}" version
