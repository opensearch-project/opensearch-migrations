#!/usr/bin/env bats
# test_pack.bats — coverage for lib/pack.sh / cmd_pack.
#
# Builds a fake input tarball from agent-skills/, runs `migration-assistant
# pack` end-to-end, and verifies the output tarball has the right shape.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  export CLI_BIN="$PROJECT_ROOT/bin/migration-assistant"
  if [[ ! -x "$CLI_BIN" ]]; then
    skip "CLI binary not executable"
  fi

  # Stage a fake input tarball: bin/ + lib/ + skills/ (with manifest.json
  # at root) + 4 SKILL.md skill dirs.
  STAGE_TAR="$TMPHOME/stage"
  mkdir -p "$STAGE_TAR"
  TARBALL_DIR="$STAGE_TAR/migration-assistant-cli-3.2.1"
  mkdir -p "$TARBALL_DIR"
  cp -R "$PROJECT_ROOT/bin"  "$TARBALL_DIR/"
  cp -R "$PROJECT_ROOT/lib"  "$TARBALL_DIR/"
  mkdir -p "$TARBALL_DIR/skills"
  cp "$PROJECT_ROOT/../../../../agent-skills/skills/manifest.json" "$TARBALL_DIR/manifest.json"
  cp "$PROJECT_ROOT/../../../../agent-skills/skills/manifest.json" "$TARBALL_DIR/skills/manifest.json"
  : >"$TARBALL_DIR/skills/Startup.md"
  local s
  for s in aoss-nextgen migrating-to-opensearch migration-assistant-cli-reference migration-assistant-operator; do
    cp -R "$PROJECT_ROOT/../../../../agent-skills/skills/$s" "$TARBALL_DIR/skills/"
  done

  INPUT_TARBALL="$STAGE_TAR/migration-assistant-cli-3.2.1.tar.gz"
  ( cd "$STAGE_TAR" && tar -czf "$INPUT_TARBALL" "migration-assistant-cli-3.2.1" )
}

teardown() {
  teardown_isolated_home
}

@test "pack --help prints the help text" {
  run "$CLI_BIN" pack --help
  [ "$status" -eq 0 ]
  [[ "$output" == *"--add-skill"* ]]
  [[ "$output" == *"--add-mcp"* ]]
  [[ "$output" == *"--branding"* ]]
}

@test "pack rejects missing required flags" {
  run "$CLI_BIN" pack --input "$INPUT_TARBALL" --output /tmp/bogus
  [ "$status" -ne 0 ]
  [[ "$output" == *"--pack-name"* ]]
}

@test "pack with --add-skill includes the new skill in the output" {
  local skill_src="$TMPHOME/myorg-runbook"
  mkdir -p "$skill_src"
  printf '# MyOrg\n' > "$skill_src/SKILL.md"

  local out="$TMPHOME/out.tar.gz"
  run "$CLI_BIN" pack \
    --input "$INPUT_TARBALL" \
    --add-skill "$skill_src" \
    --pack-name myorg --pack-version 1.0.0 \
    --output "$out"
  [ "$status" -eq 0 ]
  [ -f "$out" ]

  local extract="$TMPHOME/extract"
  mkdir -p "$extract"
  tar -xzf "$out" -C "$extract"
  [ -f "$extract/migration-assistant-cli-3.2.1/skills/myorg-runbook/SKILL.md" ]
}

@test "pack with --add-mcp merges the MCP into the manifest" {
  local frag="$TMPHOME/frag.json"
  cat >"$frag" <<'EOF'
{
  "myorg-mcp": {
    "command": "uvx",
    "args": ["myorg@latest"],
    "scope": "project",
    "agents": ["claude"]
  }
}
EOF
  local out="$TMPHOME/out.tar.gz"
  run "$CLI_BIN" pack \
    --input "$INPUT_TARBALL" \
    --add-mcp "$frag" \
    --pack-name myorg --pack-version 1.0.0 \
    --output "$out"
  [ "$status" -eq 0 ]

  local extract="$TMPHOME/extract"
  mkdir -p "$extract"
  tar -xzf "$out" -C "$extract"
  local m="$extract/migration-assistant-cli-3.2.1/manifest.json"
  [ -f "$m" ]
  jq -e '.mcpServers["myorg-mcp"].command == "uvx"' "$m"
  jq -e '.mcpServers["aws-mcp"]' "$m"   # upstream still present
}

@test "pack with --brand-binary renames the tarball top dir" {
  local out="$TMPHOME/out.tar.gz"
  run "$CLI_BIN" pack \
    --input "$INPUT_TARBALL" \
    --brand-binary myorg-migrate \
    --pack-name myorg --pack-version 1.0.0 \
    --output "$out"
  [ "$status" -eq 0 ]

  local extract="$TMPHOME/extract"
  mkdir -p "$extract"
  tar -xzf "$out" -C "$extract"
  [ -d "$extract/myorg-migrate-cli-3.2.1" ]
  jq -e '.branding.binaryName == "myorg-migrate"' "$extract/myorg-migrate-cli-3.2.1/manifest.json"
}

@test "pack with --mode-default + --mode-order reshapes branding.modes" {
  local out="$TMPHOME/out.tar.gz"
  run "$CLI_BIN" pack \
    --input "$INPUT_TARBALL" \
    --mode-default Agent \
    --mode-order Agent,Manual \
    --pack-name myorg --pack-version 1.0.0 \
    --output "$out"
  [ "$status" -eq 0 ]

  local extract="$TMPHOME/extract"
  mkdir -p "$extract"
  tar -xzf "$out" -C "$extract"
  local m="$extract/migration-assistant-cli-3.2.1/manifest.json"
  jq -e '.branding.modes[0].id == "Agent"' "$m"
  jq -e '.branding.modes[0].default == true' "$m"
  jq -e '.branding.modes[1].id == "Manual"' "$m"
  jq -e '.branding.modes[1].default == false' "$m"
}

@test "pack records the pack entry in build.packs[]" {
  local out="$TMPHOME/out.tar.gz"
  run "$CLI_BIN" pack \
    --input "$INPUT_TARBALL" \
    --pack-name myorg-internal --pack-version 1.0.0 \
    --output "$out"
  [ "$status" -eq 0 ]

  local extract="$TMPHOME/extract"
  mkdir -p "$extract"
  tar -xzf "$out" -C "$extract"
  local m="$extract/migration-assistant-cli-3.2.1/manifest.json"
  jq -e '.build.packs | length == 1' "$m"
  jq -e '.build.packs[0].name == "myorg-internal"' "$m"
  jq -e '.build.packs[0].version == "1.0.0"' "$m"
  # appliedAt is an ISO-8601 UTC timestamp.
  jq -e '.build.packs[0].appliedAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T")' "$m"
}

@test "pack rejects --add-skill with no SKILL.md" {
  local skill_src="$TMPHOME/empty"
  mkdir -p "$skill_src"
  local out="$TMPHOME/out.tar.gz"
  run "$CLI_BIN" pack \
    --input "$INPUT_TARBALL" \
    --add-skill "$skill_src" \
    --pack-name x --pack-version 1.0.0 \
    --output "$out"
  [ "$status" -ne 0 ]
  [[ "$output" == *"SKILL.md"* ]]
}

@test "pack --strict catches duplicate mode default" {
  local frag="$TMPHOME/bad.json"
  cat >"$frag" <<'EOF'
{
  "modes": [
    {"id":"Manual","label":"Manual","description":"x","default":true,"available":true},
    {"id":"Agent", "label":"AI",    "description":"y","default":true,"available":true}
  ]
}
EOF
  local out="$TMPHOME/out.tar.gz"
  run "$CLI_BIN" pack \
    --input "$INPUT_TARBALL" \
    --branding "$frag" \
    --strict \
    --pack-name x --pack-version 1.0.0 \
    --output "$out"
  [ "$status" -ne 0 ]
}
