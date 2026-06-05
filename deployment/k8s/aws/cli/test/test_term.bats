#!/usr/bin/env bats
# test_term.bats — coverage for lib/term.sh.
#
# term.sh decisions tested:
#   * No tput / ncurses fork at module load (regression for ui.sh:28-46).
#   * Cursor hide is idempotent and tracked by __TERM_CURSOR_HIDDEN.
#   * The dedicated EXIT trap unconditionally restores cursor + line wrap.
#   * Hyperlink emits OSC 8 when interactive, plain "(label) (url)" otherwise.
#   * Panel renders UTF-8 box chars when interactive, plain "title:" + "  body" otherwise.
#   * Progress bar uses save/restore (\e7/\e8) for sticky-bottom behaviour.
#   * SIGWINCH refreshes geometry from `stty size`.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh std.sh term.sh
  # Force interactive mode for the visual-emit tests. Real callers go
  # through term_init which auto-detects.
  __TERM_INTERACTIVE=1
  __TERM_LINES=24
  __TERM_COLUMNS=80
  __TERM_C_RESET=$'\e[0m'
  __TERM_C_BOLD=$'\e[1m'
  __TERM_C_GREEN=$'\e[32m'
  __TERM_C_DIM=$'\e[2m'
  __TERM_C_RED=$'\e[31m'
  __TERM_C_YELLOW=$'\e[33m'
  __TERM_C_CYAN=$'\e[36m'
}

teardown() {
  teardown_isolated_home
}

capture_stderr() {
  # Run a function and return its stderr WITHOUT subshell-isolating
  # variable mutations the function makes. tmpfile is the only way.
  local tf=$(mktemp)
  "$@" 2>"$tf"
  STDERR=$(<"$tf")
  rm -f "$tf"
}

# ---------- detection / no-tput regression ----------

@test "term.sh sources without forking tput" {
  # Re-source under a sentinel that fails if tput is invoked.
  # We can't easily prove the negative inside one process, so instead
  # check that all __TERM_C_* color constants are set to either '' or
  # raw ANSI escape sequences (\e[…m) — never to tput's binary output.
  for v in __TERM_C_RESET __TERM_C_RED __TERM_C_GREEN __TERM_C_YELLOW \
           __TERM_C_BLUE __TERM_C_CYAN __TERM_C_BOLD __TERM_C_DIM; do
    val="${!v}"
    [[ -z "$val" || "$val" == $'\e['* ]] || {
      echo "color constant $v is not raw ANSI (got: ${val@Q})" >&2
      return 1
    }
  done
}

@test "term_interactive returns success when __TERM_INTERACTIVE=1" {
  __TERM_INTERACTIVE=1
  term_interactive
  __TERM_INTERACTIVE=0
  ! term_interactive
}

# ---------- cursor management ----------

@test "term_hide_cursor emits CSI ?25l and tracks state" {
  __TERM_CURSOR_HIDDEN=0
  capture_stderr term_hide_cursor
  [[ "$STDERR" == *$'\e[?25l'* ]]
  [ "$__TERM_CURSOR_HIDDEN" -eq 1 ]
}

@test "term_hide_cursor is idempotent — second call emits nothing" {
  __TERM_CURSOR_HIDDEN=0
  capture_stderr term_hide_cursor
  [ -n "$STDERR" ]
  capture_stderr term_hide_cursor
  [ -z "$STDERR" ]
}

@test "term_save_cursor / term_restore_cursor emit \\e7 / \\e8" {
  capture_stderr term_save_cursor
  [ "$STDERR" = $'\e7' ]
  capture_stderr term_restore_cursor
  [ "$STDERR" = $'\e8' ]
}

@test "term_clear_line emits \\e[2K\\r" {
  capture_stderr term_clear_line
  [ "$STDERR" = $'\e[2K\r' ]
}

@test "term_show_cursor + dedicated EXIT trap restore the cursor unconditionally" {
  __TERM_CURSOR_HIDDEN=1
  capture_stderr term_show_cursor
  [[ "$STDERR" == *$'\e[?25h'* ]]
  [ "$__TERM_CURSOR_HIDDEN" -eq 0 ]
  # _term_reset (the trap target) ALSO restores cursor + wrap.
  capture_stderr _term_reset
  [[ "$STDERR" == *$'\e[?25h'* ]]
  [[ "$STDERR" == *$'\e[?7h'* ]]
}

# ---------- autowrap (DECAWM) ----------
#
# The sticky dashboard disables autowrap so its logical-line redraw count
# matches the physical rows on screen (a wrapped line would otherwise leave
# a ghost frame behind on every poll). These mirror the cursor-hide tests.

@test "term_wrap_off emits CSI ?7l and tracks state" {
  __TERM_WRAP_DISABLED=0
  capture_stderr term_wrap_off
  [[ "$STDERR" == *$'\e[?7l'* ]]
  [ "$__TERM_WRAP_DISABLED" -eq 1 ]
}

@test "term_wrap_off is idempotent — second call emits nothing" {
  __TERM_WRAP_DISABLED=0
  capture_stderr term_wrap_off
  [ -n "$STDERR" ]
  capture_stderr term_wrap_off
  [ -z "$STDERR" ]
}

@test "term_wrap_on emits CSI ?7h and clears the tracked state" {
  __TERM_WRAP_DISABLED=1
  capture_stderr term_wrap_on
  [[ "$STDERR" == *$'\e[?7h'* ]]
  [ "$__TERM_WRAP_DISABLED" -eq 0 ]
}

@test "term_wrap_off is a no-op when not interactive" {
  __TERM_INTERACTIVE=0
  __TERM_WRAP_DISABLED=0
  capture_stderr term_wrap_off
  [ -z "$STDERR" ]
  [ "$__TERM_WRAP_DISABLED" -eq 0 ]
}

@test "_term_reset clears the autowrap-disabled flag" {
  __TERM_WRAP_DISABLED=1
  capture_stderr _term_reset
  [ "$__TERM_WRAP_DISABLED" -eq 0 ]
}

# ---------- geometry ----------

@test "term_lines / term_columns echo the cached geometry" {
  __TERM_LINES=42
  __TERM_COLUMNS=120
  [ "$(term_lines)" = "42" ]
  [ "$(term_columns)" = "120" ]
}

@test "_term_winch reads from stty size when present" {
  # stty is always available on macOS+linux; if not, skip.
  if ! command -v stty >/dev/null 2>&1; then
    skip "stty not on PATH"
  fi
  # bats runs without a controlling tty, so `stty size` may fail. The
  # contract is "_term_winch updates LINES/COLUMNS when stty succeeds AND
  # leaves them alone when it fails". Skip when bats has no tty.
  if ! stty size </dev/tty >/dev/null 2>&1; then
    skip "no controlling tty (bats default)"
  fi
  __TERM_LINES=0
  __TERM_COLUMNS=0
  _term_winch
  [ "$__TERM_LINES" -gt 0 ]
  [ "$__TERM_COLUMNS" -gt 0 ]
}

# ---------- hyperlinks ----------

@test "term_link emits OSC 8 hyperlink when interactive" {
  __TERM_INTERACTIVE=1
  out=$(term_link "https://example.com" "click me")
  # OSC 8 sequence must be present.
  [[ "$out" == *$'\e]8;;https://example.com\e\\click me\e]8;;\e\\'* ]]
}

@test "term_link falls back to 'label (url)' when not interactive" {
  __TERM_INTERACTIVE=0
  [ "$(term_link "https://example.com" "click me")" = "click me (https://example.com)" ]
  # When url == label, just the url
  [ "$(term_link "https://example.com")" = "https://example.com" ]
}

# ---------- panel ----------

@test "term_panel renders UTF-8 box when interactive" {
  __TERM_INTERACTIVE=1
  capture_stderr term_panel "Cleanup will remove" "alpha" "beta"
  [[ "$STDERR" == *"┏"* ]]
  [[ "$STDERR" == *"┃"* ]]
  [[ "$STDERR" == *"┗"* ]]
  [[ "$STDERR" == *"alpha"* ]]
  [[ "$STDERR" == *"beta"* ]]
  [[ "$STDERR" == *"Cleanup will remove"* ]]
}

@test "term_panel falls back to plain text when not interactive" {
  __TERM_INTERACTIVE=0
  capture_stderr term_panel "Title here" "line1" "line2"
  [[ "$STDERR" == *"Title here:"* ]]
  [[ "$STDERR" == *"  line1"* ]]
  [[ "$STDERR" == *"  line2"* ]]
  [[ "$STDERR" != *"┏"* ]]
}

# ---------- progress ----------

@test "term_progress emits sticky-bottom escape sequence interactively" {
  __TERM_INTERACTIVE=1
  capture_stderr term_progress 5 10 "step msg"
  # save (\e7), jump to LINES (\e[<N>H), clear (\e[2K), restore (\e8)
  [[ "$STDERR" == *$'\e7'* ]]
  [[ "$STDERR" == *$'\e8'* ]]
  [[ "$STDERR" == *"50%"* ]]
  [[ "$STDERR" == *"[5/10]"* ]]
  [[ "$STDERR" == *"step msg"* ]]
}

@test "term_progress falls back to plain '[cur/total] msg' when not interactive" {
  __TERM_INTERACTIVE=0
  capture_stderr term_progress 3 7 "thing"
  [ "$STDERR" = "[3/7] thing" ]
}

# ---------- title + spinner ----------

@test "term_set_title emits OSC 0" {
  __TERM_INTERACTIVE=1
  capture_stderr term_set_title "running deploy"
  [[ "$STDERR" == *$'\e]0;running deploy\e\\'* ]]
}

@test "term_spinner_frame cycles through the glyph set" {
  g0=$(term_spinner_frame 0)
  g1=$(term_spinner_frame 1)
  [ "$g0" != "$g1" ]
  # 10 glyphs in the default set → tick=10 wraps back to tick=0.
  g10=$(term_spinner_frame 10)
  [ "$g10" = "$g0" ]
}
