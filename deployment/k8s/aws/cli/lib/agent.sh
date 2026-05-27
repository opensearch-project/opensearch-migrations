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

# agent_setup <agent> — write Startup.md (and any per-agent paths) into the
# stage dir. Source skill is the bundled $LIB_DIR/../skills/Startup.md.
agent_setup() {
  local agent="$1"
  local src="$LIB_DIR/../skills/Startup.md"
  [[ -f "$src" ]] || die "missing bundled skill: $src"

  local dest_dir
  case "$agent" in
    claude)              dest_dir="$STAGE_DIR/.claude/skills/opensearch-migration" ;;
    q)                   dest_dir="$STAGE_DIR/.q/skills/opensearch-migration" ;;
    kiro)                dest_dir="$STAGE_DIR/.kiro" ;;
    codex)               dest_dir="$STAGE_DIR/.codex" ;;
    *)                   dest_dir="$STAGE_DIR" ;;
  esac
  mkdir -p "$dest_dir"
  cp -f "$src" "$dest_dir/Startup.md"

  # Also drop a top-level copy so any agent without skill-folder support sees it.
  cp -f "$src" "$STAGE_DIR/Startup.md"

  log_info "agent_setup: agent=$agent skill_dir=$dest_dir"
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
  exec "$bin" "Read skill Startup.md and give the user next steps."
}
