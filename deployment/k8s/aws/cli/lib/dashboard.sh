#!/usr/bin/env bash
# dashboard.sh — generic sticky-panel TUI primitives.
#
# Maintains an in-memory "rows" table (key → status/reason/ts) and renders
# it into a fixed-height pane on stderr, with a header, progress bar,
# counts, and the most-recent rows in each status class.
#
# Multiple panels can run concurrently — pass a uniqueish <ns> arg into
# every helper. State for namespace `cfn` lives in __DASH_<NS>_KEYS /
# __DASH_<NS>_VALS, etc. Bash 3.2-safe: indirection via printf -v +
# eval/declare. No associative arrays.
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

[[ -n "${__MIGRATE_DASHBOARD_LOADED:-}" ]] && return 0
__MIGRATE_DASHBOARD_LOADED=1

DASH_SPINNER='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
DASH_BAR_WIDTH=24
DASH_PANEL_MAX=10

# dash_init <ns> <header>
dash_init() {
  local ns="$1" header="$2"
  eval "__DASH_${ns}_HEADER='$header'"
  eval "__DASH_${ns}_KEYS=()"
  eval "__DASH_${ns}_VALS=()"
  eval "__DASH_${ns}_TICK=0"
  eval "__DASH_${ns}_LAST_HEIGHT=0"
  eval "__DASH_${ns}_CLASSIFIER=_dash_default_classifier"
  printf '\033[?25l' >&2
  on_exit_register _dash_cursor_restore
}

_dash_cursor_restore() { printf '\033[?25h' >&2; }

# dash_status_classifier <ns> <fn-name>
# fn must take a status string and print one of: fail prog done other
dash_status_classifier() {
  eval "__DASH_${1}_CLASSIFIER='$2'"
}

# dash_upsert <ns> <key> <status> <reason> <ts>
dash_upsert() {
  local ns="$1" key="$2" status="$3" reason="$4" ts="$5"
  local epoch packed idx
  epoch=$(date +%s)
  # shellcheck disable=SC2034  # consumed by `eval` below
  packed="$status|$reason|$ts|$epoch"
  idx=$(_dash_idx "$ns" "$key")
  if [[ -n "$idx" ]]; then
    eval "__DASH_${ns}_VALS[idx]=\$packed"
  else
    eval "__DASH_${ns}_KEYS[\${#__DASH_${ns}_KEYS[@]}]=\$key"
    eval "__DASH_${ns}_VALS[\${#__DASH_${ns}_VALS[@]}]=\$packed"
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
  local tick classifier header
  eval "tick=\$((\${__DASH_${ns}_TICK} + 1))"
  eval "__DASH_${ns}_TICK=\$tick"
  local glyph_idx=$(( tick % ${#DASH_SPINNER} ))
  local glyph="${DASH_SPINNER:$glyph_idx:1}"

  if [[ -n "$header_override" ]]; then
    header="$header_override"
  else
    eval "header=\$__DASH_${ns}_HEADER"
  fi
  eval "classifier=\$__DASH_${ns}_CLASSIFIER"

  # Tally counts.
  local total in_progress=0 completed=0 failed=0 i v cls
  eval "total=\${#__DASH_${ns}_KEYS[@]}"
  for ((i=0; i < total; i++)); do
    eval "v=\${__DASH_${ns}_VALS[\$i]}"
    cls=$("$classifier" "${v%%|*}")
    case "$cls" in
      fail) failed=$((failed + 1)) ;;
      prog) in_progress=$((in_progress + 1)) ;;
      done) completed=$((completed + 1)) ;;
    esac
  done

  # Move cursor up by previous render height + erase those lines.
  local last_height
  eval "last_height=\$__DASH_${ns}_LAST_HEIGHT"
  if (( last_height > 0 )); then
    printf '\033[%dA' "$last_height" >&2
    printf '\033[J' >&2
  fi

  local height=0
  local elapsed; elapsed=$(_dash_fmt_elapsed "$started")

  # Header line.
  printf '%s%s%s  %s%s\n' \
    "$__UI_C_BOLD" "$glyph" "$__UI_C_RESET" "$header" \
    "$(printf '%s elapsed %s%s' "$__UI_C_DIM" "$elapsed" "$__UI_C_RESET")" >&2
  height=$(( height + 1 ))

  # Counts + progress bar.
  local seen=$(( total > 0 ? total : 1 ))
  local pct=$(( (completed * 100) / seen ))
  local fill=$(( (completed * DASH_BAR_WIDTH) / seen ))
  (( fill > DASH_BAR_WIDTH )) && fill=$DASH_BAR_WIDTH
  local filled empty
  filled=$(_dash_repeat_char '█' "$fill")
  empty=$(_dash_repeat_char '░' "$(( DASH_BAR_WIDTH - fill ))")
  printf '  %s%s%s%s  %d%%   %s✓ %d%s  %s↻ %d%s  %s✗ %d%s  total %d\n' \
    "$__UI_C_GREEN" "$filled" "$__UI_C_DIM" "$empty" \
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

  printf '%s  %s%s\n' "$__UI_C_DIM" "(Ctrl-C to abort; full log: $LOG_FILE)" "$__UI_C_RESET" >&2
  height=$(( height + 1 ))

  eval "__DASH_${ns}_LAST_HEIGHT=\$height"
}

# dash_finish <ns> <started_epoch> — final render with cursor restored.
dash_finish() {
  local ns="$1" started="$2"
  eval "__DASH_${ns}_TICK=0"
  dash_render "$ns" "$started"
  _dash_cursor_restore
}

# ---------- internals ----------

_dash_default_classifier() { printf 'other\n'; }

_dash_idx() {
  local ns="$1" target="$2" i n
  eval "n=\${#__DASH_${ns}_KEYS[@]}"
  for ((i=0; i < n; i++)); do
    local v
    eval "v=\${__DASH_${ns}_KEYS[\$i]}"
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
  printf '%dm %02ds\n' "$m" "$s"
}

_dash_repeat_char() {
  local ch="$1" n="$2" out
  if (( n <= 0 )); then return; fi
  printf -v out '%*s' "$n" ''
  printf '%s\n' "${out// /$ch}"
}

# _dash_emit_class <ns> <class> <count_in_class> <max> <classifier>
_dash_emit_class() {
  local ns="$1" cls="$2" cls_count="$3" max="$4" classifier="$5"
  (( cls_count == 0 || max == 0 )) && { printf '0\n'; return; }

  local color glyph
  case "$cls" in
    fail) color="$__UI_C_RED";   glyph='✗' ;;
    prog) color="$__UI_C_DIM";   glyph='↻' ;;
    done) color="$__UI_C_GREEN"; glyph='✓' ;;
    *)    color="$__UI_C_RESET"; glyph='·' ;;
  esac

  local i v ts_epoch logical entries=() n
  eval "n=\${#__DASH_${ns}_KEYS[@]}"
  for ((i=0; i < n; i++)); do
    eval "v=\${__DASH_${ns}_VALS[\$i]}"
    [[ "$("$classifier" "${v%%|*}")" == "$cls" ]] || continue
    eval "logical=\${__DASH_${ns}_KEYS[\$i]}"
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
