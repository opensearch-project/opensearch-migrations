#!/usr/bin/env bash
# helpers/stub.sh — utilities for bats tests to fake commands & isolate HOME.
#
# Each test should:
#   1. Call `setup_isolated_home` in `setup()`. This creates a fresh $HOME
#      and exports MIGRATE_HOME under it.
#   2. Use `mkstub <cmd> <stdout> [<exit>]` to fake any external command.
#      The stub directory is prepended to PATH for the duration of the test.

# shellcheck disable=SC2034   # variables consumed by callers
setup_isolated_home() {
  TMPHOME=$(mktemp -d)
  export HOME="$TMPHOME"
  export MIGRATE_HOME="$TMPHOME/.opensearch-migrate"
  export STAGE="${STAGE:-default}"
  export STAGE_DIR="$MIGRATE_HOME/$STAGE"
  export STUB_DIR="$TMPHOME/stubs"
  mkdir -p "$STUB_DIR"
  export PATH="$STUB_DIR:$PATH"
}

teardown_isolated_home() {
  if [[ -n "${TMPHOME:-}" && -d "$TMPHOME" ]]; then
    rm -rf "$TMPHOME"
  fi
}

# mkstub <cmd> <stdout> [<exit-code>] — write a fake command to STUB_DIR/<cmd>
# that prints <stdout> and exits <exit-code> (default 0). The stub records
# every invocation under STUB_DIR/<cmd>.calls (one line per call, args joined
# by spaces).
mkstub() {
  local cmd="$1" out="$2" rc="${3:-0}"
  local path="$STUB_DIR/$cmd"
  cat >"$path" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$STUB_DIR/${cmd}.calls"
cat <<'STUB_OUT'
$out
STUB_OUT
exit $rc
EOF
  chmod +x "$path"
}

# stub_calls <cmd> — print all recorded invocations of <cmd>.
stub_calls() {
  local cmd="$1"
  local f="$STUB_DIR/${cmd}.calls"
  if [[ -f "$f" ]]; then
    cat "$f"
  fi
}

# load_libs <name…> — source the named libs from the project's lib/.
load_libs() {
  local proj_lib
  proj_lib="$BATS_TEST_DIRNAME/../lib"
  [[ -d "$proj_lib" ]] || proj_lib="$(cd "$BATS_TEST_DIRNAME/.." && pwd)/lib"
  export LIB_DIR="$proj_lib"
  local lib
  for lib in "$@"; do
    # shellcheck disable=SC1090
    source "$proj_lib/$lib"
  done
}
