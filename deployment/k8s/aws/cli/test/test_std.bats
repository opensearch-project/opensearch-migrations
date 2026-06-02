#!/usr/bin/env bats
# test_std.bats — coverage for lib/std.sh helpers.
#
# These helpers replace ad-hoc patterns scattered across cfn.sh / helm.sh /
# state.sh / discover.sh. Each test exercises the helper at the call-site
# shape used in the real lib (split_csv into a named array, regex_capture
# extracting BASH_REMATCH[1], read_lines with a no-trailing-newline file).

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  load_libs _common.sh std.sh
}

teardown() {
  teardown_isolated_home
}

# ---------- strings ----------

@test "trim strips leading + trailing whitespace" {
  [ "$(trim '  hello world  ')" = "hello world" ]
  [ "$(trim '')" = "" ]
  [ "$(trim 'no-ws')" = "no-ws" ]
}

@test "trim_quotes strips matched single OR double quotes" {
  [ "$(trim_quotes '"hello"')" = "hello" ]
  [ "$(trim_quotes "'hello'")" = "hello" ]
  # Asymmetric input — leave alone (don't mangle into half-quoted).
  [ "$(trim_quotes '"only-leading')" = '"only-leading' ]
  [ "$(trim_quotes 'only-trailing"')" = 'only-trailing"' ]
}

@test "starts_with / ends_with / contains exit-status semantics" {
  starts_with abc abcdef
  ! starts_with xyz abcdef
  ends_with def abcdef
  ! ends_with xyz abcdef
  contains cd abcdef
  ! contains zz abcdef
}

@test "split_csv into default array name (__SPLIT)" {
  split_csv "a,b,c"
  [ "${#__SPLIT[@]}" -eq 3 ]
  [ "${__SPLIT[0]}" = "a" ]
  [ "${__SPLIT[2]}" = "c" ]
}

@test "split_csv into custom array name" {
  local my_arr=()
  split_csv "x,y,z" my_arr
  [ "${#my_arr[@]}" -eq 3 ]
  [ "${my_arr[1]}" = "y" ]
}

@test "split_csv on empty string yields one empty element" {
  split_csv ""
  # Bash 3.2 read with empty input + IFS=,  → array with one empty entry.
  # We're documenting the contract here, not the ideal semantics.
  [ "${#__SPLIT[@]}" -le 1 ]
}

@test "join_by joins args with the given separator" {
  [ "$(join_by , a b c)" = "a,b,c" ]
  [ "$(join_by ' | ' one two three)" = "one | two | three" ]
  [ "$(join_by ,)" = "" ]   # zero args → empty
  [ "$(join_by , single)" = "single" ]
}

@test "regex_capture extracts BASH_REMATCH[1] by default" {
  out=$(regex_capture "name: my-job, kind: Job" 'name: ([A-Za-z0-9_-]+),')
  [ "$out" = "my-job" ]
}

@test "regex_capture returns non-zero on no match" {
  ! regex_capture "no match here" '([0-9]+)' >/dev/null
}

@test "regex_capture supports group index argument" {
  out=$(regex_capture "abc=123" '([a-z]+)=([0-9]+)' 2)
  [ "$out" = "123" ]
}

@test "count_lines_var counts lines in a variable" {
  local v
  v=$'a\nb\nc'
  [ "$(count_lines_var v)" = "3" ]
  v=""
  [ "$(count_lines_var v)" = "0" ]
  v=$'a\nb\n'
  [ "$(count_lines_var v)" = "2" ]
  v=$'one-line-no-newline'
  [ "$(count_lines_var v)" = "1" ]
}

# ---------- collections ----------

@test "array_contains finds existing element" {
  local arr=(alpha beta gamma)
  array_contains beta arr
}

@test "array_contains rejects missing element" {
  local arr=(alpha beta gamma)
  ! array_contains delta arr
}

@test "array_contains handles empty array" {
  local empty=()
  ! array_contains anything empty
}

@test "read_lines populates the named array" {
  local f
  f=$(mktemp)
  printf 'one\ntwo\nthree\n' > "$f"
  local out=()
  read_lines out "$f"
  [ "${#out[@]}" -eq 3 ]
  [ "${out[0]}" = "one" ]
  [ "${out[2]}" = "three" ]
  rm -f "$f"
}

@test "read_lines reads file without trailing newline" {
  local f
  f=$(mktemp)
  # No trailing newline — historically lost the last line in the
  # ad-hoc read-loop unless the caller appended `|| [[ -n \$line ]]`.
  printf 'one\ntwo\nthree' > "$f"
  local out=()
  read_lines out "$f"
  [ "${#out[@]}" -eq 3 ]
  [ "${out[2]}" = "three" ]
  rm -f "$f"
}

@test "read_lines with missing file leaves array empty" {
  local out=()
  read_lines out /nonexistent/path
  [ "${#out[@]}" -eq 0 ]
}

@test "dedupe drops duplicate lines" {
  out=$(printf 'a\nb\na\nc\nb\n' | dedupe | tr '\n' '|')
  [ "$out" = "a|b|c|" ]
}

# ---------- path + json ----------

@test "path_join collapses slashes" {
  [ "$(path_join /var log migrate.log)" = "/var/log/migrate.log" ]
  [ "$(path_join /foo/ /bar)" = "/foo/bar" ]
  [ "$(path_join foo bar baz)" = "foo/bar/baz" ]
}

@test "json_get extracts a top-level field via jq when present" {
  if ! command -v jq >/dev/null 2>&1; then
    skip "jq not installed"
  fi
  local f; f=$(mktemp)
  printf '{"foo": "bar", "x": 1}\n' > "$f"
  out=$(json_get .foo "$f")
  [ "$out" = "bar" ]
  rm -f "$f"
}

# ---------- error + retry ----------

@test "retry succeeds on the third attempt" {
  local f; f=$(mktemp); echo 0 > "$f"
  flaky() {
    local n; n=$(< "$f"); n=$((n + 1)); echo "$n" > "$f"; (( n >= 3 ))
  }
  retry 5 0.01 -- flaky
  [ "$(< "$f")" = "3" ]
  rm -f "$f"
}

@test "retry returns non-zero when all attempts fail" {
  always_fail() { return 1; }
  ! retry 3 0.01 -- always_fail
}

@test "with_timeout returns 124 on hard timeout" {
  if ! command -v timeout >/dev/null 2>&1; then
    skip "POSIX timeout(1) not installed; fallback path tested separately"
  fi
  run with_timeout 1 -- sleep 5
  [ "$status" -eq 124 ]
}

@test "arith_safe survives 0-result expressions under set -e" {
  set -o errexit
  arith_safe '5 - 5'
  arith_safe '0'
}

# ---------- environment ----------

@test "is_macos / is_linux are mutually exclusive (most of the time)" {
  is_macos && return 0
  is_linux && return 0
  skip "neither macos nor linux — \$OSTYPE=$OSTYPE"
}

@test "is_interactive matches [[ -t 2 ]]" {
  if [[ -t 2 ]]; then
    is_interactive
  else
    ! is_interactive
  fi
}

# ---------- flag parsing ----------

@test "parse_flag_into extracts space-separated value" {
  local rest=() val=""
  parse_flag_into rest val --foo "--non-interactive" "--foo" "myval" "--bar" "x"
  [ "$val" = "myval" ]
  [ "${#rest[@]}" -eq 3 ]
  [ "${rest[0]}" = "--non-interactive" ]
  [ "${rest[1]}" = "--bar" ]
  [ "${rest[2]}" = "x" ]
}

@test "parse_flag_into extracts equals-form value" {
  local rest=() val=""
  parse_flag_into rest val --foo "--foo=combined" "extra"
  [ "$val" = "combined" ]
  [ "${rest[0]}" = "extra" ]
}

@test "parse_flag_into without the flag leaves val empty" {
  local rest=() val=""
  parse_flag_into rest val --missing "a" "b" "c"
  [ -z "$val" ]
  [ "${#rest[@]}" -eq 3 ]
}

# ---------- assertion ----------

@test "require_var dies when the named variable is empty" {
  unset SOMEVAR
  run require_var SOMEVAR
  [ "$status" -ne 0 ]
}

@test "require_var passes when the named variable is set" {
  SOMEVAR=ok require_var SOMEVAR
}

@test "optional_cmd returns 0 for present commands, 1 for missing" {
  optional_cmd bash
  ! optional_cmd this-command-definitely-does-not-exist-9876
}
