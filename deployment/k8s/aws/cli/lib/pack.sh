#!/usr/bin/env bash
# pack.sh — repack a CLI tarball with new skills, MCPs, and branding.
#
# Operators who maintain a custom build of the CLI run:
#
#   migration-assistant pack \
#     --input migration-assistant-cli-3.2.1.tar.gz \
#     --add-skill ./my-runbook \
#     --add-mcp ./my-secrets-mcp.json \
#     --branding ./myorg.json \
#     --pack-name myorg-internal \
#     --pack-version 1.0.0 \
#     --output myorg-migrate-3.2.1+myorg-1.0.0.tar.gz
#
# Behavior:
#   1. Extract the input tarball into a tempdir.
#   2. For each --add-skill <dir>: validate it has SKILL.md, copy under
#      skills/.
#   3. For each --add-mcp <fragment.json>: validate it parses, deep-merge
#      into manifest.json's mcpServers. Last-write-wins on collisions
#      (logged as a WARN at repack time so the operator sees the override).
#   4. If --branding <file>: deep-merge into manifest.branding. modes[]
#      is REPLACED (not merged) when present in the input.
#   5. Append a build.packs[] entry recording the pack identity +
#      additions + timestamp + brandingChanged flag.
#   6. Re-tar to --output. The top-level tar dir is renamed to match
#      branding.binaryName when set.
#
# pack is read-only against the operator's deployed environment — no
# AWS calls, no state writes. Runs anywhere with bash + jq + tar.

[[ -n "${__MIGRATE_PACK_LOADED:-}" ]] && return 0
__MIGRATE_PACK_LOADED=1

# shellcheck source=lib/std.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/std.sh"

# cmd_pack <flags…> — the `migration-assistant pack` entry point.
cmd_pack() {
  local input="" output=""
  local pack_name="" pack_version=""
  local branding_file=""
  local add_skills=() add_mcps=()
  local brand_name="" brand_binary="" brand_tagline=""
  local mode_default="" mode_order=""
  local strict=0

  while (( $# > 0 )); do
    case "$1" in
      --input)         input="$2";          shift 2 ;;
      --input=*)       input="${1#--input=}"; shift ;;
      --output)        output="$2";         shift 2 ;;
      --output=*)      output="${1#--output=}"; shift ;;
      --pack-name)     pack_name="$2";      shift 2 ;;
      --pack-name=*)   pack_name="${1#--pack-name=}"; shift ;;
      --pack-version)  pack_version="$2";   shift 2 ;;
      --pack-version=*) pack_version="${1#--pack-version=}"; shift ;;
      --add-skill)     add_skills+=("$2");  shift 2 ;;
      --add-skill=*)   add_skills+=("${1#--add-skill=}"); shift ;;
      --add-mcp)       add_mcps+=("$2");    shift 2 ;;
      --add-mcp=*)     add_mcps+=("${1#--add-mcp=}"); shift ;;
      --branding)      branding_file="$2";  shift 2 ;;
      --branding=*)    branding_file="${1#--branding=}"; shift ;;
      --brand-name)    brand_name="$2";     shift 2 ;;
      --brand-name=*)  brand_name="${1#--brand-name=}"; shift ;;
      --brand-binary)  brand_binary="$2";   shift 2 ;;
      --brand-binary=*) brand_binary="${1#--brand-binary=}"; shift ;;
      --brand-tagline) brand_tagline="$2";  shift 2 ;;
      --brand-tagline=*) brand_tagline="${1#--brand-tagline=}"; shift ;;
      --mode-default)  mode_default="$2";   shift 2 ;;
      --mode-default=*) mode_default="${1#--mode-default=}"; shift ;;
      --mode-order)    mode_order="$2";     shift 2 ;;
      --mode-order=*)  mode_order="${1#--mode-order=}"; shift ;;
      --strict)        strict=1;            shift ;;
      -h|--help)       _pack_print_help; return 0 ;;
      *) die "pack: unknown flag: $1 (try --help)" ;;
    esac
  done

  [[ -z "$input" ]]        && die "pack: --input <tarball> is required"
  [[ -z "$output" ]]       && die "pack: --output <path> is required"
  [[ -f "$input" ]]        || die "pack: --input not found: $input"
  [[ -z "$pack_name" ]]    && die "pack: --pack-name <name> is required"
  [[ -z "$pack_version" ]] && die "pack: --pack-version <ver> is required"

  optional_cmd jq  || die "pack: jq required on PATH"
  optional_cmd tar || die "pack: tar required on PATH"

  ui_step "Repacking: $input → $output"
  ui_dim  "  pack: ${pack_name}-${pack_version}"

  local stage; stage=$(mktemp -d)
  # shellcheck disable=SC2064 # $stage is the temp path captured by the trap
  trap "rm -rf '$stage'" RETURN

  # 1. Extract.
  if ! tar -xzf "$input" -C "$stage" 2>/dev/null; then
    die "pack: could not extract $input"
  fi
  local root
  root=$(_pack_resolve_root "$stage") \
    || die "pack: input tarball does not look like a CLI bundle (no manifest.json found)"
  ui_dim "  staged: $root"

  local manifest="$root/manifest.json"
  [[ -f "$manifest" ]] || die "pack: input has no manifest.json — is this a v1 bundle?"

  # 2. Add skills.
  local skill_dir
  for skill_dir in "${add_skills[@]+"${add_skills[@]}"}"; do
    _pack_add_skill "$root" "$skill_dir"
  done

  # 3. Add MCPs.
  local mcp_file
  for mcp_file in "${add_mcps[@]+"${add_mcps[@]}"}"; do
    _pack_add_mcp "$manifest" "$mcp_file"
  done

  # 4. Branding.
  local branding_changed=0
  if [[ -n "$branding_file" ]]; then
    [[ -f "$branding_file" ]] || die "pack: --branding file not found: $branding_file"
    _pack_apply_branding "$manifest" "$branding_file"
    branding_changed=1
  fi
  if [[ -n "$brand_name$brand_binary$brand_tagline$mode_default$mode_order" ]]; then
    _pack_apply_brand_flags "$manifest" \
      "$brand_name" "$brand_binary" "$brand_tagline" \
      "$mode_default" "$mode_order"
    branding_changed=1
  fi

  # 5. Validate the result.
  _pack_validate "$manifest" "$strict" || return 1

  # 6. Append the pack entry.
  _pack_append_entry "$manifest" "$pack_name" "$pack_version" \
    "${add_skills[*]+"${add_skills[*]}"}" \
    "${add_mcps[*]+"${add_mcps[*]}"}" \
    "$branding_changed"

  # 7. Re-tar. If branding.binaryName changed, also rename the top-level
  # dir so the tarball self-describes.
  local bin_name
  bin_name=$(jq -r '.branding.binaryName // "migration-assistant"' "$manifest")
  local new_root_basename
  new_root_basename=$(basename "$root")
  case "$new_root_basename" in
    migration-assistant-cli-*)
      if [[ "$bin_name" != "migration-assistant" ]]; then
        new_root_basename="${bin_name}-cli-${new_root_basename#migration-assistant-cli-}"
      fi
      ;;
  esac
  # Snapshot summary numbers BEFORE the rename — $manifest is the
  # pre-rename path; computing here keeps it valid.
  local n_skills n_mcps n_packs
  n_skills=$(find "$root/skills" -name SKILL.md 2>/dev/null | wc -l | tr -d ' ')
  n_mcps=$(jq -r '.mcpServers | length' "$manifest")
  n_packs=$(jq -r '.build.packs | length' "$manifest")

  if [[ "$new_root_basename" != "$(basename "$root")" ]]; then
    mv "$root" "$stage/$new_root_basename"
    root="$stage/$new_root_basename"
  fi

  mkdir -p "$(dirname "$output")"
  if ! tar -czf "$output" -C "$stage" "$new_root_basename" 2>/dev/null; then
    die "pack: tar -czf $output failed"
  fi

  # 8. Print summary.
  ui_ok "wrote $output"
  ui_dim "  skills: $n_skills"
  ui_dim "  MCPs:   $n_mcps"
  ui_dim "  binary: $bin_name"
  ui_dim "  packs:  $n_packs"
}

# _pack_resolve_root <stage_dir> — echo the unique tarball-root
# subdirectory under stage. Tarballs ship with a single top-level dir
# (migration-assistant-cli-<ver> or similar). Returns non-zero if there
# isn't exactly one.
_pack_resolve_root() {
  local stage="$1"
  local candidates=()
  local d
  for d in "$stage"/*/; do
    [[ -d "$d" ]] || continue
    if [[ -f "${d}manifest.json" ]] || [[ -f "${d}skills/manifest.json" ]] || [[ -d "${d}lib" ]]; then
      candidates+=("${d%/}")
    fi
  done
  if (( ${#candidates[@]} == 1 )); then
    printf '%s' "${candidates[0]}"
    return 0
  fi
  return 1
}

# _pack_add_skill <root> <skill_dir>
_pack_add_skill() {
  local root="$1" skill_dir="$2"
  [[ -d "$skill_dir" ]] || die "pack: --add-skill not a directory: $skill_dir"
  [[ -f "$skill_dir/SKILL.md" ]] || die "pack: --add-skill has no SKILL.md: $skill_dir"
  local name; name=$(basename "$skill_dir")
  if [[ -d "$root/skills/$name" ]]; then
    log_warn "pack: skill '$name' already in bundle — overwriting"
  fi
  cp -R "$skill_dir" "$root/skills/"
  ui_dim "  +skill: $name"
}

# _pack_add_mcp <manifest> <fragment_json>
# The fragment is an object whose keys become mcpServers entries.
# Example:
#   { "myorg-secrets-mcp": { "command": "uvx", "args": [...], "scope": "project",
#                            "agents": ["claude"], "requires": ["uvx"] } }
_pack_add_mcp() {
  local manifest="$1" frag="$2"
  [[ -f "$frag" ]] || die "pack: --add-mcp file not found: $frag"
  if ! jq -e . "$frag" >/dev/null 2>&1; then
    die "pack: --add-mcp file is not valid JSON: $frag"
  fi
  # Warn on each name collision so the operator sees the override.
  local existing
  existing=$(jq -r --slurpfile frag "$frag" '
    .mcpServers as $cur
    | [$frag[0] | keys[]]
    | map(select(. as $k | $cur | has($k)))
    | .[]
  ' "$manifest" 2>/dev/null)
  if [[ -n "$existing" ]]; then
    local n
    while IFS= read -r n; do
      log_warn "pack: MCP '$n' already in bundle — replacing"
    done <<<"$existing"
  fi
  local tmp; tmp=$(mktemp)
  jq --slurpfile frag "$frag" '.mcpServers += $frag[0]' "$manifest" >"$tmp"
  mv -f "$tmp" "$manifest"
  ui_dim "  +mcp:   $(jq -r 'keys|join(", ")' "$frag")"
}

# _pack_apply_branding <manifest> <branding_json>
# Deep-merges the file into manifest.branding. modes[] is REPLACED when
# the input declares it (array order matters for picker).
_pack_apply_branding() {
  local manifest="$1" frag="$2"
  if ! jq -e . "$frag" >/dev/null 2>&1; then
    die "pack: --branding file is not valid JSON: $frag"
  fi
  local tmp; tmp=$(mktemp)
  jq --slurpfile frag "$frag" '
    .branding = (.branding * $frag[0])
    | if ($frag[0].modes // null) != null then .branding.modes = $frag[0].modes else . end
  ' "$manifest" >"$tmp"
  mv -f "$tmp" "$manifest"
  ui_dim "  +branding: $(jq -r 'keys|join(", ")' "$frag")"
}

# _pack_apply_brand_flags <manifest> <name> <binary> <tagline> <mode_default> <mode_order>
# Convenience flags: each is optional. mode_order is comma-separated;
# the manifest's existing modes are reordered to match.
_pack_apply_brand_flags() {
  local manifest="$1" name="$2" binary="$3" tagline="$4" mode_default="$5" mode_order="$6"
  local tmp; tmp=$(mktemp)
  jq \
    --arg name "$name" --arg binary "$binary" --arg tagline "$tagline" \
    --arg default_id "$mode_default" --arg order "$mode_order" \
    '
    if $name    != "" then .branding.appName     = $name    else . end
    | if $binary  != "" then .branding.binaryName  = $binary  else . end
    | if $tagline != "" then .branding.tagline     = $tagline else . end
    | if $default_id != "" then
        .branding.modes = (.branding.modes // [] | map(.default = (.id == $default_id)))
      else . end
    | if $order != "" then
        ($order | split(",")) as $ord
        | (.branding.modes // []) as $cur_modes
        | .branding.modes = (
            $ord
            | map(. as $id | $cur_modes[] | select(.id == $id))
          )
      else . end
    ' "$manifest" >"$tmp"
  mv -f "$tmp" "$manifest"
  ui_dim "  +brand-flags applied"
}

# _pack_validate <manifest> <strict>
# Sanity-check the post-merge manifest. With --strict, warnings escalate
# to errors.
_pack_validate() {
  local manifest="$1" strict="$2"
  local errs=0

  # Exactly one mode is default.
  local default_count
  default_count=$(jq -r '[.branding.modes[]? | select(.default == true)] | length' "$manifest")
  if [[ "$default_count" != "1" ]]; then
    log_warn "pack: $default_count modes have default:true (expected exactly 1)"
    (( strict )) && errs=$((errs + 1))
  fi

  # No duplicate mode ids.
  local dups
  dups=$(jq -r '[.branding.modes[]?.id] | group_by(.) | map(select(length > 1) | .[0]) | .[]' "$manifest")
  if [[ -n "$dups" ]]; then
    log_warn "pack: duplicate mode id(s): $dups"
    errs=$((errs + 1))
  fi

  # Each mode id is one of the known dispatch targets in resume.sh.
  # Manual + Agent are the only handlers today.
  local unknown
  unknown=$(jq -r '
    [.branding.modes[]? | select(.available != false) | .id]
    | map(select(. != "Manual" and . != "Agent"))
    | .[]?' "$manifest")
  if [[ -n "$unknown" ]]; then
    log_warn "pack: unknown mode id(s) [$unknown] — resume.sh has no handler; runtime will die"
    (( strict )) && errs=$((errs + 1))
  fi

  # binaryName is sane.
  local bin_name; bin_name=$(jq -r '.branding.binaryName // "migration-assistant"' "$manifest")
  if [[ ! "$bin_name" =~ ^[a-z][a-z0-9-]*$ ]]; then
    log_warn "pack: binaryName='$bin_name' should match [a-z][a-z0-9-]*"
    (( strict )) && errs=$((errs + 1))
  fi

  # Every MCP entry has the required fields.
  local malformed
  malformed=$(jq -r '
    .mcpServers
    | to_entries
    | map(select(
        (.value.command | type) != "string"
        or (.value.args    | type) != "array"
        or (.value.agents  | type) != "array"
      ))
    | .[].key' "$manifest")
  if [[ -n "$malformed" ]]; then
    log_warn "pack: malformed MCP entries (need command, args, agents): $malformed"
    errs=$((errs + 1))
  fi

  if (( errs > 0 )); then
    die "pack: validation failed with $errs error(s) (rerun without --strict for warnings only)"
  fi
  return 0
}

# _pack_append_entry <manifest> <name> <ver> <skills_csv> <mcps_csv> <branding_changed>
_pack_append_entry() {
  local manifest="$1" name="$2" ver="$3" skills_csv="$4" mcps_csv="$5" branding_changed="$6"
  # Compute added skill names (basename of each --add-skill path) and
  # added MCP names (top-level keys of each --add-mcp file). Both are
  # space-separated input strings here.
  local skills_json='[]' mcps_json='[]'
  if [[ -n "$skills_csv" ]]; then
    # shellcheck disable=SC2086 # word-split on space — caller passes a space-joined list
    skills_json=$(printf '%s\n' $skills_csv \
      | xargs -n1 basename 2>/dev/null \
      | jq -R . | jq -sc .)
  fi
  if [[ -n "$mcps_csv" ]]; then
    local f names=()
    # shellcheck disable=SC2086 # word-split on space — caller passes a space-joined list
    for f in $mcps_csv; do
      [[ -f "$f" ]] || continue
      while IFS= read -r n; do
        [[ -n "$n" ]] && names+=("$n")
      done < <(jq -r 'keys[]' "$f")
    done
    if (( ${#names[@]} > 0 )); then
      mcps_json=$(printf '%s\n' "${names[@]}" | jq -R . | jq -sc .)
    fi
  fi

  # Use a deterministic timestamp source. `date -u +…` is portable; we
  # pin to ISO-8601 UTC.
  local ts; ts=$(date -u +%FT%TZ)

  local tmp; tmp=$(mktemp)
  jq --arg n "$name" --arg v "$ver" --arg ts "$ts" \
     --argjson skills "$skills_json" --argjson mcps "$mcps_json" \
     --argjson bc "$branding_changed" \
    '.build.packs += [{
       name: $n, version: $v, appliedAt: $ts,
       addedSkills: $skills, addedMcpServers: $mcps,
       brandingChanged: ($bc == 1)
     }]' "$manifest" >"$tmp"
  mv -f "$tmp" "$manifest"
}

_pack_print_help() {
  cat <<'EOF'
migration-assistant pack — repack a CLI tarball with new skills + MCPs + branding.

Usage:
  migration-assistant pack [flags]

Required:
  --input <path>          Source tarball (an existing CLI release).
  --output <path>         Target tarball.
  --pack-name <name>      Identifier recorded in build.packs[].
  --pack-version <ver>    Version recorded with the pack.

Optional content:
  --add-skill <dir>       Add a skill directory (must contain SKILL.md).
                          Repeatable.
  --add-mcp <file.json>   Add MCP definitions from a JSON object whose
                          keys become mcpServers entries. Repeatable.
  --branding <file.json>  Deep-merge a branding fragment into manifest.

Optional convenience flags:
  --brand-name <name>     Set branding.appName.
  --brand-binary <name>   Set branding.binaryName.
  --brand-tagline <text>  Set branding.tagline.
  --mode-default <id>     Pick a single mode id (Manual|Agent) as default.
  --mode-order id1,id2    Reorder branding.modes[].

Behavior:
  --strict                Treat validation warnings as errors.

Output tarball naming:
  When branding.binaryName changes from migration-assistant, the
  top-level tar dir is renamed (myorg-migrate-cli-3.2.1).
EOF
}
