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
# (the migrating-to-opensearch SOP), wire up agent-specific config, and
# register the AWS MCP server. Source skill is $LIB_DIR/../skills/.
agent_setup() {
  local agent="$1"
  # Skill source resolution. In a release tarball, skills live next to
  # bin/ + lib/ at $LIB_DIR/../skills. In a repo checkout (dev mode),
  # they live at the repo root in agent-skills/skills.
  local skills_src=""
  if [[ -f "$LIB_DIR/../skills/Startup.md" ]]; then
    skills_src="$LIB_DIR/../skills"
  elif [[ -f "$LIB_DIR/../../../../agent-skills/skills/Startup.md" ]]; then
    # cli/lib/ → cli/ → aws/ → k8s/ → deployment/ → repo-root
    skills_src="$LIB_DIR/../../../../agent-skills/skills"
  elif [[ -n "${MIGRATE_SKILLS_SRC:-}" && -f "${MIGRATE_SKILLS_SRC}/Startup.md" ]]; then
    skills_src="$MIGRATE_SKILLS_SRC"
  else
    die "could not locate Startup.md (looked in lib/../skills + agent-skills/skills + MIGRATE_SKILLS_SRC)"
  fi

  # Always drop a copy of the Startup runbook + every shipped skill at
  # the stage root so any agent (even those without a skills convention)
  # can read them by relative path.
  cp -f "$skills_src/Startup.md" "$STAGE_DIR/Startup.md"
  mkdir -p "$STAGE_DIR/skills"
  local skill
  for skill in migration-assistant-operator migrating-to-opensearch aoss-nextgen; do
    if [[ -d "$skills_src/$skill" ]]; then
      cp -R "$skills_src/$skill" "$STAGE_DIR/skills/"
    fi
  done

  local dest_dir
  case "$agent" in
    claude)
      # Claude Code's auto-loadable skills live under .claude/skills/.
      # Each subdir with a SKILL.md is detected as one skill.
      dest_dir="$STAGE_DIR/.claude/skills"
      mkdir -p "$dest_dir/opensearch-migration"
      cp -f "$skills_src/Startup.md" "$dest_dir/opensearch-migration/Startup.md"
      for skill in migration-assistant-operator migrating-to-opensearch aoss-nextgen; do
        if [[ -d "$skills_src/$skill" ]]; then
          cp -R "$skills_src/$skill" "$dest_dir/"
        fi
      done
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
      # Drop the upstream kiro config tree (agents, prompts, settings,
      # steering) directly into .kiro/ so `kiro chat` picks them up
      # automatically. In a release tarball, kiro/ lives under
      # skills/kiro/. In a repo checkout it's a sibling to skills/
      # at agent-skills/kiro/.
      dest_dir="$STAGE_DIR/.kiro"
      mkdir -p "$dest_dir/steering"
      cp -f "$skills_src/Startup.md" "$dest_dir/Startup.md"
      local kiro_src=""
      if [[ -d "$skills_src/kiro" ]]; then
        kiro_src="$skills_src/kiro"
      elif [[ -d "$skills_src/../kiro" ]]; then
        kiro_src="$skills_src/../kiro"
      fi
      if [[ -n "$kiro_src" ]]; then
        cp -R "$kiro_src/." "$dest_dir/"
      fi
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
  _agent_ensure_uvx || die "aws-mcp registration requires uvx; rerun with MIGRATE_SKIP_MCP=1 to opt out"
  local region; region=$(state_get AWS_REGION us-east-1)

  case "$agent" in
    claude) _agent_mcp_claude "$region" ;;
    codex)  _agent_mcp_codex  "$region" ;;
    kiro)   _agent_mcp_kiro   "$region" ;;
  esac
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

  local fresh_prompt='Read Startup.md and skills/migration-assistant-operator/SKILL.md (then skills/migration-assistant-operator/workflow.md). The migrating-to-opensearch skill is for assessment-only sessions; do not load it unless the user explicitly asks for an assessment. Give the user next steps based on what state.env shows.'
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
