#!/usr/bin/env bash
# term.sh — terminal control primitives. Raw ANSI/VT100 only. No tput.
#
# Why no tput:
#   * Each tput call forks ncurses + does a terminfo lookup (~10-15 ms).
#     ui.sh:28-46 forks 8 subshells at module load — ~120 ms cold start
#     before the operator sees the banner.
#   * `tput dim` exits non-zero on minimal terminfo databases (busybox,
#     containers, Jenkins agents with TERM=dumb). Combined with `set -e`,
#     that's a startup crash latent in PR 3022 (ui.sh:35).
#   * Raw VT100 escapes are baked into every terminal emulator that runs
#     a shell in 2026 — predictable, dependency-free, composable inside
#     printf format strings without escape-of-escape headaches.
#
# Contract:
#   * setup and reset are EXACTLY inverse, each one printf, registered as
#     a DEDICATED EXIT trap (separate from on_exit_register) that runs
#     FIRST. This eliminates the cursor-stuck-hidden bug class — anything
#     that hides the cursor goes through term.sh, and the trap unconditionally
#     restores it on ANY exit path (Ctrl-C, set -e, exec replace, normal).
#
#   * EVERYTHING goes to stderr — stdout is reserved for return values
#     (per the ui.sh discipline rule). Tests in test_stdout_discipline.bats
#     enforce this.
#
#   * No-op gracefully when stderr isn't a TTY (CI logs, piped output).
#     Detection is `[[ -t 2 && ${TERM:-dumb} != dumb ]]` — no fork.
#
# Cursor management is OWNED by term.sh. Other libs MUST NOT emit
# `\033[?25l` directly; use term_hide_cursor() / term_show_cursor().

[[ -n "${__MIGRATE_TERM_LOADED:-}" ]] && return 0
__MIGRATE_TERM_LOADED=1

# ---------- detection + state -----------------------------------------------

# Set in term_init based on stderr-is-a-TTY + TERM != dumb.
# When 0, every emit-helper is a no-op.
__TERM_INTERACTIVE=0

# Cached terminal geometry. Updated on SIGWINCH.
__TERM_LINES=24
__TERM_COLUMNS=80

# Current cursor-hide state, so term_hide_cursor is idempotent and the
# reset trap doesn't double-emit show.
__TERM_CURSOR_HIDDEN=0

# Color constants. Set in term_init. 8-color ANSI SGR. Truecolor /
# 256-color sidestepped: this is an installer, the eight colors are
# sufficient and CI-log-safe.
__TERM_C_RESET=''
__TERM_C_RED=''
__TERM_C_GREEN=''
__TERM_C_YELLOW=''
__TERM_C_BLUE=''
__TERM_C_CYAN=''
__TERM_C_MAGENTA=''
__TERM_C_DIM=''
__TERM_C_BOLD=''

# ---------- init / reset ---------------------------------------------------

# term_init — one-time setup. Detect TTY, populate color constants,
# install dedicated EXIT trap for terminal reset, install SIGWINCH handler.
# Idempotent — safe to call repeatedly.
term_init() {
  if [[ -n "${__TERM_INIT_DONE:-}" ]]; then return 0; fi
  __TERM_INIT_DONE=1

  # Interactive iff stderr is a real terminal AND TERM is not 'dumb' (which
  # CI runners and some IDE consoles set). $TERM unset → treat as dumb.
  if [[ -t 2 && "${TERM:-dumb}" != "dumb" ]]; then
    __TERM_INTERACTIVE=1
    # Raw ANSI SGR codes. $'…' converts \e to a real ESC byte.
    __TERM_C_RESET=$'\e[0m'
    __TERM_C_RED=$'\e[31m'
    __TERM_C_GREEN=$'\e[32m'
    __TERM_C_YELLOW=$'\e[33m'
    __TERM_C_BLUE=$'\e[34m'
    __TERM_C_MAGENTA=$'\e[35m'
    __TERM_C_CYAN=$'\e[36m'
    __TERM_C_DIM=$'\e[2m'
    __TERM_C_BOLD=$'\e[1m'

    # Initial geometry read. SIGWINCH handler refreshes on resize.
    _term_winch
    trap '_term_winch' WINCH

    # DEDICATED EXIT trap — runs FIRST regardless of any other registered
    # callbacks. We deliberately install this directly via `trap` (not via
    # on_exit_register) so it runs before _common.sh's chain — that chain
    # is gated on MIGRATE_OWNS_PROCESS=1, which means a sourced lib (bats
    # test, future tooling) skips it entirely and would leave the cursor
    # hidden. Direct trap is unconditional.
    trap '_term_reset' EXIT
  fi
}

# _term_winch — update LINES + COLUMNS on resize. Cheap: one `stty size`
# call. Pure-bash `shopt -s checkwinsize` is bash 4+, so we use stty.
_term_winch() {
  local size
  size=$(stty size 2>/dev/null) || return 0
  if [[ "$size" =~ ^([0-9]+)\ ([0-9]+)$ ]]; then
    __TERM_LINES="${BASH_REMATCH[1]}"
    __TERM_COLUMNS="${BASH_REMATCH[2]}"
  fi
}

# _term_reset — single-printf restore. Inverse of every state mutation
# term.sh might have made. Idempotent — safe to call multiple times.
# Always restores cursor + line-wrap; restoring more requires balancing
# state, but cursor + wrap are global and benign to "always restore".
_term_reset() {
  # Even when not interactive, this is a no-op; emit anyway for safety.
  printf '\e[?25h\e[?7h' >&2
  __TERM_CURSOR_HIDDEN=0
}

# term_interactive — returns 0 iff term_init detected a real TTY.
# Use to gate any visual chrome that's not handled internally by term.sh.
term_interactive() {
  (( __TERM_INTERACTIVE == 1 ))
}

# ---------- cursor + line control ------------------------------------------

# term_hide_cursor — hide via \e[?25l. Idempotent. Tracked so the dedicated
# EXIT trap can restore unconditionally without double-emitting.
term_hide_cursor() {
  (( __TERM_INTERACTIVE )) || return 0
  if (( __TERM_CURSOR_HIDDEN == 0 )); then
    printf '\e[?25l' >&2
    __TERM_CURSOR_HIDDEN=1
  fi
}

# term_show_cursor — show. Most callers should NOT call this directly;
# the dedicated EXIT trap handles it. Useful when you want to flash the
# cursor between rendering phases.
term_show_cursor() {
  (( __TERM_INTERACTIVE )) || return 0
  printf '\e[?25h' >&2
  __TERM_CURSOR_HIDDEN=0
}

# term_save_cursor — \e7 (DEC). Bracket transient overlays.
term_save_cursor()    { (( __TERM_INTERACTIVE )) && printf '\e7' >&2; return 0; }

# term_restore_cursor — \e8.
term_restore_cursor() { (( __TERM_INTERACTIVE )) && printf '\e8' >&2; return 0; }

# term_clear_line — clear current line + carriage return.
# Replaces ad-hoc '\r\033[K' at ui.sh:232 and similar.
term_clear_line() {
  (( __TERM_INTERACTIVE )) && printf '\e[2K\r' >&2
  return 0
}

# term_lines / term_columns — accessor for the WINCH-tracked geometry.
# Echoes the current value. Replaces dashboard.sh's hard-coded
# DASH_BAR_WIDTH=24 (which doesn't track resize).
term_lines()   { printf '%s' "$__TERM_LINES"; }
term_columns() { printf '%s' "$__TERM_COLUMNS"; }

# ---------- presentation primitives ----------------------------------------

# term_link <url> [<label>]
# Emit an OSC 8 hyperlink. iTerm2, kitty, Wezterm, recent gnome-terminal,
# vscode integrated terminal all render this as clickable. Older terminals
# silently print the label.
#
# When stderr is not a TTY, fall back to "label (url)" plain text so CI
# logs and piped outputs are still readable.
#
# Used by ui_err / ui_warn / cleanup confirmation. The label is the visible
# text; default to the URL if omitted.
term_link() {
  local url="$1" label="${2:-$1}"
  if (( __TERM_INTERACTIVE )); then
    # OSC 8 ; ; <url> ST  <label>  OSC 8 ; ; ST
    # ST is ESC \ (\e\\). We use \e\\ — literal escape + backslash.
    # shellcheck disable=SC1003 # \e\\ is literal escape + backslash, not a ' escape
    printf '\e]8;;%s\e\\%s\e]8;;\e\\' "$url" "$label"
  else
    if [[ "$url" == "$label" ]]; then
      printf '%s' "$url"
    else
      printf '%s (%s)' "$label" "$url"
    fi
  fi
}

# term_panel <title> [<line>...]
# Render a UTF-8 box panel with a title and one line per remaining arg.
# Width is clamped to columns - 2. Lines longer than the inner width are
# truncated with an ellipsis (matches dashboard.sh:235-237 pattern).
#
# Used by cleanup confirmation (lists what's about to be deleted) and
# wizard summary.
#
# Falls back to plain "title:" + "  line" formatting when not interactive.
term_panel() {
  local title="$1"; shift
  if ! (( __TERM_INTERACTIVE )); then
    printf '%s:\n' "$title" >&2
    local plain_line
    for plain_line in "$@"; do
      printf '  %s\n' "$plain_line" >&2
    done
    return 0
  fi

  # Inner width budget. Clamp to a sane minimum (40) so very narrow
  # terminals still get a readable panel.
  local cols=$__TERM_COLUMNS
  (( cols < 50 )) && cols=50
  local inner=$(( cols - 4 ))

  # Title + horizontal bars.
  local title_visible=" ${title} "
  if (( ${#title_visible} > inner - 2 )); then
    # Title doesn't fit between corners — truncate.
    local cut=$(( inner - 5 ))
    (( cut < 4 )) && cut=4
    title_visible=" ${title:0:$cut}… "
  fi
  local title_pad=$(( inner - ${#title_visible} ))
  (( title_pad < 0 )) && title_pad=0

  # Top: ┏━{title}━━━━━━━┓
  local hbar
  printf -v hbar '%*s' "$title_pad" ''
  hbar=${hbar// /━}
  printf '%s┏%s%s┓%s\n' \
    "$__TERM_C_BOLD" "$title_visible" "$hbar" "$__TERM_C_RESET" >&2

  # Body lines.
  local line short pad
  for line in "$@"; do
    short=$line
    if (( ${#short} > inner )); then
      short="${short:0:$(( inner - 1 ))}…"
    fi
    pad=$(( inner - ${#short} ))
    (( pad < 0 )) && pad=0
    printf -v hbar '%*s' "$pad" ''
    printf '%s┃%s %s%s%s ┃%s\n' \
      "$__TERM_C_BOLD" "$__TERM_C_RESET" \
      "$short" "$hbar" \
      "$__TERM_C_BOLD" "$__TERM_C_RESET" >&2
  done

  # Bottom: ┗━━━━━━━━━━━━━┛
  printf -v hbar '%*s' "$inner" ''
  hbar=${hbar// /━}
  printf '%s┗%s┛%s\n' \
    "$__TERM_C_BOLD" "$hbar" "$__TERM_C_RESET" >&2
}

# term_progress <current> <total> [<msg>]
# Sticky bottom progress line. Uses \e7 (save cursor) → jump to LINES →
# \e[2K (clear line) → paint → \e8 (restore cursor) so concurrent stderr
# writes from helm/aws/kubectl above the line aren't disturbed.
#
# Standard sticky-bottom-line VT100 pattern (\e7 save + jump + paint +
# \e8 restore). Survives `helm install --wait` streaming output above
# the line without flicker.
#
# Falls back to a plain `[cur/total] msg\n` line when not interactive (CI
# logs see one progress line per call, which is what they want).
term_progress() {
  local cur="$1" total="$2" msg="${3:-}"
  if ! (( __TERM_INTERACTIVE )); then
    printf '[%d/%d] %s\n' "$cur" "$total" "$msg" >&2
    return 0
  fi

  # Pct + 24-wide bar.
  local seen=$(( total > 0 ? total : 1 ))
  local pct=$(( (cur * 100) / seen ))
  local bar_w=24
  local fill=$(( (cur * bar_w) / seen ))
  (( fill > bar_w )) && fill=$bar_w
  local empty=$(( bar_w - fill ))

  local filled_str empty_str
  printf -v filled_str '%*s' "$fill"  ''
  printf -v empty_str  '%*s' "$empty" ''
  filled_str=${filled_str// /█}
  empty_str=${empty_str// /░}

  # Single printf; one syscall.
  printf '\e7\e[%dH\e[2K  %s%s%s%s%s  %d%%  [%d/%d]  %s\e8' \
    "$__TERM_LINES" \
    "$__TERM_C_GREEN" "$filled_str" \
    "$__TERM_C_DIM"   "$empty_str" \
    "$__TERM_C_RESET" \
    "$pct" "$cur" "$total" "$msg" >&2
}

# term_set_title <text>
# OSC 0/2 — set the terminal tab/window title. Cheap, harmless on terminals
# that ignore it. Useful for long-running deploys: helm-install at 7m,
# operator's tab reads "migration-assistant: helm-install".
term_set_title() {
  (( __TERM_INTERACTIVE )) || return 0
  # OSC 2 sets the window title; OSC 0 sets both window + icon. Use 0 for
  # broader compatibility (tmux, screen, kitty, iTerm2).
  # shellcheck disable=SC1003 # \e\\ is literal escape + backslash, not a ' escape
  printf '\e]0;%s\e\\' "$1" >&2
}

# ---------- spinner glyph (no animator loop) -------------------------------

# term_spinner_frame <tick> [<glyph_set>]
# Echo one glyph from the braille spinner sequence. The animator loop
# stays in ui.sh; this is the per-frame glyph picker so it can be tested
# independently and shared with timeline.sh / dashboard.sh.
__TERM_SPINNER='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
term_spinner_frame() {
  local tick="$1" set="${2:-$__TERM_SPINNER}"
  local idx=$(( tick % ${#set} ))
  printf '%s' "${set:$idx:1}"
}
