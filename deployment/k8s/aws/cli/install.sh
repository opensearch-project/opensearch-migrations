#!/usr/bin/env bash
# install.sh — curl-pipe installer for migration-assistant.
#
# Drops a versioned install at ~/.opensearch-migrate/cli/<version>/, symlinks
# ~/.local/bin/migration-assistant, and (when run interactively) auto-launches
# the CLI so the operator's first interaction is the deploy/resume flow.
#
# Usage:
#   curl -fsSL https://github.com/<owner>/<repo>/releases/latest/download/install.sh | bash
#
# Env overrides:
#   MIGRATE_REPO       owner/repo override (defaults below)
#   MIGRATE_VERSION    pin to a specific release tag (default: latest)
#   MIGRATE_PREFIX     install root  (default: ~/.opensearch-migrate/cli)
#   BIN_DIR            symlink target (default: ~/.local/bin)
#   INSTALL_FROM_LOCAL local path to install from (developer/test)
#   MIGRATE_NO_LAUNCH  set to skip auto-launching after install

set -o errexit
set -o nounset
set -o pipefail

# Repo path is baked at assemble time. assemble-bootstrap.sh substitutes
# @DEFAULT_REPO@ with whatever it was built against (the upstream
# opensearch-project repo for releases, AndreKurait/opensearch-migrations
# for fork previews). Operators can still override at runtime via
# MIGRATE_REPO if they're, e.g., installing one fork's tarball from a
# mirror of another.
# shellcheck disable=SC2016  # @DEFAULT_REPO@ is a literal sed placeholder
DEFAULT_REPO='@DEFAULT_REPO@'
# When this file is read from the source tree (developer flow), the
# placeholder hasn't been substituted; fall back to the upstream repo.
# Detect by joining the literal at runtime so sed never rewrites this
# comparison line — only the assignment two lines up.
if [[ "$DEFAULT_REPO" == "$(printf '@%s@' DEFAULT_REPO)" ]] || [[ -z "$DEFAULT_REPO" ]]; then
  DEFAULT_REPO='opensearch-project/opensearch-migrations'
fi
REPO="${MIGRATE_REPO:-$DEFAULT_REPO}"
VERSION="${MIGRATE_VERSION:-latest}"
PREFIX="${MIGRATE_PREFIX:-$HOME/.opensearch-migrate/cli}"
BIN_DIR="${BIN_DIR:-$HOME/.local/bin}"
INSTALL_FROM_LOCAL="${INSTALL_FROM_LOCAL:-}"
NO_LAUNCH="${MIGRATE_NO_LAUNCH:-0}"

c_red()    { printf '\033[31m%s\033[0m' "$1"; }
c_green()  { printf '\033[32m%s\033[0m' "$1"; }
c_yellow() { printf '\033[33m%s\033[0m' "$1"; }
c_dim()    { printf '\033[2m%s\033[0m' "$1"; }
c_bold()   { printf '\033[1m%s\033[0m' "$1"; }

die() { printf '%s %s\n' "$(c_red error:)" "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "required command not on PATH: $1"; }

# resolve_version → echo the version string to install. "latest" hits the
# releases API; everything else is taken as-is (tag name, no "v" prefix).
resolve_version() {
  if [[ "$VERSION" != "latest" ]]; then
    printf '%s\n' "$VERSION"
    return
  fi
  require curl
  local tag
  tag=$(curl -fsSL --max-time 10 \
    "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep -o '"tag_name":[[:space:]]*"[^"]*"' \
    | head -1 \
    | sed -E 's/.*"([^"]+)"$/\1/' || true)
  [[ -z "$tag" ]] && die "could not resolve latest version from https://api.github.com/repos/${REPO}/releases/latest"
  printf '%s\n' "${tag#v}"
}

main() {
  printf 'Setting up Migration Assistant...\n\n'

  require uname

  local version
  if [[ -n "$INSTALL_FROM_LOCAL" ]]; then
    version="local-$(date +%Y%m%d%H%M%S)"
  else
    require curl
    require tar
    version=$(resolve_version)
  fi

  local install_dir="$PREFIX/$version"
  mkdir -p "$install_dir" "$BIN_DIR"

  if [[ -n "$INSTALL_FROM_LOCAL" ]]; then
    cp -R "$INSTALL_FROM_LOCAL/bin"    "$install_dir/"
    cp -R "$INSTALL_FROM_LOCAL/lib"    "$install_dir/"
    cp -R "$INSTALL_FROM_LOCAL/skills" "$install_dir/"
  else
    local tarball="migration-assistant-cli-${version}.tar.gz"
    local url="https://github.com/${REPO}/releases/download/${version}/${tarball}"
    local tmp; tmp=$(mktemp -d)
    curl -fsSL --max-time 60 -o "$tmp/$tarball" "$url" \
      || die "could not download $url"
    tar -xzf "$tmp/$tarball" -C "$install_dir" --strip-components=1
    rm -rf "$tmp"
  fi

  chmod +x "$install_dir/bin/migration-assistant"
  ln -sfn "$install_dir/bin/migration-assistant" "$BIN_DIR/migration-assistant"

  printf '%s Migration Assistant successfully installed!\n\n' "$(c_green '✔')"
  printf '  Version:  %s\n' "$version"
  printf '  Location: %s/migration-assistant\n\n' "${BIN_DIR/#$HOME/~}"

  # PATH hint, only if missing.
  local path_warning=0
  if ! printf '%s' "$PATH" | tr ':' '\n' | grep -qxF "$BIN_DIR"; then
    path_warning=1
    printf '%s %s is not on your PATH.\n' "$(c_yellow '!')" "${BIN_DIR/#$HOME/~}"
    printf '  Add this line to your shell profile:\n\n'
    # shellcheck disable=SC2016
    printf '    export PATH="%s:$PATH"\n\n' "${BIN_DIR/#$HOME/~}"
  fi

  printf '  Next: Run %s to deploy or re-enter the migration console\n\n' \
    "$(c_bold 'migration-assistant')"
  printf '%s Installation complete!\n' "$(c_green '✓')"

  # Auto-launch when:
  #   * /dev/tty is reachable (works for `curl|bash` since stdin is the
  #     pipe, not a TTY — but /dev/tty still resolves to the operator's
  #     terminal in any normal shell session)
  #   * MIGRATE_NO_LAUNCH != 1
  #   * BIN_DIR is on PATH (otherwise the operator needs to fix that first)
  if [[ "$NO_LAUNCH" != "1" ]] \
     && [[ "$path_warning" -eq 0 ]] \
     && { : >/dev/tty; } 2>/dev/null; then
    printf '\n%s Launching %s now…\n\n' \
      "$(c_dim '↳')" "$(c_bold 'migration-assistant')"
    # Re-attach stdin to /dev/tty so curl|bash auto-launch can read prompts
    # (without this, the CLI's first prompt fails fast on EOF).
    exec "$BIN_DIR/migration-assistant" "$@" </dev/tty
  fi
}

main "$@"
