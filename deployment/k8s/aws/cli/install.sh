#!/usr/bin/env bash
# install.sh — curl-pipe installer for migration-assistant.
#
# Drops a workspace-local install at ./migration-assistant-workspace/cli/<version>/,
# symlinks ./migration-assistant-workspace/bin/migration-assistant, and
# (when run interactively) auto-launches the CLI so the operator's first
# interaction is the deploy/resume flow.
#
# Per-stage runtime state (state.env, log/, plan/, history/, .claude/,
# .codex/, .kiro/) lands under ./migration-assistant-workspace/<stage>/
# — co-located with the install so the whole project is one removable
# directory you can git-init, tar up, or rm -rf without touching $HOME.
#
# Usage:
#   curl -fsSL https://github.com/<owner>/<repo>/releases/latest/download/install.sh | bash
#
# Env overrides:
#   MIGRATE_REPO        owner/repo override (defaults below)
#   MIGRATE_VERSION     pin to a specific release tag (default: latest)
#   MIGRATE_WORKSPACE   workspace root (default: ./migration-assistant-workspace)
#   MIGRATE_HOME        per-stage state root (default: $MIGRATE_WORKSPACE)
#   MIGRATE_PREFIX      install root (default: $MIGRATE_WORKSPACE/cli)
#   BIN_DIR             symlink target (default: $MIGRATE_WORKSPACE/bin)
#   INSTALL_FROM_LOCAL  local path to install from (developer/test)
#   MIGRATE_NO_LAUNCH   set to skip auto-launching after install

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
# Resolve the workspace to an absolute path so the symlink + per-stage
# state still work after the operator cd's away from the install dir.
WORKSPACE_DEFAULT="$PWD/migration-assistant-workspace"
WORKSPACE="${MIGRATE_WORKSPACE:-$WORKSPACE_DEFAULT}"
case "$WORKSPACE" in
  /*) ;;                          # already absolute
  *)  WORKSPACE="$PWD/$WORKSPACE" ;;
esac
PREFIX="${MIGRATE_PREFIX:-$WORKSPACE/cli}"
BIN_DIR="${BIN_DIR:-$WORKSPACE/bin}"
# MIGRATE_HOME is what the CLI itself reads (lib/_common.sh) to locate
# per-stage state. Default it under the workspace too so a single
# rm -rf migration-assistant-workspace cleans everything up.
MIGRATE_HOME_DEFAULT="$WORKSPACE"
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

_pretty_for_activate() {
  case "$1" in
    "$PWD"/*) printf './%s' "${1#"$PWD"/}" ;;
    "$PWD")   printf '.' ;;
    *)        printf '%s' "$1" ;;
  esac
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

  # Workspace activation script. `source ./migration-assistant-workspace/activate`
  # sets MIGRATE_HOME + PATH for an existing shell so subsequent
  # `migration-assistant` invocations use workspace-local state.
  cat >"$WORKSPACE/activate" <<EOF
# Source this file: \`source $(_pretty_for_activate "$WORKSPACE")/activate\`
# Sets MIGRATE_HOME so per-stage state lands in this workspace, and
# prepends bin/ to PATH so \`migration-assistant\` resolves here.
export MIGRATE_HOME="$WORKSPACE"
case ":\$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) export PATH="$BIN_DIR:\$PATH" ;;
esac
EOF

  # Pretty-print paths relative to PWD when they're under it; otherwise
  # fall back to the ~ form for paths under $HOME; otherwise raw.
  # shellcheck disable=SC2088  # the literal '~/' is for display, not shell expansion
  _pretty() {
    local p="$1"
    case "$p" in
      "$PWD"/*)  printf './%s' "${p#"$PWD"/}" ;;
      "$PWD")    printf '.' ;;
      "$HOME"/*) printf '~/%s' "${p#"$HOME"/}" ;;
      *)         printf '%s' "$p" ;;
    esac
  }

  printf '%s Migration Assistant successfully installed!\n\n' "$(c_green '✔')"
  printf '  Version:    %s\n' "$version"
  printf '  Workspace:  %s\n' "$(_pretty "$WORKSPACE")"
  printf '  Binary:     %s/migration-assistant\n\n' "$(_pretty "$BIN_DIR")"

  # PATH hint. Workspace bin/ is unlikely to be on PATH, but the
  # auto-launch below uses an absolute path so this is informational
  # for follow-up invocations only — `source workspace/activate` is
  # the recommended way to wire the rest of the operator's session.
  if ! printf '%s' "$PATH" | tr ':' '\n' | grep -qxF "$BIN_DIR"; then
    printf '%s %s is not on your PATH.\n' "$(c_yellow '!')" "$(_pretty "$BIN_DIR")"
    printf '  Easiest: %ssource %s/activate%s\n' \
      "$(c_dim '')" "$(_pretty "$WORKSPACE")" "$(c_dim '')"
    printf '  Or call the binary by its absolute path: %s\n\n' \
      "$BIN_DIR/migration-assistant"
  fi

  printf '  Next: Run %s to deploy or re-enter the migration console\n' \
    "$(c_bold 'migration-assistant')"
  printf '  State for each stage lives at %s/<stage>/\n' \
    "$(_pretty "$WORKSPACE")"
  printf '  In a new shell: %ssource %s/activate%s to wire PATH + MIGRATE_HOME\n\n' \
    "$(c_dim '')" "$(_pretty "$WORKSPACE")" "$(c_dim '')"
  printf '%s Installation complete!\n' "$(c_green '✓')"

  # Auto-launch when /dev/tty is reachable (works for curl|bash since
  # stdin is the pipe, not a TTY — /dev/tty still resolves to the
  # operator's terminal in any normal shell session) AND
  # MIGRATE_NO_LAUNCH != 1. Pass MIGRATE_HOME so the launched CLI
  # uses workspace-local state instead of $HOME/.opensearch-migrate/.
  if [[ "$NO_LAUNCH" != "1" ]] \
     && { : >/dev/tty; } 2>/dev/null; then
    printf '\n%s Launching %s now…\n\n' \
      "$(c_dim '↳')" "$(c_bold 'migration-assistant')"
    export MIGRATE_HOME="$MIGRATE_HOME_DEFAULT"
    # Re-attach stdin to /dev/tty so curl|bash auto-launch can read
    # prompts (without this, the CLI's first prompt fails fast on EOF).
    exec "$BIN_DIR/migration-assistant" "$@" </dev/tty
  fi
}

# main is invoked with the script's args at the end of the file.

main "$@"
