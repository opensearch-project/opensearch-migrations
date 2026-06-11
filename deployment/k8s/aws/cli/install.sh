#!/usr/bin/env bash
# install.sh — curl-pipe installer for migration-assistant.
#
# Supports: Linux (x86_64, aarch64), macOS (x86_64, arm64).
# Windows users: install WSL2 first, then run this from inside the Linux shell.
#
# Two install modes (pick with MIGRATE_INSTALL, default `path`):
#
#   path       (default) — a normal CLI install. Unpacks the bundle tarball
#              into ~/.opensearch-migration-assistant/ and places the native
#              binary inside its bin/ subdir. A symlink at ~/.local/bin/ puts
#              it on PATH. Does NOT drop anything in the current directory and
#              does NOT auto-launch. You then `cd` into whatever directory you
#              want to use as your migration project and run
#              `migration-assistant` there — that directory becomes the
#              project (state lands in ./migration-assistant-workspace/).
#
#   workspace  — the project-local install. Unpacks everything into
#              ./migration-assistant-workspace/ (binary in bin/, skills,
#              manifest, VERSION.txt all in one tree). Writes an `activate`
#              script, and auto-launches the CLI so your first interaction is
#              the deploy/resume flow. The whole project is one removable dir.
#
# Usage:
#   curl -fsSL https://github.com/<owner>/<repo>/releases/latest/download/install.sh | bash
#   curl -fsSL .../install.sh | MIGRATE_INSTALL=workspace bash
#
# Env overrides:
#   MIGRATE_INSTALL     `path` (default) or `workspace`
#   MIGRATE_REPO        owner/repo override (defaults below)
#   MIGRATE_VERSION     pin to a specific release tag (default: latest)
#   MIGRATE_PREFIX      bundle install root (default: per-mode, see above)
#   BIN_DIR             binary install dir (path mode default: ~/.local/bin)
#   MIGRATE_WORKSPACE   workspace root (workspace mode; default ./migration-assistant-workspace)
#   MIGRATE_NO_LAUNCH   set to 1 to skip auto-launch in workspace mode

set -o errexit
set -o nounset
set -o pipefail

# ── configuration ─────────────────────────────────────────────────────────

# Repo path is baked at release time. The Gradle stampInstallScript
# task substitutes @DEFAULT_REPO@ with the repo it was built from (the
# upstream opensearch-project for releases, a fork for fork previews).
# Operators can override at runtime via MIGRATE_REPO.
# shellcheck disable=SC2016  # @DEFAULT_REPO@ is a literal sed placeholder
DEFAULT_REPO='@DEFAULT_REPO@'
# When this file is read from the source tree (developer flow), the
# placeholder hasn't been substituted; fall back to the upstream repo.
if [[ "$DEFAULT_REPO" == "$(printf '@%s@' DEFAULT_REPO)" ]] || [[ -z "$DEFAULT_REPO" ]]; then
  DEFAULT_REPO='opensearch-project/opensearch-migrations'
fi
REPO="${MIGRATE_REPO:-$DEFAULT_REPO}"

# VERSION may be baked at release time (Gradle stamps @VERSION@).
# When run from source or when operator overrides via env, the baked value is
# ignored. The fallback `latest` triggers a GitHub API call in resolve_version.
BAKED_VERSION='@VERSION@'
if [[ "$BAKED_VERSION" == "$(printf '@%s@' VERSION)" ]] || [[ -z "$BAKED_VERSION" ]]; then
  BAKED_VERSION=""
fi
VERSION="${MIGRATE_VERSION:-${BAKED_VERSION:-latest}}"

INSTALL_MODE="${MIGRATE_INSTALL:-path}"
NO_LAUNCH="${MIGRATE_NO_LAUNCH:-0}"

# ── helpers ───────────────────────────────────────────────────────────────

c_red()    { printf '\033[31m%s\033[0m' "$1"; }
c_green()  { printf '\033[32m%s\033[0m' "$1"; }
c_yellow() { printf '\033[33m%s\033[0m' "$1"; }
c_dim()    { printf '\033[2m%s\033[0m' "$1"; }
c_bold()   { printf '\033[1m%s\033[0m' "$1"; }

die() { printf '%s %s\n' "$(c_red error:)" "$*" >&2; exit 1; }

require() { command -v "$1" >/dev/null 2>&1 || die "required command not on PATH: $1"; }

# Pretty-print paths relative to PWD when under it, else ~ form, else raw.
# shellcheck disable=SC2088
_pretty() {
  local p="$1"
  case "$p" in
    "$PWD"/*)  printf './%s' "${p#"$PWD"/}" ;;
    "$PWD")    printf '.' ;;
    "$HOME"/*) printf '~/%s' "${p#"$HOME"/}" ;;
    *)         printf '%s' "$p" ;;
  esac
}

# ── platform detection ────────────────────────────────────────────────────

detect_platform() {
  require uname

  local os arch target

  os="$(uname -s)"
  arch="$(uname -m)"

  case "$os" in
    Linux)
      case "$arch" in
        x86_64|amd64)   target="x86_64-unknown-linux-gnu" ;;
        aarch64|arm64)  target="aarch64-unknown-linux-gnu" ;;
        *) die "unsupported Linux architecture: $arch (supported: x86_64, aarch64)" ;;
      esac
      ;;
    Darwin)
      case "$arch" in
        x86_64|amd64)   target="x86_64-apple-darwin" ;;
        aarch64|arm64)  target="aarch64-apple-darwin" ;;
        *) die "unsupported macOS architecture: $arch (supported: x86_64, arm64)" ;;
      esac
      ;;
    MINGW*|MSYS*|CYGWIN*)
      die "native Windows is not supported. Install WSL2 and run this from inside WSL:

  1. Open PowerShell as Administrator and run:  wsl --install
  2. Restart your computer
  3. Open Ubuntu from the Start menu
  4. Re-run this installer inside the Linux shell

  Docs: https://learn.microsoft.com/en-us/windows/wsl/install"
      ;;
    *)
      die "unsupported OS: $os (supported: Linux, macOS). On Windows, use WSL2."
      ;;
  esac

  PLATFORM_TARGET="$target"
}


# ── version resolution ────────────────────────────────────────────────────

resolve_version() {
  if [[ "$VERSION" != "latest" ]]; then
    printf '%s\n' "$VERSION"
    return
  fi
  require curl
  local tag
  tag=$(curl -fsSL --connect-timeout 10 --max-time 30 --retry 3 --retry-all-errors \
    "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep -o '"tag_name":[[:space:]]*"[^"]*"' \
    | head -1 \
    | sed -E 's/.*"([^"]+)"$/\1/' || true)
  [[ -z "$tag" ]] && die "could not resolve latest version from https://api.github.com/repos/${REPO}/releases/latest"
  # Strip leading 'v' if present
  printf '%s\n' "${tag#v}"
}

# ── download helpers ──────────────────────────────────────────────────────

# fetch <url> <output_path> — download a file with retries.
fetch() {
  local url="$1" out="$2"
  curl -fL --connect-timeout 30 --max-time 300 \
       --retry 5 --retry-delay 3 --retry-all-errors \
       -o "$out" "$url" \
    || die "download failed: $url (network/CDN unreachable after retries)"
}

# download_binary <version> <dest_dir> — download the platform-specific binary.
# Returns the path to the downloaded binary via stdout.
download_binary() {
  local version="$1" dest_dir="$2"
  local binary_name="migration-assistant-${PLATFORM_TARGET}"
  local url="https://github.com/${REPO}/releases/download/${version}/${binary_name}"
  local dest="${dest_dir}/${binary_name}"

  printf '  Downloading binary: %s\n' "$binary_name" >&2
  fetch "$url" "$dest"
  chmod +x "$dest"
  printf '%s' "$dest"
}

# download_bundle <version> <dest_dir> — download and extract the shared bundle.
download_bundle() {
  local version="$1" dest_dir="$2"
  local tarball="migration-assistant-bundle.tar.gz"
  local url="https://github.com/${REPO}/releases/download/${version}/${tarball}"
  local tmp_tar; tmp_tar=$(mktemp "${TMPDIR:-/tmp}/bundle-XXXXXX.tar.gz")

  printf '  Downloading bundle: %s\n' "$tarball"
  fetch "$url" "$tmp_tar"

  # Extract atomically: if an old bundle exists, move it aside
  if [[ -d "$dest_dir" ]]; then
    rm -rf "${dest_dir}.old" 2>/dev/null || true
    mv "$dest_dir" "${dest_dir}.old" || die "could not move existing bundle aside"
  fi
  mkdir -p "$dest_dir"
  tar -xzf "$tmp_tar" -C "$dest_dir" --strip-components=1 \
    || { rm -rf "$dest_dir"; mv "${dest_dir}.old" "$dest_dir" 2>/dev/null || true; die "bundle extract failed"; }
  rm -rf "${dest_dir}.old" 2>/dev/null || true
  rm -f "$tmp_tar"
}

# ── branding ──────────────────────────────────────────────────────────────

# read_binary_name <bundle_dir> — echoes branding.binaryName from
# manifest.json, defaulting to "migration-assistant" when absent.
read_binary_name() {
  local bundle_dir="$1"
  local manifest=""
  if [[ -f "$bundle_dir/manifest.json" ]]; then
    manifest="$bundle_dir/manifest.json"
  fi
  if [[ -z "$manifest" ]]; then
    printf '%s' "migration-assistant"
    return
  fi
  if command -v jq >/dev/null 2>&1; then
    local v
    v=$(jq -r '.branding.binaryName // "migration-assistant"' "$manifest" 2>/dev/null)
    [[ -z "$v" || "$v" == "null" ]] && v="migration-assistant"
    printf '%s' "$v"
  else
    # Regex fallback for environments without jq.
    local v
    v=$(grep -oE '"binaryName"[[:space:]]*:[[:space:]]*"[^"]+"' "$manifest" \
        | head -1 \
        | sed -E 's/.*"([^"]+)"$/\1/')
    [[ -z "$v" ]] && v="migration-assistant"
    printf '%s' "$v"
  fi
}

# ── VERSION.txt ───────────────────────────────────────────────────────────

# write_version_file <path> <version> — writes VERSION.txt for runtime resolution.
write_version_file() {
  local dir="$1" version="$2"
  printf '%s\n' "$version" > "$dir/VERSION.txt"
}

# ── Mode: path (default) ─────────────────────────────────────────────────

install_path_mode() {
  printf 'Installing Migration Assistant CLI...\n\n'
  require curl
  require tar

  detect_platform

  local version; version=$(resolve_version)
  local home_dir; home_dir=$HOME
  local install_dir="${MIGRATE_PREFIX:-${home_dir}/.opensearch-migration-assistant}"
  local link_dir="${BIN_DIR:-${home_dir}/.local/bin}"

  # Download and extract the bundle into the install tree
  download_bundle "$version" "$install_dir"

  # Write VERSION.txt at the install root (binary finds it via ancestor search)
  write_version_file "$install_dir" "$version"

  # Create bin/ inside the install tree for the native binary
  mkdir -p "$install_dir/bin"

  # Read branding from the extracted bundle
  local bin_name; bin_name=$(read_binary_name "$install_dir")

  # Download the platform-specific binary into install_dir/bin/
  local tmp_dir; tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/migrate-bin-XXXXXX")
  local downloaded_binary; downloaded_binary=$(download_binary "$version" "$tmp_dir")
  mv "$downloaded_binary" "$install_dir/bin/${bin_name}"
  chmod +x "$install_dir/bin/${bin_name}"
  rm -rf "$tmp_dir"

  # Symlink onto PATH so the operator can just type the binary name
  mkdir -p "$link_dir"
  ln -sfn "$install_dir/bin/${bin_name}" "$link_dir/${bin_name}"

  printf '\n%s %s %s installed\n\n' "$(c_green '✔')" "$bin_name" "$version"
  printf '  Platform: %s\n' "$PLATFORM_TARGET"
  printf '  Install:  %s\n' "$(_pretty "$install_dir")"
  printf '  Symlink:  %s/%s\n\n' "$(_pretty "$link_dir")" "${bin_name}"

  # PATH check
  if ! printf '%s' "$PATH" | tr ':' '\n' | grep -qxF "$link_dir"; then
    printf '%s %s is not on your PATH. Add it to your shell rc:\n' \
      "$(c_yellow '!')" "$(_pretty "$link_dir")"
    # shellcheck disable=SC2016
    printf '    export PATH="%s:$PATH"\n\n' "$(_pretty "$link_dir")"
  fi

  printf '%s\n' "$(c_bold 'Next: cd into the directory you want to use as your migration project, then run:')"
  printf '    %s\n\n' "$(c_bold "$bin_name")"
  printf '  That directory becomes the project — state, logs, and plan land in\n'
  printf '  %s there (one removable folder). Use %s for a named project.\n' \
    "$(c_dim './migration-assistant-workspace/')" "$(c_dim '--stage <name>')"
  printf '%s Installation complete!\n' "$(c_green '✓')"
}

# ── Mode: workspace ──────────────────────────────────────────────────────

install_workspace_mode() {
  printf 'Setting up Migration Assistant workspace...\n\n'
  require curl
  require tar

  detect_platform

  local version; version=$(resolve_version)

  # Resolve the workspace to an absolute path so per-stage state still
  # works after the operator cd's away from the install dir.
  local workspace="${MIGRATE_WORKSPACE:-$PWD/migration-assistant-workspace}"
  case "$workspace" in /*) ;; *) workspace="$PWD/$workspace" ;; esac

  # The entire install tree lives inside the workspace — one removable dir.
  local install_dir="${MIGRATE_PREFIX:-$workspace}"

  # Download and extract the bundle into the workspace root
  download_bundle "$version" "$install_dir"

  # Write VERSION.txt at the workspace root (binary finds it via ancestor search)
  write_version_file "$install_dir" "$version"

  # Create bin/ inside the workspace for the native binary
  mkdir -p "$install_dir/bin"

  # Read branding from the extracted bundle
  local bin_name; bin_name=$(read_binary_name "$install_dir")
  local final_binary="$install_dir/bin/${bin_name}"

  # Download the platform-specific binary into workspace/bin/
  local tmp_dir; tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/migrate-bin-XXXXXX")
  local downloaded_binary; downloaded_binary=$(download_binary "$version" "$tmp_dir")
  mv "$downloaded_binary" "$final_binary"
  chmod +x "$final_binary"
  rm -rf "$tmp_dir"

  # activate script: `source ./migration-assistant-workspace/activate`
  cat >"$workspace/activate" <<EOF
# Source this file: \`source $(_pretty "$workspace")/activate\`
# Sets MIGRATE_HOME so per-stage state lands in this workspace, and
# prepends bin/ to PATH so \`$bin_name\` resolves here.
export MIGRATE_HOME="$workspace"
case ":\$PATH:" in
  *":$install_dir/bin:"*) ;;
  *) export PATH="$install_dir/bin:\$PATH" ;;
esac
EOF

  printf '\n%s Migration Assistant workspace ready\n\n' "$(c_green '✔')"
  printf '  Version:    %s\n' "$version"
  printf '  Platform:   %s\n' "$PLATFORM_TARGET"
  printf '  Workspace:  %s\n' "$(_pretty "$workspace")"
  printf '  Binary:     %s\n\n' "$(_pretty "$final_binary")"
  printf '  In a new shell: %ssource %s/activate%s to wire PATH + MIGRATE_HOME\n' \
    "$(c_dim '')" "$(_pretty "$workspace")" "$(c_dim '')"
  printf '  State for each stage lives at %s/<stage>/\n\n' "$(_pretty "$workspace")"
  printf '%s Installation complete!\n' "$(c_green '✓')"

  # Auto-launch when an interactive terminal is reachable.
  if [[ "$NO_LAUNCH" == "1" ]]; then
    return 0
  fi
  if { exec 3<>/dev/tty; } 2>/dev/null; then
    exec 3>&- 3<&-
    printf '\n%s Launching %s now…\n\n' "$(c_dim '↳')" "$(c_bold "$bin_name")"
    export MIGRATE_HOME="$workspace"
    exec "$final_binary" "$@" </dev/tty
  else
    printf '\n%s no interactive terminal detected — not auto-launching.\n' "$(c_yellow '!')"
    printf '  Run it yourself from a real terminal:\n'
    printf '    %ssource %s/activate && %s%s\n' \
      "$(c_bold '')" "$(_pretty "$workspace")" "$bin_name" "$(c_bold '')"
  fi
}

# ── main ──────────────────────────────────────────────────────────────────

main() {
  case "$INSTALL_MODE" in
    path)      install_path_mode "$@" ;;
    workspace) install_workspace_mode "$@" ;;
    *) die "unknown MIGRATE_INSTALL='$INSTALL_MODE' (expected 'path' or 'workspace')" ;;
  esac
}

main "$@"
