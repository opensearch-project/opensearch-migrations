#!/usr/bin/env bash
# state.sh — per-stage state I/O.
#
# Bash 3.2 compatible (macOS-default-bash). We avoid associative arrays
# (`declare -A`, bash 4+) and use a parallel-array key/value pair instead.
# Public API is unchanged: state_load / state_set / state_get / state_has /
# state_save / state_archive / state_resumable_step.
#
# Two artifacts written together for two readers:
#   state.env   ← KEY=VALUE lines, sourceable from bash, also human-greppable
#   state.json  ← canonical for jq, used by the agent and by tests
#
# A simple flock guards against concurrent migration-assistant runs on the same stage.

[[ -n "${__MIGRATE_STATE_LOADED:-}" ]] && return 0
__MIGRATE_STATE_LOADED=1

STATE_KEYS=()
STATE_VALS=()

# _state_index <key> → echoes index in STATE_KEYS or empty
_state_index() {
  local target="$1" i
  for ((i=0; i < ${#STATE_KEYS[@]}; i++)); do
    if [[ "${STATE_KEYS[$i]}" == "$target" ]]; then
      printf '%d\n' "$i"
      return
    fi
  done
}

state_load() {
  stage_dir_init
  STATE_KEYS=()
  STATE_VALS=()
  local env_file="$STAGE_DIR/state.env"
  if [[ -f "$env_file" ]]; then
    local k v line
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -z "$line" || "$line" == \#* ]] && continue
      k=${line%%=*}
      v=${line#*=}
      v=${v%\"}; v=${v#\"}
      v=${v//\\\"/\"}
      STATE_KEYS+=("$k")
      STATE_VALS+=("$v")
    done <"$env_file"
  fi
}

state_set() {
  local k="$1" v="$2"
  local idx
  idx=$(_state_index "$k")
  if [[ -n "$idx" ]]; then
    STATE_VALS[idx]="$v"
  else
    STATE_KEYS+=("$k")
    STATE_VALS+=("$v")
  fi
}

state_get() {
  local k="$1" def="${2:-}"
  local idx
  idx=$(_state_index "$k")
  if [[ -n "$idx" ]]; then
    printf '%s\n' "${STATE_VALS[$idx]}"
  else
    printf '%s\n' "$def"
  fi
}

state_has() {
  local idx
  idx=$(_state_index "$1")
  [[ -n "$idx" ]]
}

state_save() {
  stage_dir_init
  local lock="$STAGE_DIR/.state.lock"
  exec 9>"$lock"
  if command -v flock >/dev/null 2>&1; then
    flock -x 9
  fi

  local env_file="$STAGE_DIR/state.env"
  local json_file="$STAGE_DIR/state.json"
  local env_tmp="$env_file.tmp.$$"
  local json_tmp="$json_file.tmp.$$"

  {
    printf '# %s — written by migration-assistant; do not edit while it is running.\n' "$(date)"
    local i k v
    for ((i=0; i < ${#STATE_KEYS[@]}; i++)); do
      k="${STATE_KEYS[$i]}"
      v="${STATE_VALS[$i]}"
      v=${v//\"/\\\"}
      printf '%s="%s"\n' "$k" "$v"
    done
  } >"$env_tmp"

  if command -v jq >/dev/null 2>&1; then
    local i k v args=()
    for ((i=0; i < ${#STATE_KEYS[@]}; i++)); do
      k="${STATE_KEYS[$i]}"
      v="${STATE_VALS[$i]}"
      args+=(--arg "$k" "$v")
    done
    if [[ ${#args[@]} -gt 0 ]]; then
      jq -n "${args[@]}" '$ARGS.named' >"$json_tmp"
    else
      printf '{}\n' >"$json_tmp"
    fi
  else
    {
      printf '{\n'
      local first=1 i k v
      for ((i=0; i < ${#STATE_KEYS[@]}; i++)); do
        k="${STATE_KEYS[$i]}"
        v="${STATE_VALS[$i]}"
        v=${v//\\/\\\\}
        v=${v//\"/\\\"}
        if [[ $first -eq 1 ]]; then first=0; else printf ',\n'; fi
        printf '  "%s": "%s"' "$k" "$v"
      done
      printf '\n}\n'
    } >"$json_tmp"
  fi

  mv -f "$env_tmp" "$env_file"
  mv -f "$json_tmp" "$json_file"
  exec 9>&-
}

state_archive() {
  stage_dir_init
  local ts
  ts=$(date '+%Y%m%dT%H%M%S')
  local dest="$STAGE_DIR/archive/$ts"
  mkdir -p "$dest"
  local f
  for f in state.env state.json; do
    [[ -f "$STAGE_DIR/$f" ]] && mv -f "$STAGE_DIR/$f" "$dest/"
  done
  STATE_KEYS=()
  STATE_VALS=()
}

state_resumable_step() {
  state_get last_step ""
}
