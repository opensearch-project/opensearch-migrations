#!/usr/bin/env bash
# log.sh — append-only log to $STAGE_DIR/log/migrate.log + stream tee.
#
# Invariants:
#   * Logs to file always; mirrors to stderr only when --verbose is set.
#   * Single rotation when log > 5 MiB → migrate.log.1 (overwrites prior .1).
#   * Safe to call before stage_dir_init (no-ops to /dev/null until ready).
#   * The log path is announced on startup and on exit so the operator can
#     `tail -f` it from a second terminal.
#
# Public API:
#   log_init                    Initialise the log file (rotates if needed).
#   log_announce                Print the log path on stderr (called by the
#                               banner; also re-printed on graceful exit).
#   log_info / log_warn /
#   log_error / log_debug       Append a level-prefixed line.
#   log_stream <prefix> CMD …   Run CMD; tee its stdout+stderr live to the
#                               log file AND to the operator's stderr,
#                               prefixed with <prefix>. Returns CMD's
#                               exit code via $? (and via the function's
#                               own exit code).

[[ -n "${__MIGRATE_LOG_LOADED:-}" ]] && return 0
__MIGRATE_LOG_LOADED=1

: "${MIGRATE_VERBOSE:=0}"
LOG_FILE="${LOG_FILE:-/dev/null}"

log_init() {
  stage_dir_init
  LOG_FILE="$STAGE_DIR/log/migrate.log"
  if [[ -f "$LOG_FILE" ]]; then
    local sz
    sz=$(wc -c <"$LOG_FILE" 2>/dev/null | tr -d ' ' || echo 0)
    if [[ "$sz" -gt 5242880 ]]; then
      mv -f "$LOG_FILE" "$LOG_FILE.1"
    fi
  fi
  : >>"$LOG_FILE"
}

# log_announce — tell the operator where the log is. Called by the banner
# at startup and registered as an on_exit handler so it's the last thing
# printed even on a normal exit. Stderr-only — never pollutes stdout.
log_announce() {
  [[ "$LOG_FILE" == "/dev/null" || -z "$LOG_FILE" ]] && return 0
  if [[ "${1:-}" == "--exit" ]]; then
    printf '\n%sLog: %s%s\n' "$__UI_C_DIM" "$LOG_FILE" "$__UI_C_RESET" >&2
    [[ -n "${MIGRATE_VERBOSE:-}" && "$MIGRATE_VERBOSE" -eq 1 ]] || \
      printf '%s  Re-run with --verbose for live log mirroring.%s\n' "$__UI_C_DIM" "$__UI_C_RESET" >&2
  else
    printf '%s  log: %s  (tail -f to follow in another terminal)%s\n' \
      "$__UI_C_DIM" "$LOG_FILE" "$__UI_C_RESET" >&2
  fi
}

# log_<level> <msg…>
log() {
  local lvl="$1"; shift
  local ts
  ts=$(date '+%Y-%m-%dT%H:%M:%S%z')
  printf '[%s] %s %s\n' "$ts" "$lvl" "$*" >>"$LOG_FILE"
  if [[ "$MIGRATE_VERBOSE" -eq 1 ]]; then
    printf '[%s] %s %s\n' "$ts" "$lvl" "$*" >&2
  fi
}

log_info()  { log INFO  "$@"; }
log_warn()  { log WARN  "$@"; }
log_error() { log ERROR "$@"; }
log_debug() { [[ "$MIGRATE_VERBOSE" -eq 1 ]] && log DEBUG "$@"; }

# log_announce_exit — on_exit_register hook: print the log path one more
# time as the very last operator-visible line. Receives <rc> as $1.
log_announce_exit() {
  log_announce --exit
}

# log_stream <prefix> CMD ARGS… — run a long-running command and tee its
# combined output to the log file AND stderr, one prefixed line at a time.
#
# Why this exists: `aws cloudformation deploy` (and helm install, crane
# copy, kubectl wait) can run for many minutes. The operator needs *some*
# sign of progress. Previously we ran them under a spinner and discarded
# output — failures were invisible until the final non-zero exit.
#
# Behavior:
#   * CMD's stdout AND stderr → log file, line-buffered, ts-prefixed.
#   * Same lines → operator's stderr, with <prefix> | live-streamed.
#   * Exit code propagated.
#
# Usage:
#   log_stream "cfn" aws cloudformation deploy --stack-name foo …
log_stream() {
  local prefix="$1"; shift
  local rc=0

  # We need to capture the child's exit code through a pipe. mkfifo gives
  # us a clean way to drive two readers (file logger + stderr printer)
  # off the same byte stream without subshell rc games.
  local fifo
  fifo=$(mktemp -u "$STAGE_DIR/log/.stream.$$.XXXXXX")
  mkfifo "$fifo"

  # Reader 1: append every line to the log file, prefixed with timestamp.
  # Ignore SIGINT inside the reader subshell — the parent's trap will kill
  # us explicitly so we don't leave a dangling reader on Ctrl-C.
  ( trap '' INT
    while IFS= read -r line; do
      printf '[%s] STREAM[%s] %s\n' \
        "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$prefix" "$line" >>"$LOG_FILE"
      printf '%s  %s│%s %s\n' "$__UI_C_DIM" "$prefix" "$__UI_C_RESET" "$line" >&2
    done <"$fifo"
  ) &
  local reader_pid=$!
  on_signal_track_pid "$reader_pid"

  # Run the command, redirect 2>&1 into the fifo. set +e for this scope so
  # we can capture the rc instead of bailing on a non-zero exit.
  set +e
  "$@" >"$fifo" 2>&1
  rc=$?
  set -e

  wait "$reader_pid" 2>/dev/null || true
  on_signal_untrack_pid "$reader_pid"
  rm -f "$fifo"

  log_info "stream[$prefix] exit=$rc"
  return $rc
}
