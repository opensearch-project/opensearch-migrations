#!/usr/bin/env bash
# agent.sh — agent discovery, skill installation, and exec-handoff.
#
# Supported agents (in detection order): claude, q, kiro, codex, opencode, gemini.
#
# Skill installation per agent:
#   claude    → $STAGE_DIR/.claude/skills/opensearch-migration/Startup.md
#   q         → $STAGE_DIR/.q/skills/opensearch-migration/Startup.md  (best-effort)
#   kiro      → $STAGE_DIR/.kiro/Startup.md
#   codex     → $STAGE_DIR/.codex/Startup.md
#   *         → $STAGE_DIR/Startup.md  (fallback)
#
# Handoff: cd $STAGE_DIR; exec <agent-bin> "Read skill Startup.md and give the user next steps"
# The current migration-assistant process is replaced — no parent process remains.

[[ -n "${__MIGRATE_AGENT_LOADED:-}" ]] && return 0
__MIGRATE_AGENT_LOADED=1

AGENT_CANDIDATES=(claude q kiro codex opencode gemini)

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
    ui_info "Install one of: claude (https://docs.claude.com/code), q (https://aws.amazon.com/q/developer/), kiro (https://kiro.dev)"
    exit 1
  fi
  # shellcheck disable=SC2086
  IFS=$'\n' read -r -d '' -a agents <<<"$agents_list"$'\0' || true

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

# agent_setup <agent> — write Startup.md, drop the bundled skill tree
# (the migrating-to-opensearch SOP), wire up agent-specific config (kiro
# steering for kiro, .mcp.json + AWS MCP for claude). Source skill is
# the bundled $LIB_DIR/../skills/.
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
      _agent_install_aws_mcp_claude
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
    q)
      dest_dir="$STAGE_DIR/.q/skills/opensearch-migration"
      mkdir -p "$dest_dir"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"
      ;;
    codex)
      dest_dir="$STAGE_DIR/.codex"
      mkdir -p "$dest_dir"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"
      ;;
    *)
      dest_dir="$STAGE_DIR"
      ;;
  esac

  log_info "agent_setup: agent=$agent skill_dir=$dest_dir"
}

# _agent_install_aws_mcp_claude — register the AWS MCP server with the
# operator's claude config so `aws___read_documentation` and the rest of
# the AWS MCP tool surface are available in the handoff session.
#
# Idempotent: skips when claude already knows about a server named
# "aws-mcp", or when the user has set MIGRATE_SKIP_MCP=1.
_agent_install_aws_mcp_claude() {
  if [[ "${MIGRATE_SKIP_MCP:-0}" -eq 1 ]]; then
    ui_dim "  MIGRATE_SKIP_MCP=1 → skipping aws-mcp registration"
    return 0
  fi
  if ! command -v claude >/dev/null 2>&1; then
    return 0
  fi
  if claude mcp list 2>/dev/null | grep -q '^aws-mcp\b'; then
    ui_dim "  aws-mcp already registered with claude"
    return 0
  fi
  ui_step "Registering aws-mcp with claude (provides aws___read_documentation)"
  local region; region=$(state_get AWS_REGION us-east-1)
  local cfg
  cfg=$(printf '{"command":"uvx","args":["mcp-proxy-for-aws@latest","https://aws-mcp.us-east-1.api.aws/mcp","--metadata","AWS_REGION=%s"]}' "$region")
  if claude mcp add-json aws-mcp --scope user "$cfg" >>"$LOG_FILE" 2>&1; then
    ui_ok "aws-mcp registered (region metadata=$region)"
  else
    ui_warn "claude mcp add-json failed; agent will fall back to web fetch"
    ui_dim "  retry manually: claude mcp add-json aws-mcp --scope user '$cfg'"
  fi
}

# agent_exec <agent> — replace this process with the agent. Never returns.
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

  ui_dim "  exec $bin"
  case "$agent" in
    kiro)
      # kiro chat with the bundled opensearch-migration agent
      # definition (.kiro/agents/opensearch-migration.json).
      exec "$bin" chat --agent opensearch-migration
      ;;
    *)
      exec "$bin" "Read Startup.md and skills/migrating-to-opensearch/SKILL.md, then give the user next steps."
      ;;
  esac
}
