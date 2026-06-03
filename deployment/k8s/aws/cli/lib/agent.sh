#!/usr/bin/env bash
# agent.sh — agent discovery, skill installation, and exec-handoff.
#
# Supported agents (in detection order): claude, codex, kiro.
#
# Skill installation per agent:
#   claude → $STAGE_DIR/.claude/skills/opensearch-migration/Startup.md
#   codex  → $STAGE_DIR/.codex/Startup.md
#   kiro   → $STAGE_DIR/.kiro/{agents,prompts,settings,steering}/  (full upstream config)
#
# AWS MCP: all three agents get aws-mcp registered for the stage (via
# uvx mcp-proxy-for-aws@latest pointing at https://aws-mcp.us-east-1.api.aws/mcp)
# so aws___read_documentation, aws___search_documentation, etc. are
# available in the handoff session.
#
# Handoff: cd $STAGE_DIR; exec <agent-bin> with the right args (resume
# vs. fresh, agent-specific subcommand). The current migration-assistant
# process is replaced — no parent process remains.

[[ -n "${__MIGRATE_AGENT_LOADED:-}" ]] && return 0
__MIGRATE_AGENT_LOADED=1

# Defensive source: agent_setup uses manifest_have / manifest_mcp_*.
# shellcheck source=lib/manifest.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/manifest.sh"

# Each entry is "<canonical-name>:<binary1>[ <binary2>…]". The canonical
# name is what we display + persist in state. The binary list is the
# names we'll search for on PATH (kiro ships its installed binary as
# `kiro-cli`, not `kiro`).
AGENT_CANDIDATES_SPEC=(
  "claude:claude"
  "codex:codex"
  "kiro:kiro-cli kiro"
)
AGENT_CANDIDATES=(claude codex kiro)

# cmd_agent — `migration-assistant agent [<name>] [--stage <name>]`. Skip
# the resume/discover/wizard/CFN/helm flow and go straight to the agent
# handoff. Picks up the saved AGENT from state when no <name> argument
# is given. Errors clearly when the stage isn't deployed.
cmd_agent() {
  local picked="" args=("$@") i
  # shellcheck disable=SC2034  # STAGE_DIR is read by state_load / log_init
  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[$i]}" in
      --stage)    STAGE="${args[$((i + 1))]}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
      --stage=*)  STAGE="${args[$i]#--stage=}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
      --*)        : ;;
      *)          [[ -z "$picked" ]] && picked="${args[$i]}" ;;
    esac
  done
  log_init
  state_load
  if [[ -z "$(state_get STAGE_NAME "")" ]]; then
    die "no deployed stage found in state. Run \`migration-assistant\` first or pass --stage <name>."
  fi
  helm_kctx_init

  if [[ -z "$picked" ]]; then
    picked=$(state_get AGENT "")
  fi
  if [[ -z "$picked" ]]; then
    local agents_list
    agents_list=$(discover_agents) \
      || die "no supported agent CLI found on PATH"
    # shellcheck disable=SC2086
    IFS=$'\n' read -r -d '' -a agents <<<"$agents_list"$'\0' || true
    picked=$(ui_select "Which agent should drive this migration?" "${agents[@]}")
  elif [[ -z "$(_agent_bin_for "$picked")" ]]; then
    die "agent not on PATH: $picked (binary candidates: $(_agent_bins_for "$picked"))"
  fi

  state_set AGENT "$picked"
  state_set last_step "agent_handoff"
  state_save

  agent_setup "$picked"
  ui_ok "Handing off to: $picked"
  agent_exec "$picked"
}

# discover_agents — print one canonical agent name per line, for each
# agent whose binary (any of the binaries in its SPEC) is on PATH.
discover_agents() {
  local found=()
  local spec
  for spec in "${AGENT_CANDIDATES_SPEC[@]}"; do
    local canonical="${spec%%:*}"
    if [[ -n "$(_agent_bin_for "$canonical")" ]]; then
      found+=("$canonical")
    fi
  done
  if [[ ${#found[@]} -eq 0 ]]; then
    return 1
  fi
  printf '%s\n' "${found[@]}"
}

# _agent_bin_for <canonical> → echo the path of the first binary on
# PATH for the canonical agent name, or empty when none found.
_agent_bin_for() {
  local canonical="$1" spec
  for spec in "${AGENT_CANDIDATES_SPEC[@]}"; do
    if [[ "${spec%%:*}" != "$canonical" ]]; then continue; fi
    local bins="${spec#*:}" b path
    for b in $bins; do
      path=$(command -v "$b" 2>/dev/null) || continue
      if [[ -n "$path" ]]; then
        printf '%s\n' "$path"
        return 0
      fi
    done
    return 0
  done
}

# _agent_bins_for <canonical> → echo the comma-separated list of
# candidate binaries for diagnostic messages.
_agent_bins_for() {
  local canonical="$1" spec
  for spec in "${AGENT_CANDIDATES_SPEC[@]}"; do
    if [[ "${spec%%:*}" == "$canonical" ]]; then
      printf '%s\n' "${spec#*:}" | tr ' ' ','
      return 0
    fi
  done
}

agent_path() {
  ui_step "Discover installed agents"
  local agents_list
  if ! agents_list=$(discover_agents); then
    ui_err "no supported agent CLI found on PATH"
    _agent_print_install_hints
    exit 1
  fi
  # shellcheck disable=SC2086
  IFS=$'\n' read -r -d '' -a agents <<<"$agents_list"$'\0' || true

  # If the operator only has SOME of the supported agents installed,
  # surface install hints for the rest before the picker — without
  # cluttering the picker itself.
  _agent_print_install_hints_for_missing "${agents[@]}"

  local picked
  picked=$(ui_select "Which agent should drive this migration?" "${agents[@]}")
  state_set AGENT "$picked"

  ui_step "Setup agent skills"
  agent_setup "$picked"
  state_set last_step "agent_handoff"
  state_save

  ui_ok "Handing off to: $picked"
  agent_exec "$picked"
}

# _agent_print_install_hints — full install table (used when nothing is
# on PATH and we have to bail).
_agent_print_install_hints() {
  ui_info "Install one of:"
  printf '  %sclaude%s  Anthropic Claude Code   https://code.claude.com/docs/en/quickstart\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
  printf '  %scodex%s   OpenAI Codex CLI        https://developers.openai.com/codex/cli\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
  printf '  %skiro%s    AWS Kiro CLI            https://kiro.dev/cli/\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
}

# _agent_print_install_hints_for_missing <installed-agents…> — print a
# dim hint line per supported-but-not-installed agent so the operator
# knows there are other choices a `brew install` away.
_agent_print_install_hints_for_missing() {
  local installed=" $* " missing=()
  local a
  for a in "${AGENT_CANDIDATES[@]}"; do
    if [[ "$installed" != *" $a "* ]]; then
      missing+=("$a")
    fi
  done
  if (( ${#missing[@]} == 0 )); then return 0; fi
  ui_dim "  other supported agents you could install:"
  for a in "${missing[@]}"; do
    case "$a" in
      claude) ui_dim "    claude — https://code.claude.com/docs/en/quickstart" ;;
      codex)  ui_dim "    codex  — https://developers.openai.com/codex/cli" ;;
      kiro)   ui_dim "    kiro   — https://kiro.dev/cli/" ;;
    esac
  done
}

# agent_setup <agent> — write Startup.md, drop the bundled skill tree
# (every skills/<name>/SKILL.md auto-discovered, partner packs included),
# wire up agent-specific config, and register MCP servers from manifest.
# Source skill is $LIB_DIR/../skills/.
agent_setup() {
  local agent="$1"
  # Skill source resolution. In a release tarball, skills live next to
  # bin/ + lib/ at $LIB_DIR/../skills. In a repo checkout (dev mode),
  # they live at the repo root in agent-skills/skills.
  local skills_src=""
  if [[ -f "$LIB_DIR/../skills/Startup.md" ]]; then
    skills_src="$LIB_DIR/../skills"
  elif [[ -f "$LIB_DIR/../../../../../agent-skills/skills/Startup.md" ]]; then
    # cli/lib/ → cli/ → aws/ → k8s/ → deployment/ → repo-root
    skills_src="$LIB_DIR/../../../../../agent-skills/skills"
  elif [[ -n "${MIGRATE_SKILLS_SRC:-}" && -f "${MIGRATE_SKILLS_SRC}/Startup.md" ]]; then
    skills_src="$MIGRATE_SKILLS_SRC"
  else
    die "could not locate Startup.md (looked in lib/../skills + agent-skills/skills + MIGRATE_SKILLS_SRC)"
  fi

  # Drop the Startup runbook at the stage root under both names that
  # the supported agents auto-load on session start:
  #   CLAUDE.md  — Claude Code reads this from cwd at startup, no
  #                tool call needed (eliminates the trust-prompt that
  #                fires when the agent runs `find / -name Startup.md`)
  #   AGENTS.md  — Codex CLI reads this from cwd at startup
  # Startup.md is kept as the canonical filename for any future agent
  # that doesn't recognize CLAUDE.md / AGENTS.md.
  cp -f "$skills_src/Startup.md" "$STAGE_DIR/CLAUDE.md"
  cp -f "$skills_src/Startup.md" "$STAGE_DIR/AGENTS.md"
  cp -f "$skills_src/Startup.md" "$STAGE_DIR/Startup.md"

  # Skill discovery: every subdir of $skills_src that contains a SKILL.md
  # is auto-installed. Replaces the previous hard-coded list of 4 skills
  # — drop a new <skill>/SKILL.md into the bundle and it ships, no code
  # change required. This is the manifest's `skills.discovery: auto`
  # contract, implemented in shell so it works whether or not manifest.sh
  # has been initialized (test path).
  mkdir -p "$STAGE_DIR/skills"
  local skill_dir
  for skill_dir in "$skills_src"/*/; do
    [[ -f "${skill_dir}SKILL.md" ]] || continue
    cp -R "$skill_dir" "$STAGE_DIR/skills/"
  done

  local dest_dir
  case "$agent" in
    claude)
      # Claude Code's auto-loadable skills live under .claude/skills/.
      # Each subdir with a SKILL.md is detected as one skill.
      dest_dir="$STAGE_DIR/.claude/skills"
      mkdir -p "$dest_dir/opensearch-migration"
      cp -f "$skills_src/Startup.md" "$dest_dir/opensearch-migration/Startup.md"
      for skill_dir in "$skills_src"/*/; do
        [[ -f "${skill_dir}SKILL.md" ]] || continue
        cp -R "$skill_dir" "$dest_dir/"
      done
      _agent_write_claude_settings
      ;;
    codex)
      # AGENTS.md at the stage root (above) is the auto-load target;
      # also drop a copy in .codex/ for completeness.
      dest_dir="$STAGE_DIR/.codex"
      mkdir -p "$dest_dir"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"
      ;;
    kiro)
      # Drop the upstream kiro config tree (agents, prompts, settings,
      # steering) directly into .kiro/ so `kiro chat` picks them up
      # automatically. In a release tarball, kiro/ lives under
      # skills/kiro/. In a repo checkout it's a sibling to skills/
      # at agent-skills/kiro/.
      dest_dir="$STAGE_DIR/.kiro"
      mkdir -p "$dest_dir/steering"
      local kiro_src=""
      if [[ -d "$skills_src/kiro" ]]; then
        kiro_src="$skills_src/kiro"
      elif [[ -d "$skills_src/../kiro" ]]; then
        kiro_src="$skills_src/../kiro"
      fi
      if [[ -n "$kiro_src" ]]; then
        cp -R "$kiro_src/." "$dest_dir/"
      fi
      # Drop Startup.md as the FIRST steering doc so kiro auto-loads
      # the same runbook claude/codex see via CLAUDE.md / AGENTS.md.
      # Kiro reads steering/*.md alphabetically; prefix with `00-` so
      # it sorts before the other docs.
      cp -f "$skills_src/Startup.md" "$dest_dir/steering/00-Startup.md"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"

      # The migration-assistant-operator skill's workflow.md /
      # deployment.md / migration-prompt.md / product.md ARE Kiro's
      # canonical steering docs (they used to live at
      # kiro/steering/). Copy them back into .kiro/steering/ so
      # `kiro chat` auto-loads them. The source of truth lives in
      # skills/migration-assistant-operator/ so claude + codex see
      # the same docs without Kiro-specific paths.
      if [[ -d "$skills_src/migration-assistant-operator" ]]; then
        local d
        for d in workflow deployment migration-prompt product; do
          if [[ -f "$skills_src/migration-assistant-operator/${d}.md" ]]; then
            cp -f "$skills_src/migration-assistant-operator/${d}.md" \
                  "$dest_dir/steering/${d}.md"
          fi
        done
      fi
      ;;
    *)
      dest_dir="$STAGE_DIR"
      ;;
  esac

  mcp_install_from_manifest "$agent"

  log_info "agent_setup: agent=$agent skill_dir=$dest_dir"
}

# mcp_install_from_manifest <agent>
#
# Generic MCP installer driven by manifest.json's mcpServers. Replaces
# the previous _agent_install_aws_mcp + three per-agent helpers. Adding
# a new MCP is a one-line edit to manifest.json — no shell change, no
# fork of the CLI.
#
# Per-agent project-scope writes (avoids the stale-user-scope-cache bug
# where claude mcp add-json --scope user thought aws-mcp was registered
# but the binary couldn't reach it):
#   claude → $STAGE_DIR/.mcp.json     (claude reads project-scope MCPs)
#   codex  → $STAGE_DIR/.codex/config.toml  (CODEX_HOME=$STAGE_DIR/.codex)
#   kiro   → $STAGE_DIR/.kiro/settings/mcp.json
#
# Bypass:
#   MIGRATE_SKIP_MCP=1                  → skip every MCP
#   MIGRATE_SKIP_MCP_<NAME_NORMED>=1    → skip a specific MCP by name
#                                          (NAME_NORMED is uppercase + - → _)
mcp_install_from_manifest() {
  local agent="$1"
  if [[ "${MIGRATE_SKIP_MCP:-0}" -eq 1 ]]; then
    ui_dim "  MIGRATE_SKIP_MCP=1 → skipping all MCP registration"
    return 0
  fi
  if ! manifest_have; then
    log_warn "mcp: no manifest loaded; skipping MCP registration"
    return 0
  fi

  # Resolve required binaries up front. One uvx prompt covers N MCPs
  # that all need uvx.
  local req
  while IFS= read -r req; do
    [[ -z "$req" ]] && continue
    case "$req" in
      uvx) _agent_ensure_uvx || die "MCP registration requires uvx; rerun with MIGRATE_SKIP_MCP=1 to opt out" ;;
      *)
        if ! optional_cmd "$req"; then
          die "MCP registration requires '$req' on PATH; install it or rerun with MIGRATE_SKIP_MCP=1"
        fi
        ;;
    esac
  done < <(printf '%s' "$__MANIFEST_RAW" | jq -r '[.mcpServers[].requires[]?] | unique[]')

  # Iterate each MCP that targets this agent.
  local mcp skip_var
  while IFS= read -r mcp; do
    [[ -z "$mcp" ]] && continue
    skip_var="MIGRATE_SKIP_MCP_$(_mcp_norm_envvar "$mcp")"
    if [[ "${!skip_var:-0}" == "1" ]]; then
      ui_dim "  $skip_var=1 → skipping $mcp for $agent"
      continue
    fi
    _mcp_register_for_agent "$agent" "$mcp"
  done < <(manifest_mcp_names "$agent")

  # Claude only: merge permissions from the manifest into the
  # project-scope settings.json. (The CLI's own bash/aws/kubectl
  # allowlist is contributed by _agent_write_claude_settings.)
  [[ "$agent" == "claude" ]] && _agent_write_claude_settings
}

# _mcp_norm_envvar <name>
# Normalize an MCP name into an uppercase env-var-safe suffix.
# aws-mcp → AWS_MCP, my.org/secrets → MY_ORG_SECRETS.
_mcp_norm_envvar() {
  local s="$1"
  # tr is one fork; doing this in pure bash via case-loop is bash 4+
  # (${var^^}) so we accept the fork.
  printf '%s' "$s" | tr '[:lower:]/-.' '[:upper:]___'
}

# _mcp_register_for_agent <agent> <mcp_name>
# Dispatch to the per-agent project-scope writer.
_mcp_register_for_agent() {
  local agent="$1" name="$2"
  case "$agent" in
    claude) _mcp_write_claude "$name" ;;
    codex)  _mcp_write_codex  "$name" ;;
    kiro)   _mcp_write_kiro   "$name" ;;
    *)      log_warn "mcp: no project-scope writer for agent=$agent; skipping $name" ;;
  esac
}

# _mcp_write_claude <name>
# Project-scope: $STAGE_DIR/.mcp.json. Claude Code auto-loads project
# MCPs on first session (with a one-time trust prompt per stage), which
# is the right safety prompt for an operator running a custom build.
_mcp_write_claude() {
  local name="$1"
  local cfg="$STAGE_DIR/.mcp.json"
  if [[ -f "$cfg" ]] && jq -e --arg n "$name" '.mcpServers[$n] // empty' "$cfg" >/dev/null 2>&1; then
    ui_dim "  claude: $name already in $(_mcp_pretty "$cfg")"
    return 0
  fi
  ui_step "Registering $name with claude ($cfg)"

  # Build the entry as a jq fragment.
  local cmd; cmd=$(manifest_mcp_command "$name")
  local args_array; args_array=$(_mcp_args_jq_array "$name")
  local entry
  entry=$(jq -nc --arg cmd "$cmd" --argjson args "$args_array" \
    '{command: $cmd, args: $args}')

  local tmp; tmp=$(mktemp "$STAGE_DIR/.mcp.json.tmp.XXXXXX")
  if [[ -f "$cfg" ]]; then
    jq --arg n "$name" --argjson e "$entry" '.mcpServers[$n] = $e' "$cfg" >"$tmp"
  else
    jq -nc --arg n "$name" --argjson e "$entry" '{mcpServers: {($n): $e}}' >"$tmp"
  fi
  mv -f "$tmp" "$cfg"
  ui_ok "$name registered with claude"
}

# _mcp_write_codex <name>
# Project-scope: $STAGE_DIR/.codex/config.toml. Set CODEX_HOME at
# exec time so codex resolves its config from this stage rather than
# the user-global ~/.codex.
_mcp_write_codex() {
  local name="$1"
  local cfg_dir="$STAGE_DIR/.codex"
  local cfg="$cfg_dir/config.toml"
  mkdir -p "$cfg_dir"
  touch "$cfg"
  if grep -qE "^\\[mcp_servers\\.${name}\\]" "$cfg" 2>/dev/null; then
    ui_dim "  codex: $name already in $cfg"
    return 0
  fi
  ui_step "Registering $name with codex ($cfg)"

  local cmd; cmd=$(manifest_mcp_command "$name")
  local args_array; args_array=$(_mcp_args_jq_array "$name")
  # Convert JSON args array → TOML array literal.
  local args_toml
  args_toml=$(printf '%s' "$args_array" | jq -r '
    map("\"" + (. | gsub("\\\\"; "\\\\") | gsub("\""; "\\\"")) + "\"")
    | "[" + join(", ") + "]"
  ')
  {
    printf '\n[mcp_servers.%s]\n' "$name"
    printf 'command = "%s"\n' "$cmd"
    printf 'args = %s\n' "$args_toml"
  } >>"$cfg"
  ui_ok "$name registered with codex"
}

# _mcp_write_kiro <name>
# Project-scope: $STAGE_DIR/.kiro/settings/mcp.json. Already project-
# scoped pre-refactor; this rewrite generalizes to any name.
_mcp_write_kiro() {
  local name="$1"
  local cfg="$STAGE_DIR/.kiro/settings/mcp.json"
  mkdir -p "$(dirname "$cfg")"
  if [[ -f "$cfg" ]] && jq -e --arg n "$name" '.mcpServers[$n] // empty' "$cfg" >/dev/null 2>&1; then
    ui_dim "  kiro: $name already in $cfg"
    return 0
  fi
  ui_step "Registering $name with kiro ($cfg)"

  local cmd; cmd=$(manifest_mcp_command "$name")
  local args_array; args_array=$(_mcp_args_jq_array "$name")
  local entry
  entry=$(jq -nc --arg cmd "$cmd" --argjson args "$args_array" \
    '{command: $cmd, args: $args}')

  local tmp; tmp=$(mktemp "$STAGE_DIR/.kiro/settings/mcp.json.tmp.XXXXXX")
  if [[ -f "$cfg" ]]; then
    jq --arg n "$name" --argjson e "$entry" '.mcpServers[$n] = $e' "$cfg" >"$tmp"
  else
    jq -nc --arg n "$name" --argjson e "$entry" '{mcpServers: {($n): $e}}' >"$tmp"
  fi
  mv -f "$tmp" "$cfg"
  ui_ok "$name registered with kiro"
}

# _mcp_args_jq_array <name>
# Echo the substituted args list as a JSON array, suitable for `--argjson`
# injection in jq. Reads from manifest_mcp_args (one substituted arg per
# line) and folds into a JSON array.
_mcp_args_jq_array() {
  local name="$1"
  manifest_mcp_args "$name" | jq -R . | jq -sc .
}

# _mcp_pretty <path>
# Echo a path relative to $HOME for log messages.
# shellcheck disable=SC2088 # the literal `~/` below is display text, not shell expansion
_mcp_pretty() {
  local p="$1"
  case "$p" in
    "$HOME"/*) printf '~/%s' "${p#"$HOME"/}" ;;
    *)         printf '%s' "$p" ;;
  esac
}

# _agent_write_claude_settings — drop a project-scope .claude/settings.json
# at $STAGE_DIR with pre-approved permissions. The MCP-tool permissions
# come from each MCP's permissionsAllow in manifest.json (so packs that
# add new MCPs get their tools auto-allowed). The bash/aws/kubectl
# read-only allowlist comes from this file — it's CLI-specific, not
# MCP-specific, so it doesn't belong in the manifest.
#
# Skipped when MIGRATE_NO_CLAUDE_SETTINGS=1.
_agent_write_claude_settings() {
  if [[ "${MIGRATE_NO_CLAUDE_SETTINGS:-0}" -eq 1 ]]; then
    return 0
  fi
  local settings="$STAGE_DIR/.claude/settings.json"
  mkdir -p "$STAGE_DIR/.claude"

  # Idempotent: don't clobber an existing settings.json (operator may
  # have customized it). Just announce that we left it alone.
  if [[ -f "$settings" ]]; then
    log_info "claude: $settings already exists; not overwriting"
    return 0
  fi

  # The CLI's read-only operator allowlist (NOT MCP-specific).
  local cli_perms_json
  cli_perms_json=$(jq -nc '[
    "Bash(aws sts get-caller-identity:*)",
    "Bash(aws eks list-clusters:*)",
    "Bash(aws eks describe-cluster:*)",
    "Bash(aws cloudformation list-stacks:*)",
    "Bash(aws cloudformation describe-stacks:*)",
    "Bash(aws cloudformation describe-stack-events:*)",
    "Bash(aws cloudformation describe-stack-resources:*)",
    "Bash(aws ecr describe-repositories:*)",
    "Bash(aws ecr list-images:*)",
    "Bash(aws s3 ls:*)",
    "Bash(aws iam get-role:*)",
    "Bash(aws iam list-attached-role-policies:*)",
    "Bash(kubectl get:*)",
    "Bash(kubectl describe:*)",
    "Bash(kubectl logs:*)",
    "Bash(kubectl wait:*)",
    "Bash(kubectl version:*)",
    "Bash(kubectl config current-context:*)",
    "Bash(kubectl config get-contexts:*)",
    "Bash(helm status:*)",
    "Bash(helm list:*)",
    "Bash(helm history:*)",
    "Bash(helm get values:*)",
    "Bash(cat:*)", "Bash(ls:*)", "Bash(jq:*)", "Bash(grep:*)", "Bash(rg:*)",
    "Bash(find:*)", "Bash(head:*)", "Bash(tail:*)", "Bash(wc:*)",
    "Read(*)", "Glob(*)", "Grep(*)",
    "Write(*)", "Edit(*)", "MultiEdit(*)", "NotebookEdit(*)",
    "Bash(mkdir:*)", "Bash(touch:*)", "Bash(cp:*)", "Bash(mv:*)"
  ]')

  # Manifest-driven MCP tool perms. May be empty (no MCPs declared
  # any).
  local mcp_perms_json='[]'
  if manifest_have; then
    mcp_perms_json=$(printf '%s' "$__MANIFEST_RAW" \
      | jq '[.mcpServers[].permissionsAllow[]?] | unique')
  fi

  # Merge + emit.
  jq -n --argjson mcp "$mcp_perms_json" --argjson cli "$cli_perms_json" '
    {
      "$schema": "https://json.schemastore.org/claude-code-settings.json",
      permissions: { allow: ($mcp + $cli | unique) }
    }
  ' >"$settings"
  log_info "claude: wrote pre-approved permissions to $settings ($(jq '.permissions.allow|length' "$settings") entries)"
}

# _agent_ensure_uvx — uvx is required for AWS MCP registration. If it's
# missing, offer to run astral's official installer; bail with a clear
# error only when the operator declines or the install fails.
#
# Returns 0 if uvx is on PATH afterwards, 1 otherwise.
_agent_ensure_uvx() {
  if command -v uvx >/dev/null 2>&1; then
    return 0
  fi
  ui_warn "uvx is required for AWS MCP registration but is not on PATH"
  ui_dim  "  uv (the package manager that ships uvx) is a single-binary"
  ui_dim  "  install — astral's official one-liner takes ~3 seconds and"
  ui_dim  "  drops it at \$HOME/.local/bin/uvx (no sudo, no system changes)."
  if ! ui_confirm "Install uv now via astral's official installer?" "Y"; then
    ui_err "aws-mcp registration aborted; install uv yourself: https://github.com/astral-sh/uv"
    return 1
  fi

  ui_step "Installing uv (curl -LsSf https://astral.sh/uv/install.sh | sh)"
  if ! curl -LsSf --max-time 60 https://astral.sh/uv/install.sh | sh >>"$LOG_FILE" 2>&1; then
    ui_err "uv install failed; see $LOG_FILE"
    return 1
  fi

  # uv installs to ~/.local/bin (already on PATH for any shell where the
  # operator could install via curl|bash). bash caches command lookups,
  # so hash -r is the only thing needed before the next command -v probe.
  hash -r 2>/dev/null || true
  if ! command -v uvx >/dev/null 2>&1; then
    ui_err "uv installed but uvx still not on PATH; add \$HOME/.local/bin to your PATH and rerun"
    return 1
  fi

  ui_ok "uv installed: $(uvx --version 2>&1 | head -1)"
  return 0
}

# Old _agent_mcp_claude / _agent_mcp_codex / _agent_mcp_kiro have been
# replaced by mcp_install_from_manifest + _mcp_write_{claude,codex,kiro}.
# The new path is project-scope (avoids the stale-user-cache class of
# claude bug) and manifest-driven (a pack adds an MCP via JSON edit, no
# fork required).

# agent_exec <agent> — replace this process with the agent. Never returns.
#
# Tries the agent's native session-resume flag when state indicates the
# operator already started a session in this $STAGE_DIR. The agent's
# own resume picks up the conversation in ~1s instead of re-reading
# Startup.md cold for ~25s.
#
#   claude --continue      → resume the latest claude session
#   codex resume --last    → resume the latest codex session
#   kiro chat --agent opensearch-migration --resume
#
# When state has no prior agent_handoff, fall through to the
# fresh-session command form with a Startup.md instruction.
agent_exec() {
  local agent="$1"
  local bin
  bin=$(_agent_bin_for "$agent")
  if [[ -z "$bin" ]]; then
    die "agent not on PATH: $agent (binary candidates: $(_agent_bins_for "$agent"))"
  fi
  cd "$STAGE_DIR" || die "could not enter stage dir: $STAGE_DIR"

  ui_info "Working directory at handoff:"
  printf '  %s\n' "$STAGE_DIR"
  ui_info "AWS identity at handoff:"
  aws sts get-caller-identity --output table 2>/dev/null \
    || ui_warn "could not call sts:GetCallerIdentity"

  # Resume only when state shows we already handed off AND the agent's
  # own session storage has a conversation rooted at THIS working
  # directory. claude --continue / codex resume both exit 0 even when
  # they print "No conversation found", so we can't trust their rc;
  # check the session storage up front instead.
  local resuming=0
  if [[ "$(state_get last_step '')" == "agent_handoff" ]] \
     && _agent_has_session "$agent"; then
    resuming=1
  fi

  # Agent fresh-handoff prompt: comes from manifest.branding.agentFreshPrompt
  # if the bundle declares one (so packs can fully customize the agent's
  # first impression), otherwise the upstream default below.
  local fresh_prompt; fresh_prompt=$(manifest_brand agentFreshPrompt)
  if [[ -z "$fresh_prompt" ]]; then
    fresh_prompt='Greet the operator. Briefly (3-5 sentences max) introduce what you can help with — deploy + operate Migration Assistant, run a Solr/ES/OpenSearch migration end-to-end. Suggest 3-4 example asks (e.g. "Deploy a fresh Migration Assistant in us-east-1", "Continue a deploy that was Ctrl-C'\''d", "Move data from my Solr cluster to OpenSearch Serverless"). DO NOT run any tools yet — no Read of state.env, no Bash, no kubectl. Wait for the operator to tell you what they want. The CLAUDE.md / AGENTS.md in this directory has been auto-loaded; you already know the rules. Be concise and friendly.'
  fi
  ui_dim "  exec $bin (resume=$resuming)"
  case "$agent" in
    claude)
      if (( resuming )); then exec "$bin" --continue
      else exec "$bin" "$fresh_prompt"
      fi ;;
    codex)
      # Set CODEX_HOME to the per-stage project-scope config dir so codex
      # picks up the MCP servers we wrote there. Without this, codex
      # reads ~/.codex/config.toml globally and sees only the user's
      # personal MCPs (or none).
      export CODEX_HOME="$STAGE_DIR/.codex"
      if (( resuming )); then exec "$bin" resume --last
      else exec "$bin" "$fresh_prompt"
      fi ;;
    kiro)
      if (( resuming )); then exec "$bin" chat --agent opensearch-migration --resume
      else exec "$bin" chat --agent opensearch-migration "$fresh_prompt"
      fi ;;
    *)
      die "unsupported agent: $agent"
      ;;
  esac
}

# _agent_has_session <agent> → exit 0 if the agent's session storage
# has at least one conversation rooted in our current working
# directory (which agent_exec set to $STAGE_DIR).
#
# Each agent stores sessions differently:
#   claude  → ~/.claude/projects/<cwd-with-slash-replaced-by-dash>/*.jsonl
#   codex   → ~/.codex/sessions/ has globally-keyed jsonl; codex
#             resolves cwd internally. We optimistically return 0 here
#             — codex's `resume --last` is harmless on no-history
#             (just shows a picker that auto-exits).
#   q       → no public session file format; assume yes.
#   kiro    → ~/.kiro/conversations/ — same pragma as q; assume yes.
_agent_has_session() {
  local agent="$1"
  case "$agent" in
    claude)
      # Claude's session dir: replace `/` and `.` with `-` in the CWD.
      local key="${PWD//\//-}"
      key="${key//./-}"
      local dir="$HOME/.claude/projects/$key"
      [[ -d "$dir" ]] || return 1
      # At least one .jsonl file present?
      compgen -G "$dir/*.jsonl" >/dev/null 2>&1
      ;;
    *)
      return 0
      ;;
  esac
}
