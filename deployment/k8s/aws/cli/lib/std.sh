#!/usr/bin/env bash
# std.sh — pure-bash 3.2 utilities. No subshells in hot paths.
#
# Bash 3.2 floor (macOS-default-bash). Forbidden constructs (lint-enforced
# in test/lint-bash32.sh): declare -A, ${var,,} / ${var^^}, mapfile,
# readarray, &>>, coproc.
#
# Public API groups:
#   assertion + validation     assert, require_var, require_cmd, optional_cmd
#   strings                    trim, trim_quotes, strip_prefix/suffix,
#                              starts_with, ends_with, contains, split_csv,
#                              join_by, regex_capture, count_lines_var
#   collections                array_contains, read_lines, dedupe
#   path + json                path_join, json_get
#   error + retry              on_error, retry, with_timeout, arith_safe
#   environment                is_interactive, is_tty_alive, is_macos, is_linux
#
# Every helper is documented with the call site(s) it replaces (file:line)
# so reviewers can verify the LOC win is real.

[[ -n "${__MIGRATE_STD_LOADED:-}" ]] && return 0
__MIGRATE_STD_LOADED=1

# ---------- assertion + validation -----------------------------------------

# assert <expr> <msg>
# `eval "[[ $expr ]]"` and die with $msg + file:line on failure.
# Use for invariants that should never trigger in production.
assert() {
  local expr="$1" msg="${2:-assertion failed}"
  if ! eval "[[ $expr ]]"; then
    printf '\033[31merror:\033[0m %s — assertion: [[ %s ]] @ %s:%s\n' \
      "$msg" "$expr" "${BASH_SOURCE[1]}" "${BASH_LINENO[0]}" >&2
    exit 1
  fi
}

# require_var <name>
# Die if the named variable is unset OR empty. Bash 3.2 indirect via `${!}`.
require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    printf '\033[31merror:\033[0m required variable not set: %s @ %s:%s\n' \
      "$name" "${BASH_SOURCE[1]}" "${BASH_LINENO[0]}" >&2
    exit 1
  fi
}

# require_cmd <name> [<install hint>]
# Compatibility re-export of _common.sh's require_cmd. Keep one definition.
# (We do NOT redefine it here; _common.sh already provides it.)

# optional_cmd <name>
# Returns 0 if the command resolves on PATH, 1 otherwise. Never exits.
# Use for graceful-degrade paths (logging fallback, color detection, etc.).
optional_cmd() {
  command -v "$1" >/dev/null 2>&1
}

# ---------- strings --------------------------------------------------------

# trim <string>
# Strip leading + trailing whitespace using only parameter expansion.
# Replaces ad-hoc strips in state.sh:43-44 (which only handles double quotes)
# and trim implementations scattered through discover.sh / cfn.sh.
#
# Echoes the trimmed string on stdout.
trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
}

# trim_quotes <string>
# Strip ONE leading and ONE trailing matched ' or " from the string.
# Asymmetric inputs (only leading or only trailing quote) are left alone.
# Replaces state.sh:43-44 which is double-quote-only.
trim_quotes() {
  local s="$1"
  case "$s" in
    \"*\") s="${s#\"}"; s="${s%\"}" ;;
    \'*\') s="${s#\'}"; s="${s%\'}" ;;
  esac
  printf '%s' "$s"
}

# strip_prefix <prefix> <string>
# Strip <prefix> from the start of <string>. Pattern follows ${var##pat}
# semantics (longest match; supports glob chars — that's the contract,
# pass a literal-quoted prefix if you want exact-match).
# shellcheck disable=SC2295 # glob pattern by design — see comment above
strip_prefix() {
  printf '%s' "${2##$1}"
}

# strip_suffix <suffix> <string>
# Strip <suffix> from the end of <string>.
# shellcheck disable=SC2295 # glob pattern by design — see strip_prefix
strip_suffix() {
  printf '%s' "${2%%$1}"
}

# starts_with <prefix> <string>
# Returns 0 if <string> starts with <prefix>, 1 otherwise. Pure builtin.
starts_with() {
  case "$2" in
    "$1"*) return 0 ;;
    *)     return 1 ;;
  esac
}

# ends_with <suffix> <string>
ends_with() {
  case "$2" in
    *"$1") return 0 ;;
    *)     return 1 ;;
  esac
}

# contains <needle> <string>
# Substring test — returns 0 if <needle> appears anywhere in <string>.
contains() {
  case "$2" in
    *"$1"*) return 0 ;;
    *)      return 1 ;;
  esac
}

# split_csv <string> [<arrayname=__SPLIT>]
# Split <string> on commas into the named array. Default name is __SPLIT
# so callers can use it without declaring an array first.
# Replaces cfn.sh:367-368 + cfn.sh:409-410 IFS save/restore dance.
#
# Caller pattern:
#   split_csv "$list"
#   for x in "${__SPLIT[@]+"${__SPLIT[@]}"}"; do …; done
split_csv() {
  local s="$1" name="${2:-__SPLIT}"
  # Wipe the named array first to avoid append-vs-replace confusion, then
  # split with IFS=, scoped to the read line. We use a temp local array
  # plus eval-copy because `read -r -a "$name"` confuses shellcheck and
  # behaves inconsistently across bash 3.2 patch levels.
  local __sp_tmp
  IFS=',' read -r -a __sp_tmp <<<"$s" || true
  eval "$name=(\"\${__sp_tmp[@]+\"\${__sp_tmp[@]}\"}\")"
}

# join_by <sep> <args...>
# Join args with <sep>. Replaces:
#   cfn.sh:432    paste -sd, -
#   helm.sh:1036  tr '\n' ',' | sed 's/,$//'
#   helm.sh:1039  same
#   helm.sh:1043  same
# Echoes the joined string with no trailing newline.
#
# Bash's `${arr[*]}` only takes the first byte of IFS for the join, so the
# IFS trick fails for multi-char separators. Loop explicitly to support
# arbitrary-length seps (e.g. "$' | '$").
join_by() {
  local sep="$1"; shift
  if (( $# == 0 )); then return 0; fi
  local out="$1"; shift
  local arg
  for arg in "$@"; do
    out="${out}${sep}${arg}"
  done
  printf '%s' "$out"
}

# regex_capture <string> <pattern> [<group_index=1>]
# Run [[ <string> =~ <pattern> ]] and echo BASH_REMATCH[<group>].
# Returns 0 on match, 1 on no-match (empty stdout in either case).
# Replaces:
#   discover.sh:101-109   _jq_or_grep grep+sed JSON value extraction
#   helm.sh:549           sed -nE 's/.*name: ([…]), kind: Job.*/\1/p' | head -1
#   helm.sh:631-632       tr -d '\n' | sed -E for "info.status" extraction
regex_capture() {
  local s="$1" pat="$2" group="${3:-1}"
  if [[ "$s" =~ $pat ]]; then
    printf '%s' "${BASH_REMATCH[$group]}"
    return 0
  fi
  return 1
}

# count_lines_var <varname>
# Echo the number of newlines in the named variable. Pure builtin —
# replaces `wc -l | tr -d ' '` chains.
# A trailing-newline-less string with content counts as 1.
# Replaces crane.sh:67 and similar.
count_lines_var() {
  local val="${!1:-}"
  [[ -z "$val" ]] && { printf '0'; return; }
  local nls="${val//[!$'\n']/}"
  local n="${#nls}"
  # If the last char isn't newline, the trailing fragment is still a line.
  if [[ "${val: -1}" != $'\n' ]]; then
    n=$(( n + 1 ))
  fi
  printf '%d' "$n"
}

# ---------- collections ----------------------------------------------------

# array_contains <needle> <arrayname>
# Returns 0 if <needle> is in the named array, 1 otherwise.
# Bash 3.2-safe — uses indirect array expansion.
# Replaces cfn.sh:150-175 seen_log on-disk grep -qxF tracker.
array_contains() {
  local needle="$1" name="$2"
  # Indirect expansion of array contents in 3.2: build the iteration var
  # via eval into a local positional copy, then test.
  local __ac_n
  eval "__ac_n=\${#${name}[@]}"
  if (( __ac_n == 0 )); then return 1; fi
  local __ac_i __ac_v
  for ((__ac_i = 0; __ac_i < __ac_n; __ac_i++)); do
    eval "__ac_v=\${${name}[\$__ac_i]}"
    if [[ "$__ac_v" == "$needle" ]]; then
      return 0
    fi
  done
  return 1
}

# read_lines <arrayname> <file>
# Read every line of <file> into the named array. Bash 3.2-safe `mapfile`
# substitute with the trailing-newline-less guard `|| [[ -n $line ]]`.
# Replaces 8+ copies: state.sh:39, cfn.sh:105-107, cfn.sh:171,
# helm.sh:509/518/595/746/767/931.
read_lines() {
  local name="$1" file="$2" line
  eval "$name=()"
  if [[ ! -r "$file" ]]; then return 0; fi
  while IFS= read -r line || [[ -n "$line" ]]; do
    eval "$name+=(\"\$line\")"
  done <"$file"
}

# dedupe
# Filter stdin keeping only the first occurrence of each line.
# Pure awk — bash 3.2-safe form. (A `declare -A` based dedupe would be
# more efficient on bash 4+, but breaks the macOS-default-bash floor.)
# Replaces ad-hoc patterns; codifies what cfn.sh already does correctly.
dedupe() {
  awk 'NF && !seen[$0]++'
}

# ---------- path + json ----------------------------------------------------

# path_join <a> <b> [<c>...]
# Join path components with '/' and collapse runs of slashes. No external
# call; safer than naive `"$a/$b"` when args may have trailing slashes.
path_join() {
  local out="$1" arg
  shift
  for arg in "$@"; do
    if [[ -z "$out" ]]; then
      out="$arg"
    else
      # Strip trailing slashes from $out and leading from $arg before joining.
      out="${out%/}/${arg#/}"
    fi
  done
  # Collapse double slashes that survived (e.g. when $arg started with //).
  while [[ "$out" == *//* ]]; do
    out="${out//\/\//\/}"
  done
  printf '%s' "$out"
}

# json_get <jq-expr> [<file>]
# Extract a JSON value via jq if available; fall back to a single-group
# regex capture when jq is missing AND the expression is a simple
# top-level field like '.foo' or '.foo.bar'. Reads from <file> or stdin.
#
# Replaces discover.sh:101-109 _jq_or_grep helper, which today only
# handles a single hard-coded grep_key argument. This generalizes to any
# top-level field while keeping the same fallback behavior.
json_get() {
  local expr="$1" file="${2:-}"
  if optional_cmd jq; then
    if [[ -n "$file" ]]; then
      jq -r "$expr" <"$file"
    else
      jq -r "$expr"
    fi
    return $?
  fi
  # Fallback: walk a simple .foo.bar expression by regex_capture per part.
  # The pattern matches "key": "value" at any nesting depth — coarse, but
  # matches sts:GetCallerIdentity / helm status / similar tame inputs.
  local data
  if [[ -n "$file" ]]; then
    data=$(<"$file")
  else
    data=$(cat)
  fi
  # Strip leading dot, split on dots — last component is the key we extract.
  local parts="${expr#.}"
  local key="${parts##*.}"
  if [[ -z "$key" || "$key" == "$expr" && "${expr:0:1}" != "." ]]; then
    return 1
  fi
  regex_capture "$data" "\"$key\"[[:space:]]*:[[:space:]]*\"([^\"]*)\""
}

# ---------- flag parsing ---------------------------------------------------

# parse_flag <flag> <out_name> -- <argv...>
# Extract `--flag VALUE` or `--flag=VALUE` from argv. Stores VALUE into the
# named variable. Echoes the rest of the args (one per line) to stdout for
# the caller to slurp via:
#
#   parse_flag --foo FOO_VAL -- "$@"
#   read_lines REST "$tmpfile"  # — or use the simpler variant below
#
# Caller pattern (idiomatic):
#
#   FOO_VAL=""; REST=()
#   parse_flag_into REST FOO_VAL --foo "$@"
#
# parse_flag_into <rest_arr> <out_var> <flag> <argv...>
# Removes the first `<flag> VALUE` or `<flag>=VALUE` it finds, writes VALUE
# into <out_var>, and stores everything else into <rest_arr>.
parse_flag_into() {
  local rest_arr="$1" out_var="$2" flag="$3"
  shift 3
  eval "$rest_arr=()"
  eval "$out_var=\"\""
  local consumed=0 a
  while (( $# > 0 )); do
    a="$1"
    if [[ $consumed -eq 0 ]]; then
      if [[ "$a" == "$flag" ]]; then
        if (( $# >= 2 )); then
          eval "$out_var=\"\$2\""
          shift 2
          consumed=1
          continue
        else
          shift
          consumed=1
          continue
        fi
      elif [[ "$a" == "$flag="* ]]; then
        eval "$out_var=\"\${a#\$flag=}\""
        shift
        consumed=1
        continue
      fi
    fi
    eval "$rest_arr+=(\"\$a\")"
    shift
  done
}

# ---------- error + retry --------------------------------------------------

# on_error <fn>
# Register <fn> to run on EXIT only when $? != 0. Wraps on_exit_register
# from _common.sh with an rc-gate.
on_error() {
  local fn="$1"
  # Closure over $fn via a unique outer name.
  local wrapper="__on_error_$RANDOM$RANDOM"
  eval "${wrapper}() { [[ \"\${1:-0}\" -ne 0 ]] && \"$fn\" \"\$@\"; return 0; }"
  on_exit_register "$wrapper"
}

# retry <attempts> <delay_secs> -- <cmd...>
# Run <cmd> up to <attempts> times with a fixed <delay> between attempts.
# Returns the exit code of the last attempt.
retry() {
  local attempts="$1" delay="$2"
  shift 2
  if [[ "${1:-}" == "--" ]]; then shift; fi
  local i=0 rc=1
  while (( i < attempts )); do
    "$@" && return 0
    rc=$?
    i=$(( i + 1 ))
    if (( i < attempts )); then
      sleep "$delay"
    fi
  done
  return "$rc"
}

# with_timeout <secs> -- <cmd...>
# Run <cmd> with a hard timeout. Uses `timeout(1)` if present, else a
# background-and-kill fallback. Returns 124 on timeout (matching coreutils).
with_timeout() {
  local secs="$1"
  shift
  if [[ "${1:-}" == "--" ]]; then shift; fi
  if optional_cmd timeout; then
    timeout "$secs" "$@"
    return $?
  fi
  # Fallback: run cmd in background, kill after $secs.
  "$@" &
  local cmd_pid=$!
  ( sleep "$secs"; kill -TERM "$cmd_pid" 2>/dev/null ) &
  local killer_pid=$!
  local rc=0
  wait "$cmd_pid" 2>/dev/null
  rc=$?
  kill "$killer_pid" 2>/dev/null || true
  wait "$killer_pid" 2>/dev/null || true
  if (( rc == 143 )); then return 124; fi
  return "$rc"
}

# arith_safe <expr>
# Evaluate <expr> as bash arithmetic without aborting under `set -o errexit`.
# Plain `(( $expr ))` returns rc=1 when the result is 0, which errexit
# treats as failure and aborts the whole script.
# This wrapper always returns 0; callers consuming the value should use
# `: $(( … ))` or assignment forms directly.
arith_safe() {
  : $(( $1 ))
  return 0
}

# ---------- environment ----------------------------------------------------

# is_interactive — stderr is a real TTY.
is_interactive() { [[ -t 2 ]]; }

# is_tty_alive — stdout is still attached to a TTY.
# Use to short-circuit dashboard rendering when ssh disconnects mid-deploy.
is_tty_alive() { [[ -t 1 ]]; }

# is_macos / is_linux — zero-fork OS branching via $OSTYPE (compile-time).
is_macos() { [[ "$OSTYPE" == darwin* ]]; }
is_linux() { [[ "$OSTYPE" == linux* ]]; }
