#!/usr/bin/env bash
# install.sh — curl-pipe installer for migration-assistant.
#
# Two install modes (pick with MIGRATE_INSTALL, default `path`):
#
#   path       (default) — a normal CLI install. Unpacks the versioned
#              tree under ~/.opensearch-migrate/cli/<version>/ and
#              symlinks ~/.local/bin/migration-assistant onto your PATH.
#              Does NOT drop anything in the current directory and does
#              NOT auto-launch. You then `cd` into whatever directory you
#              want to use as your migration project and run
#              `migration-assistant` there — that directory becomes the
#              project (state lands in ./migration-assistant-workspace/).
#
#   workspace  — the project-local install. Unpacks under
#              ./migration-assistant-workspace/cli/<version>/, symlinks
#              ./migration-assistant-workspace/bin/migration-assistant,
#              writes an `activate` script, and auto-launches the CLI so
#              your first interaction is the deploy/resume flow. The whole
#              project is one removable directory.
#
# Usage:
#   curl -fsSL https://github.com/<owner>/<repo>/releases/latest/download/install.sh | bash
#   curl -fsSL .../install.sh | MIGRATE_INSTALL=workspace bash
#
# Env overrides:
#   MIGRATE_INSTALL     `path` (default) or `workspace`
#   MIGRATE_REPO        owner/repo override (defaults below)
#   MIGRATE_VERSION     pin to a specific release tag (default: latest)
#   MIGRATE_PREFIX      install root (default: per-mode, see above)
#   BIN_DIR             symlink dir (path mode default: ~/.local/bin)
#   MIGRATE_WORKSPACE   workspace root (workspace mode; default ./migration-assistant-workspace)
#   INSTALL_FROM_LOCAL  local path to install from (developer/test)
#   MIGRATE_NO_LAUNCH   set to 1 to skip auto-launch in workspace mode

set -o errexit
set -o nounset
set -o pipefail

# Repo path is baked at assemble time. assemble-bootstrap.sh substitutes
# @DEFAULT_REPO@ with whatever it was built against (the upstream
# opensearch-project repo for releases, a fork for fork previews).
# Operators can still override at runtime via MIGRATE_REPO.
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
INSTALL_MODE="${MIGRATE_INSTALL:-path}"
INSTALL_FROM_LOCAL="${INSTALL_FROM_LOCAL:-}"
NO_LAUNCH="${MIGRATE_NO_LAUNCH:-0}"

c_red()    { printf '\033[31m%s\033[0m' "$1"; }
c_green()  { printf '\033[32m%s\033[0m' "$1"; }
c_yellow() { printf '\033[33m%s\033[0m' "$1"; }
c_dim()    { printf '\033[2m%s\033[0m' "$1"; }
c_bold()   { printf '\033[1m%s\033[0m' "$1"; }

die() { printf '%s %s\n' "$(c_red error:)" "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "required command not on PATH: $1"; }

# Pretty-print paths relative to PWD when under it, else ~ form, else raw.
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

# fetch_into <install_dir> — populate <install_dir> with bin/ lib/ skills/
# from a local tree (INSTALL_FROM_LOCAL) or the released tarball.
fetch_into() {
  local install_dir="$1" version="$2"
  if [[ -n "$INSTALL_FROM_LOCAL" ]]; then
    cp -R "$INSTALL_FROM_LOCAL/bin"    "$install_dir/"
    cp -R "$INSTALL_FROM_LOCAL/lib"    "$install_dir/"
    cp -R "$INSTALL_FROM_LOCAL/skills" "$install_dir/"
  else
    local tarball="migration-assistant-cli-${version}.tar.gz"
    local url="https://github.com/${REPO}/releases/download/${version}/${tarball}"
    local tmp; tmp=$(mktemp -d)
    curl -fsSL --max-time 60 -o "$tmp/$tarball" "$url" \
      || { rm -rf "$tmp"; die "could not download $url"; }
    tar -xzf "$tmp/$tarball" -C "$install_dir" --strip-components=1
    rm -rf "$tmp"
  fi
  chmod +x "$install_dir/bin/migration-assistant"
}

resolve_install_version() {
  if [[ -n "$INSTALL_FROM_LOCAL" ]]; then
    printf 'local-%s\n' "$(date +%Y%m%d%H%M%S)"
  else
    require curl; require tar
    resolve_version
  fi
}

# ----------------------------------------------------------------------
# Mode: path (default) — install the binary onto PATH, nothing in CWD.
# ----------------------------------------------------------------------
install_path_mode() {
  printf 'Installing Migration Assistant CLI...\n\n'
  require uname

  local version; version=$(resolve_install_version)
  local prefix="${MIGRATE_PREFIX:-$HOME/.opensearch-migrate/cli/$version}"
  local bin_dir="${BIN_DIR:-$HOME/.local/bin}"
  mkdir -p "$prefix" "$bin_dir"

  fetch_into "$prefix" "$version"
  ln -sfn "$prefix/bin/migration-assistant" "$bin_dir/migration-assistant"

  printf '%s migration-assistant %s installed\n\n' "$(c_green '✔')" "$version"
  printf '  Binary: %s/migration-assistant\n\n' "$(_pretty "$bin_dir")"

  if ! printf '%s' "$PATH" | tr ':' '\n' | grep -qxF "$bin_dir"; then
    printf '%s %s is not on your PATH. Add it to your shell rc:\n' \
      "$(c_yellow '!')" "$(_pretty "$bin_dir")"
    # shellcheck disable=SC2016  # $PATH is literal text for the user's rc
    printf '    export PATH="%s:$PATH"\n\n' "$(_pretty "$bin_dir")"
  fi

  printf '%s\n' "$(c_bold 'Next: cd into the directory you want to use as your migration project, then run:')"
  printf '    %s\n\n' "$(c_bold 'migration-assistant')"
  printf '  That directory becomes the project — state, logs, and plan land in\n'
  printf '  %s there (one removable folder). Use %s for a named project.\n' \
    "$(c_dim './migration-assistant-workspace/')" "$(c_dim '--stage <name>')"
  printf '%s Installation complete!\n' "$(c_green '✓')"
}

# ----------------------------------------------------------------------
# Mode: workspace — project-local install + activate script + auto-launch.
# ----------------------------------------------------------------------
install_workspace_mode() {
  printf 'Setting up Migration Assistant workspace...\n\n'
  require uname

  local version; version=$(resolve_install_version)

  # Resolve the workspace to an absolute path so the symlink + per-stage
  # state still work after the operator cd's away from the install dir.
  local workspace="${MIGRATE_WORKSPACE:-$PWD/migration-assistant-workspace}"
  case "$workspace" in /*) ;; *) workspace="$PWD/$workspace" ;; esac
  local prefix="${MIGRATE_PREFIX:-$workspace/cli}"
  local bin_dir="${BIN_DIR:-$workspace/bin}"
  local install_dir="$prefix/$version"
  mkdir -p "$install_dir" "$bin_dir"

  fetch_into "$install_dir" "$version"
  ln -sfn "$install_dir/bin/migration-assistant" "$bin_dir/migration-assistant"

  # activate: `source ./migration-assistant-workspace/activate` wires
  # MIGRATE_HOME + PATH for an existing shell.
  cat >"$workspace/activate" <<EOF
# Source this file: \`source $(_pretty "$workspace")/activate\`
# Sets MIGRATE_HOME so per-stage state lands in this workspace, and
# prepends bin/ to PATH so \`migration-assistant\` resolves here.
export MIGRATE_HOME="$workspace"
case ":\$PATH:" in
  *":$bin_dir:"*) ;;
  *) export PATH="$bin_dir:\$PATH" ;;
esac
EOF

  printf '%s Migration Assistant workspace ready\n\n' "$(c_green '✔')"
  printf '  Version:    %s\n' "$version"
  printf '  Workspace:  %s\n' "$(_pretty "$workspace")"
  printf '  Binary:     %s/migration-assistant\n\n' "$(_pretty "$bin_dir")"
  printf '  In a new shell: %ssource %s/activate%s to wire PATH + MIGRATE_HOME\n' \
    "$(c_dim '')" "$(_pretty "$workspace")" "$(c_dim '')"
  printf '  State for each stage lives at %s/<stage>/\n\n' "$(_pretty "$workspace")"
  printf '%s Installation complete!\n' "$(c_green '✓')"

  # Auto-launch only when an interactive terminal is genuinely reachable.
  # The curl|bash launch via /dev/tty does NOT work in some IDE terminals
  # (the raw-mode TUI can't acquire a controlling terminal through the
  # exec), so we gate on /dev/tty being readable AND writable and bail
  # to a printed command otherwise rather than wedging.
  if [[ "$NO_LAUNCH" == "1" ]]; then
    return 0
  fi
  if { exec 3<>/dev/tty; } 2>/dev/null; then
    exec 3>&- 3<&-
    printf '\n%s Launching %s now…\n\n' "$(c_dim '↳')" "$(c_bold 'migration-assistant')"
    export MIGRATE_HOME="$workspace"
    exec "$bin_dir/migration-assistant" "$@" </dev/tty
  else
    printf '\n%s no interactive terminal detected — not auto-launching.\n' "$(c_yellow '!')"
    printf '  Run it yourself from a real terminal:\n'
    printf '    %ssource %s/activate && migration-assistant%s\n' \
      "$(c_bold '')" "$(_pretty "$workspace")" "$(c_bold '')"
  fi
}

main() {
  case "$INSTALL_MODE" in
    path)              install_path_mode "$@" ;;
    workspace)         install_workspace_mode "$@" ;;
    *) die "unknown MIGRATE_INSTALL='$INSTALL_MODE' (expected 'path' or 'workspace')" ;;
  esac
}

main "$@"
