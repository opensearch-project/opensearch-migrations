#!/usr/bin/env bats
# test_manifest.bats — coverage for lib/manifest.sh.
#
# Verifies the bundled manifest loads, branding resolves with ${VAR}
# substitution, MCP fields and args parse correctly, and skill auto-
# discovery walks SKILL.md.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh std.sh term.sh ui.sh log.sh state.sh manifest.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

@test "manifest_init loads the bundled manifest" {
  manifest_init
  manifest_have
}

@test "manifest_brand reads top-level branding fields" {
  manifest_init
  [ "$(manifest_brand appName)"   = "OpenSearch Migration Assistant" ]
  [ "$(manifest_brand binaryName)" = "migration-assistant" ]
  [[ "$(manifest_brand helpHeader)" == *"OpenSearch Migration Assistant CLI"* ]]
}

@test "manifest_brand performs \${VAR} substitution from state" {
  manifest_init
  state_load
  state_set MIGRATE_TEST_VAR "from-state"
  state_save
  # Inject a synthetic field via a tempdir manifest.
  local m="$STAGE_DIR/_test_manifest.json"
  jq '.branding.tagline = "hello-${MIGRATE_TEST_VAR}-world"' \
    "$LIB_DIR/../../../../../agent-skills/skills/manifest.json" >"$m" \
    || skip "couldn't stage test manifest (path resolution)"
  MIGRATE_MANIFEST="$m"
  __MANIFEST_PARSED=0; __MANIFEST_RAW=""; __MANIFEST_PATH=""
  manifest_init
  [ "$(manifest_brand tagline)" = "hello-from-state-world" ]
}

@test "manifest_modes lists modes in array order with default flag" {
  manifest_init
  out=$(manifest_modes)
  # Default upstream manifest: Manual first (default), Agent second.
  first_id=$(printf '%s\n' "$out" | head -1 | cut -d'|' -f1)
  [ "$first_id" = "Manual" ]
  # 2 lines — count with grep (line-count works regardless of trailing
  # newline; wc -l on bash 3.2 macOS misses the last unterminated line).
  [ "$(printf '%s\n' "$out" | grep -c '|')" -eq 2 ]
  # Manual is default.
  manual_default=$(printf '%s\n' "$out" | grep '^Manual|' | cut -d'|' -f4)
  [ "$manual_default" = "1" ]
}

@test "manifest_skills auto-discovers SKILL.md dirs" {
  manifest_init
  out=$(manifest_skills)
  # The two upstream skills. Partner packs add more via the
  # `migration-assistant pack` flow; the discovery contract is "any
  # subdir of skills/ with a SKILL.md" — see lib/manifest.sh:manifest_skills.
  [[ "$out" == *"migration-assistant-cli-reference"* ]]
  [[ "$out" == *"migration-assistant-operator"* ]]
}

@test "manifest_mcp_names filters by agent" {
  manifest_init
  out=$(manifest_mcp_names claude)
  [[ "$out" == *"aws-mcp"* ]]
  out=$(manifest_mcp_names some-unknown-agent)
  [ -z "$out" ]
}

@test "manifest_mcp_args substitutes \${AWS_REGION}" {
  manifest_init
  state_load
  state_set AWS_REGION "us-west-9"
  state_save
  out=$(manifest_mcp_args aws-mcp | tr '\n' ' ')
  [[ "$out" == *"AWS_REGION=us-west-9"* ]]
}

@test "manifest_mcp_perms enumerates permissionsAllow" {
  manifest_init
  out=$(manifest_mcp_perms aws-mcp)
  [ "$(printf '%s' "$out" | wc -l | tr -d ' ')" -ge 3 ]
  [[ "$out" == *"mcp__aws-mcp__aws___read_documentation"* ]]
}

@test "manifest_pack_summary is empty for upstream build" {
  manifest_init
  [ -z "$(manifest_pack_summary)" ]
}

@test "manifest_have returns failure when no manifest is found" {
  MIGRATE_MANIFEST=/nonexistent/path/never-exists.json
  __MANIFEST_PARSED=0; __MANIFEST_RAW=""; __MANIFEST_PATH=""
  # Also unset LIB_DIR so the fallback paths don't accidentally find one.
  local saved="$LIB_DIR"
  export LIB_DIR=/nonexistent/path/never-exists/lib
  manifest_init
  ! manifest_have
  export LIB_DIR="$saved"
}
