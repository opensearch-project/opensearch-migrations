#!/usr/bin/env bash
# manifest.sh — load + parse the bundle's manifest.json.
#
# manifest.json lives at the tarball root next to bin/ + lib/ + skills/.
# It declares:
#   * build.name / version / packs[] — bundle identity + pack provenance
#   * branding.* — operator-visible chrome (appName, modes, prompts)
#   * skills.discovery — "auto" (walk skills/*/SKILL.md) is the only
#                        legal value today
#   * mcpServers — name → command/args/scope/agents/requires/permissionsAllow
#
# Resolution order (first found wins):
#   1. $LIB_DIR/../manifest.json     (release tarball — sibling of bin/)
#   2. $LIB_DIR/../skills/manifest.json    (release tarball — bundled inside skills/)
#   3. $LIB_DIR/../../../../../agent-skills/skills/manifest.json   (repo dev mode)
#   4. $MIGRATE_MANIFEST                (operator override for testing)
#
# Public API:
#   manifest_init                       Parse manifest.json once. Cache results.
#   manifest_have                       Return 0 if a manifest is loaded.
#   manifest_brand <field>              Echo branding.<field> (with var substitution).
#   manifest_skills_dir                 Echo the absolute skills/ directory path.
#   manifest_mcp_names <agent>          List MCP names whose `agents` includes <agent>.
#   manifest_mcp_field <name> <field>   Echo mcpServers.<name>.<field>.
#   manifest_mcp_args <name>            Print one arg per line, with ${VAR} substituted.
#   manifest_mcp_perms <name>           Print one permission per line.
#   manifest_pack_summary               Echo "+pack-ver +pack-ver" for build.packs[].
#   manifest_modes                      Print "id|label|description|default|available"
#                                       per visible mode, in array order.
#
# Implementation: every API call is jq + a small bash post-processor.
# jq is required for the model layer (state.json) so it's already a hard
# dep — no fallback path. Manifest is parsed once and cached in shell
# globals so subsequent calls don't re-fork jq.

[[ -n "${__MIGRATE_MANIFEST_LOADED:-}" ]] && return 0
__MIGRATE_MANIFEST_LOADED=1

# shellcheck source=lib/std.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/std.sh"

__MANIFEST_PATH=""
__MANIFEST_PARSED=0
__MANIFEST_RAW=""

# manifest_init — locate + parse manifest.json. Idempotent.
manifest_init() {
  if (( __MANIFEST_PARSED == 1 )); then return 0; fi
  __MANIFEST_PARSED=1

  # Resolve which manifest.json we're using. Walk the candidate list in
  # priority order and stop at the first that exists.
  local cand path=""
  local candidates=()
  if [[ -n "${MIGRATE_MANIFEST:-}" ]]; then
    candidates+=("$MIGRATE_MANIFEST")
  fi
  candidates+=(
    "$LIB_DIR/../manifest.json"
    "$LIB_DIR/../skills/manifest.json"
    "$LIB_DIR/../../../../../agent-skills/skills/manifest.json"
  )
  for cand in "${candidates[@]}"; do
    if [[ -f "$cand" ]]; then
      path="$cand"
      break
    fi
  done

  if [[ -z "$path" ]]; then
    # No manifest. Caller should fall back to today's hard-coded behavior
    # via manifest_have returning non-zero.
    return 0
  fi

  __MANIFEST_PATH="$path"
  __MANIFEST_RAW=$(<"$path")

  # Validate schemaVersion. Unknown versions abort loudly — better than
  # half-honoring a future schema and confusing the operator.
  local sv
  sv=$(printf '%s' "$__MANIFEST_RAW" | jq -r '.schemaVersion // 0')
  if [[ "$sv" != "1" ]]; then
    die "manifest.json schemaVersion=$sv (this CLI only understands schemaVersion=1): $path"
  fi
  log_info "manifest: loaded $path (schemaVersion=$sv)"
}

# manifest_have — exit 0 if a manifest was loaded.
manifest_have() {
  manifest_init
  [[ -n "$__MANIFEST_RAW" ]]
}

# _manifest_substitute_vars <string>
# Replace ${VAR} tokens with state values, then env values, then leave
# literal with a WARN. $$ → $.
_manifest_substitute_vars() {
  local s="$1"
  local out=""
  local rest="$s"
  # Cheap iteration: walk through, splitting on the next ${…} occurrence.
  # The match pattern uses double-quoted ${…} literal — single-quoted
  # is the same string here but shellcheck SC2016 prefers double-quoted
  # for clarity.
  while [[ "$rest" == *"\${"*"}"* ]]; do
    out+="${rest%%\$\{*}"
    rest="${rest#*\$\{}"
    local var="${rest%%\}*}"
    rest="${rest#*\}}"
    local v=""
    if declare -F state_get >/dev/null 2>&1; then
      v=$(state_get "$var" "")
    fi
    if [[ -z "$v" ]]; then
      v="${!var-}"
    fi
    if [[ -z "$v" ]]; then
      log_warn "manifest: \${$var} could not be resolved (state empty, env unset)"
      out+="\${$var}"
    else
      out+="$v"
    fi
  done
  out+="$rest"
  out=${out//$$/\$}
  printf '%s' "$out"
}

# manifest_brand <field>
# Echo branding.<field>. Honors ${VAR} substitution. Returns empty when
# field is absent or manifest isn't loaded.
manifest_brand() {
  manifest_have || { printf ''; return 0; }
  local field="$1" v
  v=$(printf '%s' "$__MANIFEST_RAW" | jq -r --arg f "$field" '.branding[$f] // ""')
  _manifest_substitute_vars "$v"
}

# manifest_skills_dir — echo absolute path to the skills directory
# (where SKILL.md subdirs live). Today this is always the dir containing
# the manifest itself, since the manifest lives inside skills/.
manifest_skills_dir() {
  manifest_have || { printf ''; return 0; }
  printf '%s' "$(cd "$(dirname "$__MANIFEST_PATH")" && pwd)"
}

# manifest_mcp_names <agent>
# Print MCP names that apply to <agent>, one per line.
manifest_mcp_names() {
  manifest_have || return 0
  local agent="$1"
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r --arg a "$agent" \
        '.mcpServers
         | to_entries[]
         | select(.value.agents | index($a))
         | .key'
}

# manifest_mcp_field <name> <field>
manifest_mcp_field() {
  manifest_have || { printf ''; return 0; }
  local name="$1" field="$2"
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r --arg n "$name" --arg f "$field" \
        '.mcpServers[$n][$f] // ""'
}

# manifest_mcp_args <name>
# Print MCP args (one per line) with ${VAR} substituted.
manifest_mcp_args() {
  manifest_have || return 0
  local name="$1" raw
  raw=$(printf '%s' "$__MANIFEST_RAW" \
    | jq -r --arg n "$name" '.mcpServers[$n].args[]?')
  local line
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    _manifest_substitute_vars "$line"
    printf '\n'
  done <<<"$raw"
}

# manifest_mcp_command <name>
manifest_mcp_command() {
  manifest_mcp_field "$1" command
}

# manifest_mcp_requires <name>
manifest_mcp_requires() {
  manifest_have || return 0
  local name="$1"
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r --arg n "$name" '.mcpServers[$n].requires[]? // empty'
}

# manifest_mcp_perms <name>
# Print one permission allowlist string per line.
manifest_mcp_perms() {
  manifest_have || return 0
  local name="$1"
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r --arg n "$name" '.mcpServers[$n].permissionsAllow[]? // empty'
}

# manifest_all_perms
# Print every permissionsAllow string from every MCP, deduped.
manifest_all_perms() {
  manifest_have || return 0
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r '[.mcpServers[].permissionsAllow[]?] | unique[]'
}

# manifest_pack_summary
# Echo " +pack-ver +pack-ver" for each entry in build.packs[]. Empty
# string when no packs. Used by the banner.
manifest_pack_summary() {
  manifest_have || { printf ''; return 0; }
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r '
        .build.packs // []
        | map(" +" + .name + "-" + .version)
        | join("")
      '
}

# manifest_build_version
manifest_build_version() {
  manifest_have || { printf ''; return 0; }
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r '.build.version // ""'
}

# manifest_modes
# Print one line per visible mode:
#   id|label|description|is_default
# in array order. Hidden modes (available: false) are omitted.
manifest_modes() {
  manifest_have || return 0
  printf '%s' "$__MANIFEST_RAW" \
    | jq -r '
        .branding.modes // []
        | map(select(.available != false))
        | .[]
        | "\(.id)|\(.label)|\(.description)|\(if .default then "1" else "0" end)"
      '
}

# manifest_skills
# Print the names of skills declared by the manifest.
# `skills.discovery == "auto"` means: every subdir of skills_dir that
# contains a SKILL.md is a skill. Echoes one name per line.
manifest_skills() {
  manifest_have || return 0
  local mode
  mode=$(printf '%s' "$__MANIFEST_RAW" | jq -r '.skills.discovery // "auto"')
  if [[ "$mode" != "auto" ]]; then
    log_warn "manifest: skills.discovery=$mode is not implemented; falling back to auto"
  fi
  local sdir; sdir=$(manifest_skills_dir)
  [[ -z "$sdir" || ! -d "$sdir" ]] && return 0
  local d name
  for d in "$sdir"/*/; do
    [[ -f "${d}SKILL.md" ]] || continue
    name="${d%/}"
    name="${name##*/}"
    printf '%s\n' "$name"
  done
}
