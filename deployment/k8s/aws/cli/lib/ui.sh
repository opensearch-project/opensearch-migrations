#!/usr/bin/env bash
# ui.sh — terminal UI primitives. Color, prompts, spinner, banners.
#
# Design rules:
#   * No external deps. Everything is printf + tput.
#   * If stderr is not a TTY, drop colors silently (CI-safe output).
#   * Prompts read from /dev/tty when stdin is not a TTY (curl-piped
#     installer scenario), so install.sh can still ask questions.
#
# *** CRITICAL: ALL UI CHROME GOES TO STDERR. ***
#
# Functions like ui_info / ui_ok / ui_step / ui_dim / ui_banner / ui_table
# write only to stderr. Stdout is reserved for *return values* — the chosen
# mode, the resolved version, the PID of a spinner, etc.
#
# Why: callers like `mode=$(_select_mode)` or `pid=$(ui_spinner_start …)`
# capture stdout. If chrome leaks to stdout, the captured value gets
# polluted ("\n▶ Choose driver\nManual" instead of "Manual") and the next
# `case` falls through. This rule eliminates that whole class of bug.
#
# Tests in test/test_stdout_discipline.bats enforce the rule.

[[ -n "${__MIGRATE_UI_LOADED:-}" ]] && return 0
__MIGRATE_UI_LOADED=1

# Detect color support once. We check stderr (fd 2) since that's where chrome
# goes; if stderr is a TTY we render colors, otherwise plain.
if [[ -t 2 ]] && command -v tput >/dev/null 2>&1 && [[ "$(tput colors 2>/dev/null || echo 0)" -ge 8 ]]; then
  __UI_C_RESET=$(tput sgr0)
  __UI_C_RED=$(tput setaf 1)
  __UI_C_GREEN=$(tput setaf 2)
  __UI_C_YELLOW=$(tput setaf 3)
  __UI_C_BLUE=$(tput setaf 4)
  __UI_C_CYAN=$(tput setaf 6)
  __UI_C_DIM=$(tput dim)
  __UI_C_BOLD=$(tput bold)
else
  __UI_C_RESET=''
  __UI_C_RED=''
  __UI_C_GREEN=''
  __UI_C_YELLOW=''
  __UI_C_BLUE=''
  __UI_C_CYAN=''
  __UI_C_DIM=''
  __UI_C_BOLD=''
fi

# Every chrome helper writes to stderr — see file header for the rule.
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
# never returns the prompt text, the default hint, or any chrome on stdout.
ui_prompt() {
  local prompt="$1" default="$2" varname="${3:-}"
  local answer prompt_str
  if [[ -n "$default" ]]; then
    prompt_str=$(printf '%s%s%s [%s]: ' "$__UI_C_BOLD" "$prompt" "$__UI_C_RESET" "$default")
  else
    prompt_str=$(printf '%s%s%s: ' "$__UI_C_BOLD" "$prompt" "$__UI_C_RESET")
  fi
  # Non-interactive mode: skip the read, always use the default.
  # This is what makes Jenkins / CI runs reproducible — every prompt
  # auto-answers with whatever the wizard would have suggested.
  if [[ "${MIGRATE_NONINTERACTIVE:-0}" == "1" ]]; then
    answer="$default"
  else
    # Try /dev/tty first; if the write actually fails (e.g. sandbox or
    # piped-stdin context where tty isn't usable), fall back to stderr.
    if ! { printf '%s' "$prompt_str" >/dev/tty; } 2>/dev/null; then
      printf '%s' "$prompt_str" >&2
    fi

    # Likewise for input: prefer /dev/tty, fall back to stdin if that fails.
    #
    # IMPORTANT: with `set -o pipefail` + `set -e` + `if ! { … }`, a
    # non-zero `read` rc inside the brace group can leave the function
    # in a state where `$answer` looks unset on bash 3.2 even though
    # the user typed something. Pull `read` out of the if-condition
    # and check rc explicitly.
    answer=""
    local rd_rc=0
    {
      IFS= read -r answer </dev/tty
      rd_rc=$?
    } 2>/dev/null || rd_rc=$?
    if (( rd_rc != 0 )); then
      # /dev/tty wasn't readable — fall back to stdin.
      IFS= read -r answer 2>/dev/null || answer=""
    fi
    if [[ "${MIGRATE_DEBUG_PROMPT:-0}" -eq 1 ]]; then
      printf '  [debug] ui_prompt: rd_rc=%s answer=[%s] (len=%s)\n' \
        "$rd_rc" "$answer" "${#answer}" >&2
    fi
    [[ -z "${answer:-}" ]] && answer="$default"
  fi

  if [[ -n "$varname" ]]; then
    printf -v "$varname" '%s' "$answer"
  else
    printf '%s\n' "$answer"
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

  # Lowercase first letter for the comparison. Bash 3.2 has no ${var,,}.
  local first
  first=$(printf '%s' "$answer" | cut -c1 | tr '[:upper:]' '[:lower:]')
  case "$first" in
    y) return 0 ;;
    n) return 1 ;;
    *) return 1 ;;   # unknown input — be conservative
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
      sleep 0.08
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
  printf '\r\033[K' >&2
}
