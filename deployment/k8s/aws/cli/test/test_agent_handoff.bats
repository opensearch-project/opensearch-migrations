#!/usr/bin/env bats
# test_agent_handoff.bats — verify the agent setup & exec logic.
#
# We don't actually exec the agents (that would replace the bats process).
# Instead we install stub binaries on PATH that record their invocation,
# then call agent_setup / _agent_has_session / the per-agent MCP
# registration helpers and assert on the state they produce.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh dashboard.sh cfn.sh \
            crane.sh helm.sh console.sh agent.sh
  # Suppress global EXIT trap so test failures aren't swallowed.
  trap - EXIT
  log_init

  # Ensure $LIB_DIR/../skills/Startup.md exists (the bundled skill).
  # In a tarball the skills dir is alongside lib/. In dev runs it's
  # at the repo root.
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export LIB_DIR="$PROJECT_ROOT/lib"
}

teardown() {
  teardown_isolated_home
}

# ---------- _agent_has_session: claude session detection ----------

@test "_agent_has_session claude returns 1 when no session dir" {
  cd "$STAGE_DIR" 2>/dev/null || mkdir -p "$STAGE_DIR" && cd "$STAGE_DIR"
  run _agent_has_session claude
  [ "$status" -ne 0 ]
}

@test "_agent_has_session claude returns 0 when a *.jsonl session exists for cwd" {
  mkdir -p "$STAGE_DIR"
  cd "$STAGE_DIR"
  # Replicate claude's CWD-encoding: replace / and . with -
  local key="${PWD//\//-}"
  key="${key//./-}"
  mkdir -p "$HOME/.claude/projects/$key"
  : >"$HOME/.claude/projects/$key/sess1.jsonl"
  run _agent_has_session claude
  [ "$status" -eq 0 ]
}

@test "_agent_has_session claude returns 1 when dir exists but is empty" {
  mkdir -p "$STAGE_DIR"
  cd "$STAGE_DIR"
  local key="${PWD//\//-}"
  key="${key//./-}"
  mkdir -p "$HOME/.claude/projects/$key"
  run _agent_has_session claude
  [ "$status" -ne 0 ]
}

@test "_agent_has_session non-claude agents return 0 (optimistic)" {
  # codex / kiro have no public session storage we probe; we always
  # return 0 and rely on the agent's own --resume to handle no-history
  # cases. This is a contract test for that behavior.
  run _agent_has_session codex
  [ "$status" -eq 0 ]
  run _agent_has_session kiro
  [ "$status" -eq 0 ]
}

# ---------- agent_setup: skill files land in the right places ----------

@test "agent_setup claude drops Startup.md and SOP into .claude/skills/" {
  agent_setup claude
  [ -f "$STAGE_DIR/.claude/skills/opensearch-migration/Startup.md" ]
  [ -f "$STAGE_DIR/Startup.md" ]
  [ -d "$STAGE_DIR/skills/migrating-to-opensearch" ]
}

@test "agent_setup codex drops AGENTS.md at stage root and Startup.md in .codex/" {
  agent_setup codex
  [ -f "$STAGE_DIR/.codex/Startup.md" ]
  [ -f "$STAGE_DIR/AGENTS.md" ]
}

@test "agent_setup kiro copies the kiro-cli-config tree" {
  # The bundled skills/kiro/ tree should land under .kiro/ verbatim.
  if [[ ! -d "$LIB_DIR/../skills/kiro" ]]; then
    skip "skills/kiro not present in this tree"
  fi
  agent_setup kiro
  [ -f "$STAGE_DIR/.kiro/Startup.md" ]
  [ -d "$STAGE_DIR/.kiro/agents" ]      || [ -d "$STAGE_DIR/.kiro/steering" ]
}

# ---------- AWS MCP registration ----------

@test "_agent_install_aws_mcp skips when MIGRATE_SKIP_MCP=1" {
  MIGRATE_SKIP_MCP=1 _agent_install_aws_mcp claude
  # Should not have written a config anywhere.
  [ ! -f "$HOME/.codex/config.toml" ]
  [ ! -f "$STAGE_DIR/.kiro/settings/mcp.json" ]
}

@test "_agent_install_aws_mcp skips when uvx not on PATH" {
  # Strip uvx from the stub PATH; the real PATH may have it but we
  # control it via $STUB_DIR.
  PATH="$STUB_DIR:/bin:/usr/bin" _agent_install_aws_mcp codex
  [ ! -f "$HOME/.codex/config.toml" ]
}

@test "_agent_install_aws_mcp codex appends [mcp_servers.aws-mcp] block" {
  mkstub uvx ''
  state_set AWS_REGION "us-west-2"
  _agent_install_aws_mcp codex
  [ -f "$HOME/.codex/config.toml" ]
  grep -q '^\[mcp_servers\.aws-mcp\]' "$HOME/.codex/config.toml"
  grep -q 'AWS_REGION=us-west-2' "$HOME/.codex/config.toml"
  grep -q 'aws-mcp.us-east-1.api.aws/mcp' "$HOME/.codex/config.toml"
}

@test "_agent_install_aws_mcp codex is idempotent (no duplicate block)" {
  mkstub uvx ''
  state_set AWS_REGION "us-east-1"
  _agent_install_aws_mcp codex
  _agent_install_aws_mcp codex
  local count
  count=$(grep -c '^\[mcp_servers\.aws-mcp\]' "$HOME/.codex/config.toml")
  [ "$count" -eq 1 ]
}

@test "_agent_install_aws_mcp kiro writes settings/mcp.json" {
  mkstub uvx ''
  state_set AWS_REGION "eu-west-1"
  mkdir -p "$STAGE_DIR/.kiro"
  _agent_install_aws_mcp kiro
  [ -f "$STAGE_DIR/.kiro/settings/mcp.json" ]
  grep -q '"aws-mcp"' "$STAGE_DIR/.kiro/settings/mcp.json"
  grep -q 'AWS_REGION=eu-west-1' "$STAGE_DIR/.kiro/settings/mcp.json"
}

@test "_agent_install_aws_mcp kiro is idempotent (file rewrite skipped)" {
  mkstub uvx ''
  state_set AWS_REGION "us-east-1"
  mkdir -p "$STAGE_DIR/.kiro"
  _agent_install_aws_mcp kiro
  local hash1
  hash1=$(md5 -q "$STAGE_DIR/.kiro/settings/mcp.json" 2>/dev/null \
          || md5sum "$STAGE_DIR/.kiro/settings/mcp.json" | cut -d' ' -f1)
  _agent_install_aws_mcp kiro
  local hash2
  hash2=$(md5 -q "$STAGE_DIR/.kiro/settings/mcp.json" 2>/dev/null \
          || md5sum "$STAGE_DIR/.kiro/settings/mcp.json" | cut -d' ' -f1)
  [ "$hash1" = "$hash2" ]
}

# ---------- install-hint surfacing ----------

@test "_agent_print_install_hints_for_missing prints hints for absent agents" {
  # Only claude is "installed". codex + kiro hints should appear.
  run_split() {
    local out_file err_file
    out_file=$(mktemp); err_file=$(mktemp)
    ( "$@" ) >"$out_file" 2>"$err_file"
    STDOUT=$(cat "$out_file"); STDERR=$(cat "$err_file")
    rm -f "$out_file" "$err_file"
  }
  run_split _agent_print_install_hints_for_missing claude
  [[ "$STDERR" == *"codex"* ]]
  [[ "$STDERR" == *"kiro"* ]]
  [[ "$STDERR" != *"claude — "* ]]   # the installed one should NOT be hinted
}

@test "_agent_print_install_hints_for_missing prints nothing when all present" {
  run_split() {
    local out_file err_file
    out_file=$(mktemp); err_file=$(mktemp)
    ( "$@" ) >"$out_file" 2>"$err_file"
    STDOUT=$(cat "$out_file"); STDERR=$(cat "$err_file")
    rm -f "$out_file" "$err_file"
  }
  run_split _agent_print_install_hints_for_missing claude codex kiro
  [ -z "$STDERR" ]
}

# ---------- discover_agents ----------

@test "discover_agents lists only AGENT_CANDIDATES that resolve on PATH" {
  # Install stubs for claude + kiro; not codex.
  mkstub claude ''
  mkstub kiro ''

  run discover_agents
  [ "$status" -eq 0 ]
  [[ "$output" == *"claude"* ]]
  [[ "$output" == *"kiro"* ]]
  [[ "$output" != *"codex"* ]]
  [[ "$output" != *" q"* ]]   # q-developer was removed
}

@test "discover_agents returns non-zero when no candidates installed" {
  # PATH has neither claude, codex, nor kiro available.
  PATH="$STUB_DIR:/usr/bin:/bin" run discover_agents
  [ "$status" -ne 0 ]
}
