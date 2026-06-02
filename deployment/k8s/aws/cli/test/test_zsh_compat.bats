#!/usr/bin/env bats
# test_zsh_compat.bats — verify that the CLI runs correctly when invoked
# from zsh and that install.sh + activate work for zsh users.
#
# The CLI itself is bash (shebang `#!/usr/bin/env bash`), so the kernel
# honors that even when the user's interactive shell is zsh. The places
# where shell-compat actually matters are:
#
#   1. Running `migration-assistant` from a zsh terminal — the kernel
#      should `execve` bash regardless of zsh's role as the parent.
#   2. install.sh, when run via `curl | zsh` or directly under zsh —
#      its shebang is `#!/usr/bin/env bash`, so same story.
#   3. The `activate` script that workspace mode generates is sourced
#      by the user's interactive shell — zsh OR bash. It must be
#      compatible with both syntaxes.
#
# Tests skip if zsh isn't on PATH (no-op for CI runners without zsh).

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  export CLI_BIN="$PROJECT_ROOT/bin/migration-assistant"

  if ! command -v zsh >/dev/null 2>&1; then
    skip "zsh not on PATH"
  fi
}

teardown() {
  teardown_isolated_home
}

@test "zsh: migration-assistant version (shebang honored under zsh)" {
  # `zsh -c '<cmd>'` execs <cmd>. The kernel reads the shebang and runs
  # the script under bash. If the shebang or the script body breaks
  # under zsh's parent-shell role, this fails.
  run zsh -c "'$CLI_BIN' version"
  [ "$status" -eq 0 ]
  [[ "$output" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]
}

@test "zsh: migration-assistant help" {
  run zsh -c "'$CLI_BIN' help"
  [ "$status" -eq 0 ]
  [[ "$output" == *"OpenSearch Migration Assistant CLI"* ]]
}

@test "zsh: install.sh path mode (with INSTALL_FROM_LOCAL)" {
  # install.sh's INSTALL_FROM_LOCAL path expects a `skills/` subdir next
  # to bin/lib. The release tarball has it; a fresh source checkout
  # doesn't (skills/ is bundled at gradle build time). Stage a fake one
  # so we can exercise install.sh without touching the network.
  local fake; fake=$(mktemp -d)
  cp -R "$PROJECT_ROOT/bin"  "$fake/"
  cp -R "$PROJECT_ROOT/lib"  "$fake/"
  cp -R "$PROJECT_ROOT/install.sh" "$fake/"
  cp -R "$PROJECT_ROOT/README.md"  "$fake/"
  mkdir -p "$fake/skills"
  : >"$fake/skills/Startup.md"

  local prefix="$TMPHOME/installed"
  local bindir="$TMPHOME/bin"
  run zsh -c "INSTALL_FROM_LOCAL='$fake' MIGRATE_PREFIX='$prefix' BIN_DIR='$bindir' '$fake/install.sh'"
  [ "$status" -eq 0 ]
  [ -x "$bindir/migration-assistant" ]
  [ -x "$prefix/bin/migration-assistant" ]
  rm -rf "$fake"
}

@test "zsh: install.sh workspace mode generates a portable activate script" {
  local fake; fake=$(mktemp -d)
  cp -R "$PROJECT_ROOT/bin"  "$fake/"
  cp -R "$PROJECT_ROOT/lib"  "$fake/"
  cp -R "$PROJECT_ROOT/install.sh" "$fake/"
  cp -R "$PROJECT_ROOT/README.md"  "$fake/"
  mkdir -p "$fake/skills"
  : >"$fake/skills/Startup.md"

  local ws="$TMPHOME/ws"
  mkdir -p "$ws"
  ( cd "$ws" && \
    INSTALL_FROM_LOCAL="$fake" MIGRATE_INSTALL=workspace MIGRATE_NO_LAUNCH=1 \
      zsh -c "'$fake/install.sh'" ) >/dev/null
  [ -f "$ws/migration-assistant-workspace/activate" ]

  # The activate file must be source-able from BOTH shells without
  # syntax errors. Source it under each, then assert MIGRATE_HOME was
  # set and the bin dir landed on PATH.
  local activate="$ws/migration-assistant-workspace/activate"

  zsh_out=$(zsh -c "source '$activate' && echo \"\$MIGRATE_HOME\"")
  [ "$zsh_out" = "$ws/migration-assistant-workspace" ]

  bash_out=$(bash -c "source '$activate' && echo \"\$MIGRATE_HOME\"")
  [ "$bash_out" = "$ws/migration-assistant-workspace" ]

  # PATH wiring: case statement uses POSIX-portable :colon-bracket
  # form, so both shells should pick it up.
  zsh_path=$(zsh -c "source '$activate' && case \":\$PATH:\" in *\":\$MIGRATE_HOME/bin:\"*) echo wired ;; *) echo MISS ;; esac")
  [ "$zsh_path" = "wired" ]
  bash_path=$(bash -c "source '$activate' && case \":\$PATH:\" in *\":\$MIGRATE_HOME/bin:\"*) echo wired ;; *) echo MISS ;; esac")
  [ "$bash_path" = "wired" ]

  rm -rf "$fake"
}

@test "zsh: workspace activated, then CLI runs (full handoff)" {
  local fake; fake=$(mktemp -d)
  cp -R "$PROJECT_ROOT/bin"  "$fake/"
  cp -R "$PROJECT_ROOT/lib"  "$fake/"
  cp -R "$PROJECT_ROOT/install.sh" "$fake/"
  cp -R "$PROJECT_ROOT/README.md"  "$fake/"
  mkdir -p "$fake/skills"
  : >"$fake/skills/Startup.md"

  local ws="$TMPHOME/ws"
  mkdir -p "$ws"
  ( cd "$ws" && \
    INSTALL_FROM_LOCAL="$fake" MIGRATE_INSTALL=workspace MIGRATE_NO_LAUNCH=1 \
      zsh -c "'$fake/install.sh'" ) >/dev/null

  # Source the activate file in zsh, then exec the CLI by name.
  out=$(zsh -c "source '$ws/migration-assistant-workspace/activate' && migration-assistant version")
  [[ "$out" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]
  rm -rf "$fake"
}

@test "zsh-installed workspace works when activated from bash (cross-shell handoff)" {
  local fake; fake=$(mktemp -d)
  cp -R "$PROJECT_ROOT/bin"  "$fake/"
  cp -R "$PROJECT_ROOT/lib"  "$fake/"
  cp -R "$PROJECT_ROOT/install.sh" "$fake/"
  cp -R "$PROJECT_ROOT/README.md"  "$fake/"
  mkdir -p "$fake/skills"
  : >"$fake/skills/Startup.md"

  local ws="$TMPHOME/ws"
  mkdir -p "$ws"
  ( cd "$ws" && \
    INSTALL_FROM_LOCAL="$fake" MIGRATE_INSTALL=workspace MIGRATE_NO_LAUNCH=1 \
      zsh -c "'$fake/install.sh'" ) >/dev/null

  out=$(bash -c "source '$ws/migration-assistant-workspace/activate' && migration-assistant version")
  [[ "$out" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]
  rm -rf "$fake"
}
