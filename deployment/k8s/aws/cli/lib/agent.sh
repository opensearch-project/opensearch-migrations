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
  local ns; ns=$(state_get STAGE_NAME "")
  if [[ -z "$ns" ]]; then
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
  elif ! command -v "$picked" >/dev/null 2>&1; then
    die "agent not on PATH: $picked"
  fi

  state_set AGENT "$picked"
  state_set last_step "agent_handoff"
  state_save

  agent_setup "$picked"
  ui_ok "Handing off to: $picked"
  agent_exec "$picked"
}

# discover_agents — print one agent per line of those found on PATH.
discover_agents() {
  local found=()
  local a
  for a in "${AGENT_CANDIDATES[@]}"; do
    if command -v "$a" >/dev/null 2>&1; then
      found+=("$a")
    fi
  done
  if [[ ${#found[@]} -eq 0 ]]; then
    return 1
  fi
  printf '%s\n' "${found[@]}"
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
  printf '  %sclaude%s  Anthropic Claude Code   https://docs.claude.com/code\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
  printf '  %scodex%s   OpenAI Codex            https://github.com/openai/codex\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
  printf '  %skiro%s    AWS Kiro                https://kiro.dev\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
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
      claude) ui_dim "    claude — https://docs.claude.com/code" ;;
      codex)  ui_dim "    codex  — https://github.com/openai/codex" ;;
      kiro)   ui_dim "    kiro   — https://kiro.dev" ;;
    esac
  done
}

# agent_setup <agent> — write Startup.md, drop the bundled skill tree
# (the migrating-to-opensearch SOP), wire up agent-specific config, and
# register the AWS MCP server. Source skill is $LIB_DIR/../skills/.
agent_setup() {
  local agent="$1"
  local skills_src="$LIB_DIR/../skills"
  [[ -f "$skills_src/Startup.md" ]] || die "missing bundled skill: $skills_src/Startup.md"

  # Always drop a copy of the SOP and Startup.md at the stage root so
  # any agent (even those without a skills convention) can find them.
  cp -f "$skills_src/Startup.md" "$STAGE_DIR/Startup.md"
  if [[ -d "$skills_src/migrating-to-opensearch" ]]; then
    mkdir -p "$STAGE_DIR/skills"
    cp -R "$skills_src/migrating-to-opensearch" "$STAGE_DIR/skills/"
  fi

  local dest_dir
  case "$agent" in
    claude)
      dest_dir="$STAGE_DIR/.claude/skills/opensearch-migration"
      mkdir -p "$dest_dir"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"
      [[ -d "$skills_src/migrating-to-opensearch" ]] \
        && cp -R "$skills_src/migrating-to-opensearch" "$STAGE_DIR/.claude/skills/"
      ;;
    codex)
      # codex reads ~/.codex/config.toml (user-scope) and AGENTS.md
      # (project-scope). Drop Startup.md as project context.
      dest_dir="$STAGE_DIR/.codex"
      mkdir -p "$dest_dir"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"
      cp -f "$skills_src/Startup.md" "$STAGE_DIR/AGENTS.md"
      ;;
    kiro)
      # Drop the upstream kiro-cli-config tree (agents, prompts,
      # settings, steering) directly into .kiro/ so `kiro chat` picks
      # them up automatically.
      dest_dir="$STAGE_DIR/.kiro"
      mkdir -p "$dest_dir"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"
      if [[ -d "$skills_src/kiro" ]]; then
        cp -R "$skills_src/kiro/." "$dest_dir/"
      fi
      ;;
    *)
      dest_dir="$STAGE_DIR"
      ;;
  esac

  _agent_install_aws_mcp "$agent"

  log_info "agent_setup: agent=$agent skill_dir=$dest_dir"
}

# _agent_install_aws_mcp <agent> — register the AWS MCP server (SigV4-
# protected, signs as the operator's IAM identity) with the agent's
# config so aws___read_documentation, aws___search_documentation,
# aws___call_aws, etc. are available in the handoff session.
#
# Backed by uvx mcp-proxy-for-aws@latest pointing at
# https://aws-mcp.us-east-1.api.aws/mcp with AWS_REGION metadata
# matching the deploy region.
#
# Per-agent registration paths:
#   claude → `claude mcp add-json aws-mcp --scope user '<json>'`
#   codex  → write [mcp_servers.aws-mcp] block to ~/.codex/config.toml
#   kiro   → write to $STAGE_DIR/.kiro/settings/mcp.json
#
# Idempotent: each path checks for an existing entry first.
# Bypass: MIGRATE_SKIP_MCP=1.
_agent_install_aws_mcp() {
  local agent="$1"
  if [[ "${MIGRATE_SKIP_MCP:-0}" -eq 1 ]]; then
    ui_dim "  MIGRATE_SKIP_MCP=1 → skipping aws-mcp registration"
    return 0
  fi
  if ! command -v uvx >/dev/null 2>&1; then
    ui_warn "uvx not on PATH; aws-mcp not registered. Install uv: https://github.com/astral-sh/uv"
    return 0
  fi
  local region; region=$(state_get AWS_REGION us-east-1)

  case "$agent" in
    claude) _agent_mcp_claude "$region" ;;
    codex)  _agent_mcp_codex  "$region" ;;
    kiro)   _agent_mcp_kiro   "$region" ;;
  esac
}

_agent_mcp_claude() {
  local region="$1"
  if ! command -v claude >/dev/null 2>&1; then return 0; fi
  if claude mcp list 2>/dev/null | grep -q '^aws-mcp\b'; then
    ui_dim "  claude: aws-mcp already registered"
    return 0
  fi
  ui_step "Registering aws-mcp with claude"
  local cfg
  cfg=$(printf '{"command":"uvx","args":["mcp-proxy-for-aws@latest","https://aws-mcp.us-east-1.api.aws/mcp","--metadata","AWS_REGION=%s"]}' "$region")
  if claude mcp add-json aws-mcp --scope user "$cfg" >>"$LOG_FILE" 2>&1; then
    ui_ok "aws-mcp registered with claude (AWS_REGION=$region)"
  else
    ui_warn "claude mcp add-json failed; see $LOG_FILE"
  fi
}

_agent_mcp_codex() {
  local region="$1"
  local cfg="$HOME/.codex/config.toml"
  mkdir -p "$(dirname "$cfg")"
  touch "$cfg"
  if grep -q '^\[mcp_servers\.aws-mcp\]' "$cfg" 2>/dev/null; then
    ui_dim "  codex: aws-mcp already in $cfg"
    return 0
  fi
  ui_step "Registering aws-mcp with codex (~/.codex/config.toml)"
  cat >>"$cfg" <<EOF

[mcp_servers.aws-mcp]
command = "uvx"
args = ["mcp-proxy-for-aws@latest", "https://aws-mcp.us-east-1.api.aws/mcp", "--metadata", "AWS_REGION=$region"]
EOF
  ui_ok "aws-mcp registered with codex (AWS_REGION=$region)"
}

_agent_mcp_kiro() {
  local region="$1"
  local cfg="$STAGE_DIR/.kiro/settings/mcp.json"
  mkdir -p "$(dirname "$cfg")"
  if [[ -f "$cfg" ]] && grep -q '"aws-mcp"' "$cfg" 2>/dev/null; then
    ui_dim "  kiro: aws-mcp already in $cfg"
    return 0
  fi
  ui_step "Registering aws-mcp with kiro ($cfg)"
  cat >"$cfg" <<EOF
{
  "mcpServers": {
    "aws-mcp": {
      "command": "uvx",
      "args": ["mcp-proxy-for-aws@latest", "https://aws-mcp.us-east-1.api.aws/mcp", "--metadata", "AWS_REGION=$region"]
    }
  }
}
EOF
  ui_ok "aws-mcp registered with kiro (AWS_REGION=$region)"
}

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
  bin=$(command -v "$agent") || die "agent not on PATH: $agent"
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

  local fresh_prompt='Read Startup.md and skills/migrating-to-opensearch/SKILL.md, then give the user next steps.'
  ui_dim "  exec $bin (resume=$resuming)"
  case "$agent" in
    claude)
      if (( resuming )); then exec "$bin" --continue
      else exec "$bin" "$fresh_prompt"
      fi ;;
    codex)
      if (( resuming )); then exec "$bin" resume --last
      else exec "$bin" "$fresh_prompt"
      fi ;;
    kiro)
      if (( resuming )); then exec "$bin" chat --agent opensearch-migration --resume
      else exec "$bin" chat --agent opensearch-migration
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
