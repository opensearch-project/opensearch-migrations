#!/usr/bin/env bash

set -euo pipefail

readonly KIND_VERSION="${KIND_VERSION:-v0.32.0}"
readonly KIND_INSTALL_DIR="${KIND_INSTALL_DIR:-${HOME}/.local/bin}"

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "Unsupported operating system: $(uname -s)" >&2
    exit 1
fi

case "$(uname -m)" in
    x86_64 | amd64)
        readonly kind_arch="amd64"
        readonly kind_sha256="50030de23cf40a18505f20426f6a8506bedf13c6e509244bd1fa9463721b0f54"
        ;;
    aarch64 | arm64)
        readonly kind_arch="arm64"
        readonly kind_sha256="b92cd615e97585de8ddade28ed5cd7feb4248d717c233eea5b03c37298900f5d"
        ;;
    *)
        echo "Unsupported architecture: $(uname -m)" >&2
        exit 1
        ;;
esac

readonly kind_asset="kind-linux-${kind_arch}"
readonly kind_binary="${KIND_INSTALL_DIR}/kind"
kind_download=""

cleanup() {
    [[ -z "${kind_download}" ]] || rm -f "${kind_download}"
}
trap cleanup EXIT

mkdir -p "${KIND_INSTALL_DIR}"
kind_download="$(mktemp "${KIND_INSTALL_DIR}/.kind.XXXXXX")"

curl --fail --silent --show-error --location \
    --retry 3 \
    --output "${kind_download}" \
    "https://github.com/kubernetes-sigs/kind/releases/download/${KIND_VERSION}/${kind_asset}"

printf '%s  %s\n' "${kind_sha256}" "${kind_download}" | sha256sum --check -
chmod 0755 "${kind_download}"
mv "${kind_download}" "${kind_binary}"
kind_download=""

"${kind_binary}" version
