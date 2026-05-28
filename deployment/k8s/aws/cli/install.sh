#!/usr/bin/env bash
# install.sh — curl-pipe bootstrap for migrate-cli.
#
# Drops a versioned install at ~/.opensearch-migrate/cli/<version>/, symlinks
# ~/.local/bin/migration-assistant, and prompts the user once if ~/.local/bin
# isn't on PATH yet.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/opensearch-project/migrate-cli/main/install.sh | bash
#   curl -fsSL .../install.sh | MIGRATE_VERSION=0.1.0 bash
#   curl -fsSL .../install.sh | INSTALL_FROM_LOCAL=/path/to/repo bash   # for testing

set -o errexit
set -o nounset
set -o pipefail

REPO_OWNER="${REPO_OWNER:-opensearch-project}"
REPO_NAME="${REPO_NAME:-opensearch-migrations}"
VERSION="${MIGRATE_VERSION:-latest}"
PREFIX="${MIGRATE_PREFIX:-$HOME/.opensearch-migrate/cli}"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
INSTALL_FROM_LOCAL="${INSTALL_FROM_LOCAL:-}"

c_red()    { printf '\033[31m%s\033[0m' "$1"; }
c_green()  { printf '\033[32m%s\033[0m' "$1"; }
c_yellow() { printf '\033[33m%s\033[0m' "$1"; }
c_bold()   { printf '\033[1m%s\033[0m' "$1"; }

die() { printf '%s %s\n' "$(c_red error:)" "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "required command not on PATH: $1"; }

resolve_version() {
  if [[ "$VERSION" != "latest" ]]; then
    printf '%s\n' "$VERSION"
    return
  fi
  require curl
  local tag
  tag=$(curl -fsSL --max-time 10 \
    "https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest" \
    | grep -o '"tag_name":[[:space:]]*"[^"]*"' \
    | head -1 \
    | sed -E 's/.*"([^"]+)"$/\1/' || true)
  [[ -z "$tag" ]] && die "could not determine latest version"
  printf '%s\n' "${tag#v}"
}

main() {
  printf '%s\n' "$(c_bold 'migrate-cli installer')"
  require uname

  local version
  if [[ -n "$INSTALL_FROM_LOCAL" ]]; then
    version="local-$(date +%Y%m%d%H%M%S)"
    printf '  source : local %s\n' "$INSTALL_FROM_LOCAL"
  else
    require curl
    require tar
    version=$(resolve_version)
    printf '  source : github %s/%s @ v%s\n' "$REPO_OWNER" "$REPO_NAME" "$version"
  fi

  local install_dir="$PREFIX/$version"
  printf '  prefix : %s\n' "$install_dir"
  printf '  bin    : %s/migration-assistant\n\n' "$BIN_DIR"

  mkdir -p "$install_dir" "$BIN_DIR"

  if [[ -n "$INSTALL_FROM_LOCAL" ]]; then
    printf 'Copying from %s …\n' "$INSTALL_FROM_LOCAL"
    cp -R "$INSTALL_FROM_LOCAL/bin" "$install_dir/"
    cp -R "$INSTALL_FROM_LOCAL/lib" "$install_dir/"
    cp -R "$INSTALL_FROM_LOCAL/skills" "$install_dir/"
  else
    # Tag convention matches assemble-bootstrap.sh + release-drafter:
    # tag is the bare version (no "v" prefix), asset is
    # migration-assistant-cli-<version>.tar.gz.
    local tarball="migration-assistant-cli-${version}.tar.gz"
    local url="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${version}/${tarball}"
    printf 'Downloading %s …\n' "$url"
    local tmp; tmp=$(mktemp -d)
    curl -fsSL --max-time 60 -o "$tmp/$tarball" "$url"
    tar -xzf "$tmp/$tarball" -C "$install_dir" --strip-components=1
    rm -rf "$tmp"
  fi

  chmod +x "$install_dir/bin/migration-assistant"
  ln -sfn "$install_dir/bin/migration-assistant" "$BIN_DIR/migration-assistant"

  printf '\n%s installed migration-assistant %s\n' "$(c_green '✓')" "$version"

  if ! printf '%s' "$PATH" | tr ':' '\n' | grep -qxF "$BIN_DIR"; then
    printf '\n%s %s is not on your PATH.\n' "$(c_yellow '!')" "$BIN_DIR"
    printf '  Add this line to your shell profile:\n\n'
    # shellcheck disable=SC2016  # literal string for the user's shell rc
    printf '    export PATH="%s:$PATH"\n\n' "$BIN_DIR"
  fi

  printf 'Run %s to get started.\n' "$(c_bold migration-assistant)"
}

main "$@"
