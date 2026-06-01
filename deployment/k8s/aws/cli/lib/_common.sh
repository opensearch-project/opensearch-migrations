#!/usr/bin/env bash
# _common.sh — shell hardening, traps, helpers used by every other lib.
#
# Sourced from bin/migration-assistant before any other lib. Idempotent: safe
# to source multiple times.

[[ -n "${__MIGRATE_COMMON_LOADED:-}" ]] && return 0
__MIGRATE_COMMON_LOADED=1

set -o errexit
set -o nounset
set -o pipefail
shopt -s extglob

umask 077

# Globals
#
# MIGRATE_HOME is the per-project state root: every stage's state.env,
# logs, plan, and history live under $MIGRATE_HOME/<stage>/. When the
# operator hasn't pinned it (CI and the `activate` script both export it
# explicitly), default to a `migration-assistant-workspace/` directory in
# the CURRENT directory — so wherever you run `migration-assistant` from
# becomes your migration project, and `rm -rf migration-assistant-workspace`
# cleans it up. Set MIGRATE_HOME to share one state root across projects
# (e.g. the old `~/.opensearch-migrate` global) if you prefer.
: "${MIGRATE_HOME:="$PWD/migration-assistant-workspace"}"
: "${STAGE:=default}"
STAGE_DIR="$MIGRATE_HOME/$STAGE"

# Non-interactive mode. When set to 1, every ui_prompt / ui_confirm
# accepts the default without reading from /dev/tty. Used by Jenkins
# (test/awsRunEksValidation.sh) and any caller that wants reproducible
# headless runs. Toggled via --non-interactive (or env var).
: "${MIGRATE_NONINTERACTIVE:=0}"

# require_cmd <name> [<install hint>]
# Aborts with an actionable message if <name> is not on PATH.
require_cmd() {
  local cmd="$1" hint="${2:-}"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    printf '\033[31merror:\033[0m required command not found: %s\n' "$cmd" >&2
    [[ -n "$hint" ]] && printf '  hint: %s\n' "$hint" >&2
    exit 127
  fi
}

# die <msg…> — print red error and exit 1.
die() {
  printf '\033[31merror:\033[0m %s\n' "$*" >&2
  exit 1
}

# on_exit_register <fn> — append a cleanup function called on EXIT.
__ON_EXIT_FNS=()
on_exit_register() { __ON_EXIT_FNS[${#__ON_EXIT_FNS[@]}]="$1"; }
__on_exit() {
  local rc=$?
  local fn
  if [[ ${#__ON_EXIT_FNS[@]} -gt 0 ]]; then
    for fn in "${__ON_EXIT_FNS[@]}"; do
      "$fn" "$rc" || true
    done
  fi
  exit "$rc"
}

# The EXIT trap is gated on MIGRATE_OWNS_PROCESS=1 (set by bin/migration-assistant)
# so it doesn't interfere with bats-core, which has its own EXIT-trap chain to
# detect skips and report test results — our trap's `exit "$rc"` would short-
# circuit bats' bookkeeping and the test would appear "not run".

# ---------- Signal handling: Ctrl-C must always work ----------
#
# Without these traps, SIGINT during a long-running background subprocess
# (`aws cloudformation deploy`, `helm install --wait`, `crane copy`) would
# be delivered to the foreground process group's leader (us), but the
# children would keep running. Worse, `wait` swallows SIGINT in bash, so
# Ctrl-C while we're `wait`-ing on a backgrounded child looks dead.
#
# Strategy:
#   * Maintain a registry of active background PIDs (deploy subprocess,
#     event-tail loop, spinner animator) via on_signal_track_pid.
#   * On SIGINT/SIGTERM, kill every tracked PID (and its children),
#     then exit 130. The user sees an interrupt within ~1 second.
#   * Modules that fork subprocesses MUST call on_signal_track_pid
#     immediately after the `&` so the trap can find them.

__SIG_TRACKED_PIDS=()

on_signal_track_pid() {
  __SIG_TRACKED_PIDS[${#__SIG_TRACKED_PIDS[@]}]="$1"
}

on_signal_untrack_pid() {
  local pid="$1" i new=()
  for ((i=0; i < ${#__SIG_TRACKED_PIDS[@]}; i++)); do
    [[ "${__SIG_TRACKED_PIDS[$i]}" == "$pid" ]] && continue
    new[${#new[@]}]="${__SIG_TRACKED_PIDS[$i]}"
  done
  __SIG_TRACKED_PIDS=("${new[@]+"${new[@]}"}")
}

__on_signal() {
  local sig="$1"
  printf '\n\033[33m! interrupted by SIG%s — killing child processes\033[0m\n' "$sig" >&2

  if [[ ${#__SIG_TRACKED_PIDS[@]} -gt 0 ]]; then
    local pid
    for pid in "${__SIG_TRACKED_PIDS[@]}"; do
      [[ -z "$pid" ]] && continue
      # First TERM the process group, then a hard KILL after 1s if it
      # didn't go down cleanly. The "-" prefix on the pid signals the
      # whole pgroup so child grandchildren go too.
      kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    done
    sleep 1
    for pid in "${__SIG_TRACKED_PIDS[@]}"; do
      [[ -z "$pid" ]] && continue
      if kill -0 "$pid" 2>/dev/null; then
        kill -KILL "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
      fi
    done
  fi

  # Bash has its own SIGINT semantics inside built-ins (`wait`, `read`).
  # Exit 130 explicitly so the EXIT trap fires the on_exit chain in turn.
  exit 130
}

# Only the entry-point binary (bin/migration-assistant) sets
# MIGRATE_OWNS_PROCESS=1 — that gate keeps the signal trap from interfering
# with bats-core (which uses its own SIGINT semantics) and any shell-script
# that sources our libs without wanting global signal hijacking.
if [[ "${MIGRATE_OWNS_PROCESS:-0}" == "1" ]]; then
  trap __on_exit            EXIT
  trap '__on_signal INT'    INT
  trap '__on_signal TERM'   TERM
fi

# arch_os — print "<os>/<arch>" using the convention used by binary releases:
#   os:   linux | darwin | windows
#   arch: amd64 | arm64
arch_os() {
  local os arch
  case "$(uname -s)" in
    Linux)   os=linux ;;
    Darwin)  os=darwin ;;
    MINGW*|MSYS*|CYGWIN*) os=windows ;;
    *) die "unsupported OS: $(uname -s)" ;;
  esac
  case "$(uname -m)" in
    x86_64|amd64) arch=amd64 ;;
    arm64|aarch64) arch=arm64 ;;
    *) die "unsupported arch: $(uname -m)" ;;
  esac
  printf '%s/%s\n' "$os" "$arch"
}

# stage_dir_init — create the stage subtree if missing. Idempotent.
stage_dir_init() {
  mkdir -p \
    "$STAGE_DIR" \
    "$STAGE_DIR/log" \
    "$STAGE_DIR/artifacts" \
    "$STAGE_DIR/artifacts/.cache" \
    "$STAGE_DIR/skills" \
    "$STAGE_DIR/plan" \
    "$STAGE_DIR/history" \
    "$STAGE_DIR/archive"
}
