#!/usr/bin/env bash
# version.sh — CLI + Migration Assistant version resolution.
#
# CLI_VERSION is baked at install time. MA_VERSION is the artifact
# version we pin when downloading CFN/helm/skills artifacts.
# ma_default_version() resolves it dynamically from the
# opensearch-migrations releases API so a CLI release does NOT need to
# update this file every time MA cuts a new version.

[[ -n "${__MIGRATE_VERSION_LOADED:-}" ]] && return 0
__MIGRATE_VERSION_LOADED=1

# Default version. assemble-bootstrap.sh rewrites this line at build
# time to pin the CLI's reported version to the tarball's tag.
# shellcheck disable=SC2155
CLI_VERSION="${CLI_VERSION:-0.0.0-dev}"

# Releases APIs.
CLI_RELEASES_API='https://api.github.com/repos/opensearch-project/migrate-cli/releases/latest'
MA_RELEASES_API='https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest'

# Last-resort fallback for ma_default_version when the releases API
# isn't reachable AND the operator has no cached value in state. Empty
# means "fail loudly rather than silently use a hardcoded version" —
# which is what we want, because hardcoding has the rotting-comment
# property: the value drifts every release and operators can't tell.
LAST_RESORT_MA_VERSION=""

# upgrade_notice_for_cli — print a one-liner if a newer CLI version is
# published. Never auto-updates. Network errors are silent.
#
# `set -e + pipefail` would kill the whole CLI on a 404, so we run the
# pipeline in a forgiving subshell (`|| true`) and cache stderr to a temp.
upgrade_notice_for_cli() {
  command -v curl >/dev/null 2>&1 || return 0
  local body latest
  body=$(curl -fsSL --max-time 3 "$CLI_RELEASES_API" 2>/dev/null || true)
  [[ -z "$body" ]] && return 0
  latest=$(printf '%s' "$body" \
    | grep -o '"tag_name":[[:space:]]*"[^"]*"' \
    | head -1 \
    | sed -E 's/.*"([^"]+)"$/\1/' \
    || true)
  [[ -z "$latest" ]] && return 0
  latest=${latest#v}
  if [[ "$latest" != "$CLI_VERSION" ]]; then
    ui_dim "  ↳ new migration-assistant available: $latest (you: $CLI_VERSION) — upgrade: curl -fsSL .../install.sh | bash"
  fi
}

# Back-compat alias. Older callers (and the integ scripts) call
# version_check; keep that name working.
version_check() { upgrade_notice_for_cli "$@"; }

# ma_default_version — print the MA artifact version. Resolution order:
#   1. MA_VERSION env var (operator explicit override)
#   2. state.env MA_VERSION (cached from a previous run of this stage)
#   3. opensearch-migrations releases API (network)
#   4. LAST_RESORT_MA_VERSION if non-empty; otherwise fail
#
# This is called both at wizard time (to pre-fill the prompt default)
# and on resume (when state has no MA_VERSION yet). The resolution
# order means a fresh deploy gets the latest released version, while a
# resumed deploy stays pinned to whatever it was running before — no
# accidental version drift mid-deploy.
ma_default_version() {
  if [[ -n "${MA_VERSION:-}" ]]; then
    printf '%s\n' "$MA_VERSION"
    return
  fi
  # state may not be loaded yet on cold start; tolerate that.
  local cached
  if declare -F state_get >/dev/null 2>&1; then
    cached=$(state_get MA_VERSION "")
    if [[ -n "$cached" ]]; then
      printf '%s\n' "$cached"
      return
    fi
  fi
  if command -v curl >/dev/null 2>&1; then
    local body latest
    body=$(curl -fsSL --max-time 3 "$MA_RELEASES_API" 2>/dev/null || true)
    if [[ -n "$body" ]]; then
      latest=$(printf '%s' "$body" \
        | grep -o '"tag_name":[[:space:]]*"[^"]*"' \
        | head -1 \
        | sed -E 's/.*"([^"]+)"$/\1/' \
        || true)
      if [[ -n "$latest" ]]; then
        printf '%s\n' "${latest#v}"
        return
      fi
    fi
  fi
  if [[ -n "$LAST_RESORT_MA_VERSION" ]]; then
    printf '%s\n' "$LAST_RESORT_MA_VERSION"
    return
  fi
  die "could not determine Migration Assistant version: pass --version <ver> or check network access to $MA_RELEASES_API"
}
