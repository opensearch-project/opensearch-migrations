#!/usr/bin/env bash
# ui.sh — terminal UI primitives. Color, prompts, spinner, banners.
#
# Design rules:
#   * No external deps. Everything is printf — no tput, no ncurses.
#     Color constants come from lib/term.sh (raw ANSI SGR via $'\e[…m'),
#     which avoids the 8 cold-start subshells the old tput block forked
#     and the latent `tput dim`-returns-non-zero crash on minimal
#     terminfo (busybox, dumb terminals, Jenkins agents).
#   * If stderr is not a TTY, drop colors silently (CI-safe output).
#   * Prompts read from /dev/tty when stdin is not a TTY (curl-piped
#     installer scenario), so install.sh can still ask questions.
#
# *** CRITICAL: ALL UI OUTPUT GOES TO STDERR. ***
#
# Functions like ui_info / ui_ok / ui_step / ui_dim / ui_banner / ui_table
# write only to stderr. Stdout is reserved for *return values* — the chosen
# mode, the resolved version, the PID of a spinner, etc.
#
# Why: callers like `mode=$(_select_mode)` or `pid=$(ui_spinner_start …)`
# capture stdout. If UI text leaks to stdout, the captured value gets
# polluted ("\n▶ Choose driver\nManual" instead of "Manual") and the next
# `case` falls through. This rule eliminates that whole class of bug.
#
# Tests in test/test_stdout_discipline.bats enforce the rule.

[[ -n "${__MIGRATE_UI_LOADED:-}" ]] && return 0
__MIGRATE_UI_LOADED=1

# ui.sh has a hard dependency on term.sh (color constants, cursor mgmt,
# tty detection). Source it defensively so callers — including bats
# tests that load _common.sh + ui.sh directly — get a working color
# table without each of them having to know about term.sh.
# shellcheck source=lib/term.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/term.sh"

# term.sh owns terminal detection, color constants, and cursor management.
# We re-export the __TERM_C_* constants under their legacy __UI_C_* names
# so existing call sites in cfn.sh / dashboard.sh / cleanup.sh / helm.sh
# keep working unchanged. New code should reach for term.sh directly.
term_init
__UI_C_RESET="$__TERM_C_RESET"
__UI_C_RED="$__TERM_C_RED"
__UI_C_GREEN="$__TERM_C_GREEN"
__UI_C_YELLOW="$__TERM_C_YELLOW"
__UI_C_BLUE="$__TERM_C_BLUE"
__UI_C_CYAN="$__TERM_C_CYAN"
__UI_C_DIM="$__TERM_C_DIM"
__UI_C_BOLD="$__TERM_C_BOLD"

# Every UI helper writes to stderr — see file header for the rule.
# Embed clickable hyperlinks in any UI message via $(term_link URL LABEL).
# OSC 8 is rendered by iTerm2, kitty, Wezterm, vscode, recent gnome-terminal;
# older terminals see plain text and don't break.
ui_info()    { printf '%s%s%s %s\n' "$__UI_C_CYAN"   "ℹ"  "$__UI_C_RESET" "$*" >&2; }
ui_ok()      { printf '%s%s%s %s\n' "$__UI_C_GREEN"  "✓"  "$__UI_C_RESET" "$*" >&2; }
ui_warn()    { printf '%s%s%s %s\n' "$__UI_C_YELLOW" "!"  "$__UI_C_RESET" "$*" >&2; }
ui_err()     { printf '%s%s%s %s\n' "$__UI_C_RED"    "✗"  "$__UI_C_RESET" "$*" >&2; }
ui_step()    { printf '\n%s▶ %s%s\n'      "$__UI_C_BOLD"  "$*" "$__UI_C_RESET" >&2; }
ui_dim()     { printf '%s%s%s\n'        "$__UI_C_DIM"   "$*" "$__UI_C_RESET" >&2; }

ui_banner() {
  local title="$1"
  local len=${#title}
  local bar
  printf -v bar '%*s' "$((len + 4))" ''
  bar=${bar// /━}
  printf '\n%s%s%s\n%s  %s  %s\n%s%s%s\n\n' \
    "$__UI_C_BOLD" "$bar" "$__UI_C_RESET" \
    "$__UI_C_BOLD" "$title" "$__UI_C_RESET" \
    "$__UI_C_BOLD" "$bar" "$__UI_C_RESET" >&2
}

# ui_prompt <prompt> <default> [VAR_NAME]
#
# Behavior:
#   * Question text goes to /dev/tty if available, otherwise stderr — NEVER
#     stdout. (Stdout is reserved for return values.)
#   * Reads input from /dev/tty if readable, otherwise from /dev/stdin
#     (the piped-input case used by tests + the curl-piped installer).
#   * If VAR_NAME is provided, the answer is stored there; otherwise it's
#     printed to stdout for capture via $(…).
#
# An empty input → default value. A read error → default value. The function
# never returns the prompt text, the default hint, or any UI text on stdout.
ui_prompt() {
  local prompt="$1" default="$2" __ui_varname="${3:-}"
  # Locals are prefixed with __ui_ to avoid name-collision with the
  # caller's varname argument. Bash 3.2 `printf -v "$varname"` resolves
  # to whatever the caller passed; if our function declared a `local
  # answer` AND the caller asked us to write into THEIR `answer` via
  # `ui_prompt … answer`, our local shadowed theirs and the result
  # never propagated. (That's the bug ui_confirm hit: caller's
  # `answer` stayed empty even when we'd read 'y' here.) Naming our
  # locals __ui_<x> sidesteps the issue completely — no real-world
  # caller passes a varname that starts with our reserved prefix.
  local __ui_answer __ui_prompt_str __ui_rd_rc=0
  if [[ -n "$default" ]]; then
    __ui_prompt_str=$(printf '%s%s%s [%s]: ' "$__UI_C_BOLD" "$prompt" "$__UI_C_RESET" "$default")
  else
    __ui_prompt_str=$(printf '%s%s%s: ' "$__UI_C_BOLD" "$prompt" "$__UI_C_RESET")
  fi
  # Non-interactive mode: skip the read, always use the default.
  if [[ "${MIGRATE_NONINTERACTIVE:-0}" == "1" ]]; then
    __ui_answer="$default"
  else
    # Try /dev/tty first; fall back to stderr if not writable.
    if ! { printf '%s' "$__ui_prompt_str" >/dev/tty; } 2>/dev/null; then
      printf '%s' "$__ui_prompt_str" >&2
    fi

    # Read from /dev/tty; fall back to stdin if not readable.
    __ui_answer=""
    {
      IFS= read -r __ui_answer </dev/tty
      __ui_rd_rc=$?
    } 2>/dev/null || __ui_rd_rc=$?
    if (( __ui_rd_rc != 0 )); then
      IFS= read -r __ui_answer 2>/dev/null || __ui_answer=""
    fi
    if [[ "${MIGRATE_DEBUG_PROMPT:-0}" -eq 1 ]]; then
      printf '  [debug] ui_prompt: rd_rc=%s answer=[%s] (len=%s)\n' \
        "$__ui_rd_rc" "$__ui_answer" "${#__ui_answer}" >&2
    fi
    [[ -z "${__ui_answer:-}" ]] && __ui_answer="$default"
  fi

  if [[ -n "$__ui_varname" ]]; then
    printf -v "$__ui_varname" '%s' "$__ui_answer"
  else
    printf '%s\n' "$__ui_answer"
  fi
}

# ui_confirm <prompt> <default Y|N>
# Returns 0 for yes, 1 for no.
#
# Behavior:
#   * Empty input (user hit Enter) → use the default.
#   * "y" / "yes" / "Y" / "YES" → yes (return 0).
#   * "n" / "no" / "N" / "NO"   → no  (return 1).
#   * anything else             → no  (safest default).
#
# IMPORTANT: keep the logic flat and obvious. Earlier "clever" two-case
# version had a fall-through path that returned 1 when the user accepted
# the default — visible bug: "Resume? [Y/n]: <Enter>" → "starting over".
ui_confirm() {
  local prompt="$1" default="${2:-Y}"
  local hint default_yn
  case "$default" in
    Y|y|yes) hint='Y/n'; default_yn='y' ;;
    *)       hint='y/N'; default_yn='n' ;;
  esac

  local answer=""
  ui_prompt "$prompt" "$hint" answer

  # If ui_prompt returned the hint string itself, the user just hit Enter
  # and ui_prompt's "default fallback" kicked in — collapse that to the
  # canonical default_yn for the comparison below.
  if [[ "$answer" == "$hint" || -z "$answer" ]]; then
    answer="$default_yn"
  fi

  # Match on first letter, case-insensitive. Bash 3.2 has no ${var,,};
  # earlier versions of this code forked `cut | tr` per call. Pure-pattern
  # case matching is fork-free and identical in behavior.
  case "$answer" in
    [Yy]*) return 0 ;;
    [Nn]*) return 1 ;;
    *)     return 1 ;;   # unknown input — be conservative
  esac
}

# ui_select <prompt> <option1> [<option2> …] → echoes chosen option to stdout
# Numbered list, default 1.
ui_select() {
  local prompt="$1"; shift
  local i=1 choice
  printf '%s%s%s\n' "$__UI_C_BOLD" "$prompt" "$__UI_C_RESET" >&2
  for opt in "$@"; do
    printf '  [%d] %s\n' "$i" "$opt" >&2
    ((i++))
  done
  ui_prompt "Select" "1" choice
  if ! [[ "$choice" =~ ^[0-9]+$ ]] || (( choice < 1 || choice > $# )); then
    ui_err "invalid selection: $choice"
    return 1
  fi
  printf '%s\n' "${!choice}"
}

# ui_table <header_line> <row1> <row2>…
# Renders a simple monospace table. Caller pre-formats columns with tabs.
ui_table() {
  local header="$1"; shift
  printf '%s%s%s\n' "$__UI_C_BOLD" "$header" "$__UI_C_RESET" >&2
  local row
  for row in "$@"; do
    printf '  %s\n' "$row" >&2
  done
}

# ui_spinner_start "Working…" → echoes PID on stdout; ui_spinner_stop <pid>.
# Animation goes to stderr. Stdout is reserved for the PID return value.
# The background animator is registered with the SIGINT/SIGTERM trap so
# Ctrl-C kills it cleanly (without this, the spinner kept animating after
# the operator interrupted and they had to kill -9 the parent).
ui_spinner_start() {
  local msg="$1"
  if [[ ! -t 2 ]]; then
    # Non-TTY: print the message as a static stderr line and return PID 0.
    printf '%s\n' "$msg" >&2
    printf '0\n'
    return
  fi
  (
    # Inside the subshell: ignore SIGINT — the parent's trap will kill us
    # explicitly. This stops bash from leaving zombie animators around.
    trap '' INT
    local frames='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏' i=0
    while :; do
      printf '\r%s %s ' "${frames:$((i % ${#frames})):1}" "$msg" >&2
      i=$((i + 1))
      # 80 ms inter-frame interval, but interruptible: `read -t 0.08`
      # from /dev/null returns immediately on SIGTERM. Plain `sleep
      # 0.08` blocks SIGTERM until the sleep returns, which produced
      # visible half-redrawn glyphs on Ctrl-C.
      read -r -t 0.08 _ </dev/null || true
    done
  ) &
  local pid=$!
  on_signal_track_pid "$pid"
  printf '%d\n' "$pid"
}
ui_spinner_stop() {
  local pid="$1"
  [[ "$pid" -gt 0 ]] || return 0
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  on_signal_untrack_pid "$pid"
  term_clear_line
}
