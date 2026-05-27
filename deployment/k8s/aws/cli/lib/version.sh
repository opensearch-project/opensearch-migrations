#!/usr/bin/env bash
# version.sh — CLI version constant + upgrade check.
#
# CLI_VERSION is baked at install time. MA_VERSION is what we pin when
# downloading CFN/helm/skills artifacts; user-overridable via wizard.

[[ -n "${__MIGRATE_VERSION_LOADED:-}" ]] && return 0
__MIGRATE_VERSION_LOADED=1

CLI_VERSION="${CLI_VERSION:-0.1.0}"

# Default Migration Assistant artifact version (matches PR 3008 MA_VERSION).
DEFAULT_MA_VERSION="3.2.1"

# Where we look for new CLI releases.
CLI_RELEASES_API='https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest'

# version_check — print a one-liner if a newer CLI version is published.
# Never auto-updates. Network errors are silent.
#
# `set -e + pipefail` would kill the whole CLI on a 404, so we run the
# pipeline in a forgiving subshell (`|| true`) and cache stderr to a temp.
version_check() {
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

# ma_default_version — print the MA artifact version for new wizards.
# Looks up GitHub releases first; falls back to DEFAULT_MA_VERSION.
ma_default_version() {
  if [[ -n "${MA_VERSION:-}" ]]; then
    printf '%s\n' "$MA_VERSION"
    return
  fi
  command -v curl >/dev/null 2>&1 || { printf '%s\n' "$DEFAULT_MA_VERSION"; return; }
  local body latest
  body=$(curl -fsSL --max-time 3 \
    'https://api.github.com/repos/opensearch-project/opensearch-migrations/releases/latest' \
    2>/dev/null || true)
  [[ -z "$body" ]] && { printf '%s\n' "$DEFAULT_MA_VERSION"; return; }
  latest=$(printf '%s' "$body" \
    | grep -o '"tag_name":[[:space:]]*"[^"]*"' \
    | head -1 \
    | sed -E 's/.*"([^"]+)"$/\1/' \
    || true)
  [[ -z "$latest" ]] && { printf '%s\n' "$DEFAULT_MA_VERSION"; return; }
  printf '%s\n' "${latest#v}"
}
