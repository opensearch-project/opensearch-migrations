#!/usr/bin/env bash
# dashboard.sh — generic sticky-panel TUI primitives.
#
# Maintains an in-memory "rows" table (key → status/reason/ts) and renders
# it into a fixed-height pane on stderr, with a header, progress bar,
# counts, and the most-recent rows in each status class.
#
# Multiple panels can run concurrently — pass a uniqueish <ns> arg into
# every helper. State for namespace `cfn` lives in __DASH_<NS>_KEYS /
# __DASH_<NS>_VALS, etc. Bash 3.2-safe.
#
# Indirection rules:
#   * USER DATA (header text, status, reason, key) is NEVER passed
#     through `eval`. It goes through `printf -v <var> '%s' "$value"`
#     for scalars and `__ARR+=("$value")` for array elements (the array
#     itself is named via eval, but the VALUE is not eval'd as a string).
#     This eliminates the cursor-exploded-mid-render bug class where a
#     header like "can't reach EKS" used to break the eval-with-single-
#     quotes wrapping the original code did.
#   * BASH VARIABLE NAMES (e.g. __DASH_${ns}_KEYS) ARE eval'd because
#     bash 3.2 has no associative arrays and indirection across array
#     names is impossible without eval. The <ns> arg is constructor-
#     supplied (test names, "cfn", "helm"), never user input.
#
# Caller workflow:
#
#   dash_init  cfn "header text"
#   dash_status_classifier cfn _cfn_status_class    # function name
#   dash_upsert cfn <key> <status> <reason> <ts>
#   dash_render cfn $started_epoch
#   dash_finish cfn $started_epoch
#
# `dash_status_classifier` plugs in domain-specific status → fail/prog/
# done/other mapping. Default classifier returns "other" for everything.
#
# Cursor management: dashboard.sh does NOT directly emit cursor-hide /
# cursor-show escapes. term.sh owns that — its dedicated EXIT trap runs
# FIRST and unconditionally restores the cursor on any exit path
# (Ctrl-C, set -e, exec, normal). dash_init asks term_hide_cursor; the
# trap handles the rest.

[[ -n "${__MIGRATE_DASHBOARD_LOADED:-}" ]] && return 0
__MIGRATE_DASHBOARD_LOADED=1

# Defensive source: uses term_columns / term_hide_cursor / term_show_cursor /
# term_spinner_frame / is_tty_alive.
# shellcheck source=lib/term.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/term.sh"
# shellcheck source=lib/std.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/std.sh"

DASH_PANEL_MAX=10

# Bar width is derived from term_columns at render time, not hard-coded.
# Old DASH_BAR_WIDTH=24 didn't track terminal resize and overflowed on
# narrow terminals.
DASH_BAR_MIN=12
DASH_BAR_MAX=32

# dash_init <ns> <header>
dash_init() {
  local ns="$1" header="$2"
  printf -v "__DASH_${ns}_HEADER"      '%s' "$header"
  printf -v "__DASH_${ns}_TICK"        '%s' '0'
  printf -v "__DASH_${ns}_LAST_HEIGHT" '%s' '0'
  printf -v "__DASH_${ns}_CLASSIFIER"  '%s' '_dash_default_classifier'
  # Initialize the parallel arrays via aliased name. Bash 3.2 has no
  # associative arrays; we use indirect array assignment via the same
  # printf -v + readarray-style trick. Plain `eval "X=()"` is unavoidable
  # for array DECLARATION in 3.2 (printf -v can't create arrays), but
  # this is the only eval in dashboard.sh and the ns is constructor-
  # supplied (test-or-cfn-or-helm), never user input.
  eval "__DASH_${ns}_KEYS=()"
  eval "__DASH_${ns}_VALS=()"
  term_hide_cursor
}

# dash_status_classifier <ns> <fn-name>
# fn must take a status string and print one of: fail prog done other
dash_status_classifier() {
  printf -v "__DASH_${1}_CLASSIFIER" '%s' "$2"
}

# dash_upsert <ns> <key> <status> <reason> <ts>
dash_upsert() {
  local ns="$1" key="$2" status="$3" reason="$4" ts="$5"
  local epoch packed idx
  epoch=$(date +%s)
  # shellcheck disable=SC2034 # consumed by `eval` below — shellcheck can't trace
  packed="$status|$reason|$ts|$epoch"
  idx=$(_dash_idx "$ns" "$key")
  if [[ -n "$idx" ]]; then
    eval "__DASH_${ns}_VALS[idx]=\$packed"
  else
    eval "__DASH_${ns}_KEYS+=(\"\$key\")"
    eval "__DASH_${ns}_VALS+=(\"\$packed\")"
  fi
}

# dash_clear <ns> — reset the rows (e.g. between phases of helm install).
dash_clear() {
  local ns="$1"
  eval "__DASH_${ns}_KEYS=()"
  eval "__DASH_${ns}_VALS=()"
}

# dash_render <ns> <started_epoch> [<header_override>]
dash_render() {
  local ns="$1" started="$2" header_override="${3:-}"

  # SSH-disconnect / detached-tty guard. If we lost the tty mid-deploy,
  # fall through silently rather than emitting ANSI to a closed fd until
  # the kernel reaps us.
  is_tty_alive || return 0

  # Pull state via indirect expansion. ${!name} reads the variable named
  # by $name — bash 3.2-safe, no eval, no quote-injection hazard.
  local tick_var="__DASH_${ns}_TICK"
  local last_h_var="__DASH_${ns}_LAST_HEIGHT"
  local classifier_var="__DASH_${ns}_CLASSIFIER"
  local header_var="__DASH_${ns}_HEADER"

  local tick=$(( ${!tick_var} + 1 ))
  printf -v "$tick_var" '%s' "$tick"

  local glyph; glyph=$(term_spinner_frame "$tick")

  local header
  if [[ -n "$header_override" ]]; then
    header="$header_override"
  else
    header="${!header_var}"
  fi
  local classifier="${!classifier_var}"

  # Tally counts. Avoid the per-row classifier subshell (the hottest fork
  # path in the previous implementation): the classifier writes its result
  # into a shared __DASH_CLS variable via printf -v. Classifier functions
  # call _dash_set_cls "<class>" instead of `printf 'class\n'`.
  local total in_progress=0 completed=0 failed=0 i v
  local keys_var="__DASH_${ns}_KEYS"
  local vals_var="__DASH_${ns}_VALS"
  eval "total=\${#${keys_var}[@]}"
  for ((i = 0; i < total; i++)); do
    eval "v=\${${vals_var}[\$i]}"
    _dash_classify "$classifier" "${v%%|*}"
    case "$__DASH_CLS" in
      fail) failed=$((failed + 1)) ;;
      prog) in_progress=$((in_progress + 1)) ;;
      done) completed=$((completed + 1)) ;;
    esac
  done

  # Move cursor up by previous render height + erase those lines.
  local last_height="${!last_h_var}"
  if (( last_height > 0 )); then
    printf '\e[%dA' "$last_height" >&2
    printf '\e[J' >&2
  fi

  local height=0
  local elapsed; elapsed=$(_dash_fmt_elapsed "$started")

  # Bar width tracks terminal width; clamp into a sane band.
  local bar_w; bar_w=$(( $(term_columns) / 4 ))
  (( bar_w < DASH_BAR_MIN )) && bar_w=$DASH_BAR_MIN
  (( bar_w > DASH_BAR_MAX )) && bar_w=$DASH_BAR_MAX

  # Header line. Inline the elapsed format (no nested $(printf …)) — the
  # outer printf takes both args directly. One fork removed per render.
  printf '%s%s%s  %s  %selapsed %s%s\n' \
    "$__UI_C_BOLD" "$glyph" "$__UI_C_RESET" "$header" \
    "$__UI_C_DIM" "$elapsed" "$__UI_C_RESET" >&2
  height=$(( height + 1 ))

  # Counts + progress bar.
  local seen=$(( total > 0 ? total : 1 ))
  local pct=$(( (completed * 100) / seen ))
  local fill=$(( (completed * bar_w) / seen ))
  if (( fill > bar_w )); then fill=$bar_w; fi
  local empty=$(( bar_w - fill ))
  local filled_str empty_str
  printf -v filled_str '%*s' "$fill"  ''
  printf -v empty_str  '%*s' "$empty" ''
  filled_str=${filled_str// /█}
  empty_str=${empty_str// /░}
  printf '  %s%s%s%s%s  %d%%   %s✓ %d%s  %s↻ %d%s  %s✗ %d%s  total %d\n' \
    "$__UI_C_GREEN" "$filled_str" "$__UI_C_DIM" "$empty_str" "$__UI_C_RESET" \
    "$pct" \
    "$__UI_C_GREEN" "$completed" "$__UI_C_RESET" \
    "$__UI_C_DIM" "$in_progress" "$__UI_C_RESET" \
    "$__UI_C_RED" "$failed" "$__UI_C_RESET" \
    "$total" >&2
  printf '%s\n' "$__UI_C_RESET" >&2
  height=$(( height + 2 ))

  # Resource rows: failed first, in_progress next, then most-recent done.
  local rows_left=$DASH_PANEL_MAX printed
  printed=$(_dash_emit_class "$ns" fail "$failed" "$rows_left" "$classifier")
  height=$(( height + printed ))
  rows_left=$(( rows_left - printed ))
  if (( rows_left > 0 )); then
    printed=$(_dash_emit_class "$ns" prog "$in_progress" "$rows_left" "$classifier")
    height=$(( height + printed ))
    rows_left=$(( rows_left - printed ))
  fi
  if (( rows_left > 0 )); then
    printed=$(_dash_emit_class "$ns" "done" "$completed" "$rows_left" "$classifier")
    height=$(( height + printed ))
  fi

  printf '%s  (Ctrl-C to abort; full log: %s)%s\n' \
    "$__UI_C_DIM" "$LOG_FILE" "$__UI_C_RESET" >&2
  height=$(( height + 1 ))

  printf -v "$last_h_var" '%s' "$height"
}

# dash_finish <ns> <started_epoch> — final render with cursor restored.
dash_finish() {
  local ns="$1" started="$2"
  printf -v "__DASH_${ns}_TICK" '%s' '0'
  dash_render "$ns" "$started"
  term_show_cursor
}

# ---------- internals ----------

# Classifier ABI (v2):
#   * Old API: classifier prints "fail" / "prog" / "done" / "other" on stdout
#     — dashboard caller did `cls=$(classifier "$status")` once per row.
#     That was the hottest fork path in the CLI: O(rows) subshells per
#     1Hz render.
#   * New API: classifier calls `_dash_set_cls <class>` which writes into
#     the shared __DASH_CLS variable via printf -v. Zero forks per row.
#
# Classifiers in the codebase (cfn.sh:_cfn_status_class, helm.sh, …) are
# updated to the new ABI. Callers of dashboard.sh from outside the CLI
# (none today) would need updating.

__DASH_CLS=other

_dash_set_cls() {
  __DASH_CLS="$1"
}

_dash_classify() {
  local classifier="$1" status="$2"
  __DASH_CLS=other
  "$classifier" "$status"
}

_dash_default_classifier() {
  _dash_set_cls other
}

_dash_idx() {
  local ns="$1" target="$2"
  local keys_var="__DASH_${ns}_KEYS"
  local n
  eval "n=\${#${keys_var}[@]}"
  local i v
  for ((i = 0; i < n; i++)); do
    eval "v=\${${keys_var}[\$i]}"
    if [[ "$v" == "$target" ]]; then
      printf '%d\n' "$i"
      return
    fi
  done
}

_dash_fmt_elapsed() {
  local started="$1" now d m s
  now=$(date +%s)
  d=$(( now - started ))
  m=$(( d / 60 ))
  s=$(( d % 60 ))
  printf '%dm %02ds' "$m" "$s"
}

# _dash_repeat_char <ch> <n>
# Echoes <n> copies of <ch>. Pure builtin, no fork.
# Kept as a named helper for backward-compat with existing bats tests AND
# because it's a useful primitive even though dash_render now inlines the
# pattern directly (one fewer subshell-capture per render).
_dash_repeat_char() {
  local ch="$1" n="$2" out
  if (( n <= 0 )); then return 0; fi
  printf -v out '%*s' "$n" ''
  printf '%s' "${out// /$ch}"
}

# _dash_emit_class <ns> <class> <count_in_class> <max> <classifier>
_dash_emit_class() {
  local ns="$1" cls="$2" cls_count="$3" max="$4" classifier="$5"
  if (( cls_count == 0 )) || (( max == 0 )); then
    printf '0\n'
    return
  fi

  local color glyph
  case "$cls" in
    fail) color="$__UI_C_RED";   glyph='✗' ;;
    prog) color="$__UI_C_DIM";   glyph='↻' ;;
    done) color="$__UI_C_GREEN"; glyph='✓' ;;
    *)    color="$__UI_C_RESET"; glyph='·' ;;
  esac

  local keys_var="__DASH_${ns}_KEYS"
  local vals_var="__DASH_${ns}_VALS"
  local n
  eval "n=\${#${keys_var}[@]}"
  local i v ts_epoch logical entries=()
  for ((i = 0; i < n; i++)); do
    eval "v=\${${vals_var}[\$i]}"
    _dash_classify "$classifier" "${v%%|*}"
    [[ "$__DASH_CLS" == "$cls" ]] || continue
    eval "logical=\${${keys_var}[\$i]}"
    ts_epoch="${v##*|}"
    entries[${#entries[@]}]="$ts_epoch|$logical|$v"
  done

  local printed=0 line status reason
  while IFS= read -r line; do
    (( printed >= max )) && break
    [[ -z "$line" ]] && continue
    local rest=${line#*|}
    logical=${rest%%|*}
    rest=${rest#*|}
    status=${rest%%|*}
    rest=${rest#*|}
    reason=${rest%%|*}
    local short="$logical"
    if (( ${#short} > 38 )); then
      short="${short:0:35}…"
    fi
    if [[ -n "$reason" && "$reason" != "None" ]]; then
      printf '  %s%s %-38s %-22s  %s%s\n' \
        "$color" "$glyph" "$short" "$status" "$reason" "$__UI_C_RESET" >&2
    else
      printf '  %s%s %-38s %-22s%s\n' \
        "$color" "$glyph" "$short" "$status" "$__UI_C_RESET" >&2
    fi
    printed=$((printed + 1))
  done < <(printf '%s\n' "${entries[@]+"${entries[@]}"}" | sort -t'|' -k1,1 -nr)

  printf '%d\n' "$printed"
}
