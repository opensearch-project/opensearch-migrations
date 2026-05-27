#!/usr/bin/env bash
# cfn.sh — deploy the Migration-Assistant Create-VPC EKS template.
#
# Idempotent: if a stack with the configured name is already in CREATE_COMPLETE
# or UPDATE_COMPLETE, this function returns 0 immediately. Otherwise it runs
# `aws cloudformation deploy` and tails the events.

[[ -n "${__MIGRATE_CFN_LOADED:-}" ]] && return 0
__MIGRATE_CFN_LOADED=1

CFN_TEMPLATE_NAME='Migration-Assistant-Infra-Create-VPC-eks.template.json'

cfn_deploy_or_skip() {
  # Allow flag override of the stack name (--stack-name) so the
  # aws-bootstrap.sh CLI surface is preserved. State holds the chosen
  # name; default is "MigrationAssistant-<stage>".
  local stage_name; stage_name=$(state_get STAGE_NAME "$HELM_DEFAULT_NS")
  local stack_name; stack_name=$(state_get CFN_STACK_NAME "MigrationAssistant-${stage_name}")
  state_set CFN_STACK_NAME "$stack_name"
  local region;     region=$(state_get AWS_REGION)
  local ma_ver;     ma_ver=$(state_get MA_VERSION)
  [[ -z "$region" ]] && die "AWS_REGION not set"

  if [[ "$(state_get SKIP_CFN_DEPLOY N)" == "Y" ]]; then
    ui_info "--skip-cfn-deploy → skipping CFN entirely"
    state_save
    return 0
  fi

  if _cfn_stack_healthy "$stack_name" "$region"; then
    ui_ok "CFN stack $stack_name already healthy; skipping deploy"
    state_set CFN_STACK_NAME "$stack_name"
    state_save
    return 0
  fi

  ui_step "Downloading CFN template (MA v$ma_ver)"
  local tmpl
  tmpl=$(artifacts_fetch "$CFN_TEMPLATE_NAME" "$ma_ver")
  ui_dim "  template: $tmpl"

  ui_step "Deploying CFN stack: $stack_name (region=$region)"
  local stage_param; stage_param=$(state_get STAGE_NAME)
  local deploy_log="$STAGE_DIR/log/cfn-deploy.log"

  ui_dim "  events tailed live below; full deploy output: $deploy_log"
  log_info "cfn: aws cloudformation deploy stack=$stack_name region=$region"

  # Kick the deploy off in the background. Its stdout+stderr stream into
  # cfn-deploy.log; we tee that file into the main log periodically too.
  # The subprocess runs in its own process group (`set -m`-equivalent via
  # `setsid`-style backgrounding) so SIGINT can take it + children down
  # together via `kill -- -<pgid>`.
  set +e
  ( aws cloudformation deploy \
      --region "$region" \
      --stack-name "$stack_name" \
      --template-file "$tmpl" \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
      --parameter-overrides "Stage=$stage_param" \
      --no-fail-on-empty-changeset \
      >"$deploy_log" 2>&1
  ) &
  local deploy_pid=$!
  set -e
  on_signal_track_pid "$deploy_pid"

  # Live-tail CFN events to operator stderr AND to the main log. Continues
  # until the deploy subprocess exits.
  _cfn_tail_events "$stack_name" "$region" "$deploy_pid"

  # Use a wait-loop instead of a single `wait`. Bash blocks SIGINT delivery
  # while inside a built-in `wait` for a SPECIFIC pid; a 1-second polled
  # wait gives the trap a window to fire.
  local rc=0
  while kill -0 "$deploy_pid" 2>/dev/null; do
    sleep 1
  done
  wait "$deploy_pid" 2>/dev/null
  rc=$?
  on_signal_untrack_pid "$deploy_pid"

  # Tee the final deploy output into the main log so it's all in one place.
  if [[ -f "$deploy_log" ]]; then
    while IFS= read -r line; do
      log_info "cfn-deploy: $line"
    done <"$deploy_log"
  fi

  if [[ $rc -ne 0 ]]; then
    ui_err "CFN deploy failed (rc=$rc); see $deploy_log"
    return $rc
  fi
  ui_ok "CFN stack $stack_name deployed"
  state_set CFN_STACK_NAME "$stack_name"
  state_set last_step "cfn_done"
  state_save
}

# _cfn_stack_healthy <stack> <region> — exit 0 if stack is in a healthy
# CREATE/UPDATE_COMPLETE state.
_cfn_stack_healthy() {
  local stack="$1" region="$2"
  local status
  status=$(aws cloudformation describe-stacks \
    --region "$region" --stack-name "$stack" \
    --query 'Stacks[0].StackStatus' --output text 2>/dev/null \
    || echo "DOES_NOT_EXIST")
  case "$status" in
    CREATE_COMPLETE|UPDATE_COMPLETE) return 0 ;;
    *)                               return 1 ;;
  esac
}

# _cfn_tail_events — poll describe-stack-events while a deploy is running.
#
# Two render modes:
#   * TTY (stderr is a terminal): a sticky live dashboard — banner + spinner
#     + counts/progress bar + a snapshot resource table. Redrawn on every
#     poll. Each new event STILL appended to the log file unconditionally.
#   * Non-TTY: per-event line printer (the previous behavior). Suitable for
#     CI, script captures, less.
#
# The dashboard is operator-friendly: instead of seeing a wall of
# IN_PROGRESS / IN_PROGRESS / IN_PROGRESS lines stream in for 20 minutes,
# the operator sees a steady frame with current counts and the most recent
# resource state.
_cfn_tail_events() {
  local stack="$1" region="$2" deploy_pid="$3"
  local seen_log="$STAGE_DIR/log/cfn-events-seen-$$.txt"
  : >"$seen_log"

  local started; started=$(date +%s)
  local dashboard_mode=0
  if [[ -t 2 ]]; then
    dashboard_mode=1
    _cfn_dashboard_init "$stack" "$region"
  fi

  local first_iter=1
  while kill -0 "$deploy_pid" 2>/dev/null; do
    local out
    out=$(aws cloudformation describe-stack-events \
      --region "$region" --stack-name "$stack" \
      --query 'StackEvents[].[Timestamp,LogicalResourceId,ResourceStatus,ResourceStatusReason]' \
      --output text 2>/dev/null \
      | tac 2>/dev/null \
      || echo "")

    if [[ -n "$out" ]]; then
      while IFS=$'\t' read -r ts logical status reason; do
        [[ -z "$logical" ]] && continue
        local key="${ts}|${logical}|${status}"
        if grep -qxF "$key" "$seen_log"; then continue; fi
        printf '%s\n' "$key" >>"$seen_log"

        # Always log every event, regardless of render mode.
        _cfn_log_event "$ts" "$logical" "$status" "$reason"

        # Skip events from the very first poll (history before our start).
        [[ $first_iter -eq 1 ]] && continue

        if [[ $dashboard_mode -eq 1 ]]; then
          _cfn_res_upsert "$logical" "$status" "$reason" "$ts"
        else
          _cfn_print_event "$ts" "$logical" "$status" "$reason"
        fi
      done <<<"$out"
      first_iter=0
    fi

    if [[ $dashboard_mode -eq 1 ]]; then
      _cfn_dashboard_render "$started"
    fi

    sleep 4
  done

  if [[ $dashboard_mode -eq 1 ]]; then
    _cfn_dashboard_finish "$started"
  fi
  rm -f "$seen_log"
}

# ---------- Dashboard internals ----------
#
# Resource state is two parallel arrays (bash 3.2-compatible):
#   _CFN_RES_KEYS[i]  — logical resource ID
#   _CFN_RES_VALS[i]  — packed "STATUS|REASON|TS|UPDATED_EPOCH"
#
# We keep the LAST status per resource. Render time we sort:
#   * FAILED/ROLLBACK rows first (red)
#   * IN_PROGRESS rows next (dim, with spinner glyph)
#   * COMPLETE rows last (green, only the most recent few)
# bounded by _CFN_DASH_MAX so the panel height is fixed.

_CFN_RES_KEYS=()
_CFN_RES_VALS=()
_CFN_DASH_MAX=10
_CFN_DASH_LAST_HEIGHT=0
_CFN_DASH_SPINNER='⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏'
_CFN_DASH_TICK=0
_CFN_DASH_STACK=''
_CFN_DASH_REGION=''

_cfn_dashboard_init() {
  _CFN_DASH_STACK="$1"
  _CFN_DASH_REGION="$2"
  _CFN_RES_KEYS=()
  _CFN_RES_VALS=()
  _CFN_DASH_LAST_HEIGHT=0
  _CFN_DASH_TICK=0
  # Hide the cursor while the dashboard owns this region.
  printf '\033[?25l' >&2
  on_exit_register _cfn_dashboard_cursor_restore
}

_cfn_dashboard_cursor_restore() {
  printf '\033[?25h' >&2
}

# _cfn_res_idx <logical>  → echoes index in _CFN_RES_KEYS (or empty)
_cfn_res_idx() {
  local target="$1" i
  for ((i=0; i < ${#_CFN_RES_KEYS[@]}; i++)); do
    if [[ "${_CFN_RES_KEYS[$i]}" == "$target" ]]; then
      printf '%d\n' "$i"
      return
    fi
  done
}

# _cfn_res_upsert <logical> <status> <reason> <ts>
_cfn_res_upsert() {
  local logical="$1" status="$2" reason="$3" ts="$4"
  local epoch; epoch=$(date +%s)
  local packed="$status|$reason|$ts|$epoch"
  local idx; idx=$(_cfn_res_idx "$logical")
  if [[ -n "$idx" ]]; then
    _CFN_RES_VALS[idx]="$packed"
  else
    _CFN_RES_KEYS[${#_CFN_RES_KEYS[@]}]="$logical"
    _CFN_RES_VALS[${#_CFN_RES_VALS[@]}]="$packed"
  fi
}

# _cfn_fmt_elapsed <started_epoch>  → "Mm Ss"
_cfn_fmt_elapsed() {
  local started="$1"
  local now; now=$(date +%s)
  local d=$(( now - started ))
  local m=$(( d / 60 ))
  local s=$(( d % 60 ))
  printf '%dm %02ds\n' "$m" "$s"
}

# _repeat_char <char> <n>  → echoes char × n
_repeat_char() {
  local ch="$1" n="$2"
  if (( n <= 0 )); then return; fi
  local out
  printf -v out '%*s' "$n" ''
  printf '%s\n' "${out// /$ch}"
}

# _cfn_status_class <status>  → fail|prog|done|other
_cfn_status_class() {
  case "$1" in
    *FAILED|*ROLLBACK*) printf 'fail\n' ;;
    *IN_PROGRESS)       printf 'prog\n' ;;
    *COMPLETE)          printf 'done\n' ;;
    *)                  printf 'other\n' ;;
  esac
}

# _cfn_dashboard_render <started_epoch>
# Redraws the panel by moving the cursor up by the previous render height
# and clearing each line. Always writes to stderr (UI discipline).
_cfn_dashboard_render() {
  local started="$1"
  _CFN_DASH_TICK=$(( _CFN_DASH_TICK + 1 ))
  local glyph_idx=$(( _CFN_DASH_TICK % ${#_CFN_DASH_SPINNER} ))
  local glyph="${_CFN_DASH_SPINNER:$glyph_idx:1}"

  # Tally counts.
  local total=${#_CFN_RES_KEYS[@]}
  local in_progress=0 completed=0 failed=0 i v cls
  for ((i=0; i < total; i++)); do
    v="${_CFN_RES_VALS[$i]}"
    cls=$(_cfn_status_class "${v%%|*}")
    case "$cls" in
      fail) failed=$((failed + 1)) ;;
      prog) in_progress=$((in_progress + 1)) ;;
      done) completed=$((completed + 1)) ;;
    esac
  done

  # Move cursor up by previous render height + erase those lines.
  if (( _CFN_DASH_LAST_HEIGHT > 0 )); then
    printf '\033[%dA' "$_CFN_DASH_LAST_HEIGHT" >&2
    printf '\033[J' >&2
  fi

  local height=0
  local elapsed; elapsed=$(_cfn_fmt_elapsed "$started")

  # Header line.
  printf '%s%s%s  %sCFN%s %s/%s  %selapsed %s%s\n' \
    "$__UI_C_BOLD" "$glyph" "$__UI_C_RESET" \
    "$__UI_C_DIM" "$__UI_C_RESET" \
    "$_CFN_DASH_STACK" "$_CFN_DASH_REGION" \
    "$__UI_C_DIM" "$elapsed" "$__UI_C_RESET" >&2
  height=$(( height + 1 ))

  # Counts + progress bar.
  local seen=$(( total > 0 ? total : 1 ))
  local pct=$(( (completed * 100) / seen ))
  local bar_w=24
  local fill=$(( (completed * bar_w) / seen ))
  (( fill > bar_w )) && fill=$bar_w
  local filled empty
  filled=$(_repeat_char '█' "$fill")
  empty=$(_repeat_char '░' "$(( bar_w - fill ))")
  printf '  %s%s%s%s  %d%%   %s✓ %d%s  %s↻ %d%s  %s✗ %d%s  total %d\n' \
    "$__UI_C_GREEN" "$filled" "$__UI_C_DIM" "$empty" \
    "$pct" \
    "$__UI_C_GREEN" "$completed" "$__UI_C_RESET" \
    "$__UI_C_DIM" "$in_progress" "$__UI_C_RESET" \
    "$__UI_C_RED" "$failed" "$__UI_C_RESET" \
    "$total" >&2
  printf '%s\n' "$__UI_C_RESET" >&2 # reset after the dim empty bar
  height=$(( height + 2 ))

  # Resource rows: failed first, then in_progress, then most-recent done.
  # _cfn_dash_emit_class echoes the number of rows it actually printed so
  # the parent can track the panel height.
  local rows_left=$_CFN_DASH_MAX printed
  printed=$(_cfn_dash_emit_class fail "$failed" "$rows_left")
  height=$(( height + printed ))
  rows_left=$(( rows_left - printed ))
  if (( rows_left > 0 )); then
    printed=$(_cfn_dash_emit_class prog "$in_progress" "$rows_left")
    height=$(( height + printed ))
    rows_left=$(( rows_left - printed ))
  fi
  if (( rows_left > 0 )); then
    # quoted "done" so shellcheck doesn't confuse the class name with the keyword
    printed=$(_cfn_dash_emit_class "done" "$completed" "$rows_left")
    height=$(( height + printed ))
  fi

  # Hint line.
  printf '%s  %s%s\n' "$__UI_C_DIM" "(Ctrl-C to abort; full event log: $LOG_FILE)" "$__UI_C_RESET" >&2
  height=$(( height + 1 ))

  _CFN_DASH_LAST_HEIGHT=$height
}

# _cfn_dash_emit_class <class> <count_in_class> <max_rows>
# Iterates resources of <class>, prints up to <max_rows> formatted rows on
# stderr. Echoes the number of rows actually printed (so the parent can
# track height).
_cfn_dash_emit_class() {
  local cls="$1" cls_count="$2" max="$3"
  (( cls_count == 0 || max == 0 )) && { printf '0\n'; return; }

  local color glyph
  case "$cls" in
    fail) color="$__UI_C_RED";    glyph='✗' ;;
    prog) color="$__UI_C_DIM";    glyph='↻' ;;
    done) color="$__UI_C_GREEN";  glyph='✓' ;;
    *)    color="$__UI_C_RESET";  glyph='·' ;;
  esac

  # Build a list of "epoch|logical|status|reason" entries for this class,
  # then sort newest-first, then print up to <max>.
  local i v ts_epoch entries=() logical
  for ((i=0; i < ${#_CFN_RES_KEYS[@]}; i++)); do
    v="${_CFN_RES_VALS[$i]}"
    [[ "$(_cfn_status_class "${v%%|*}")" == "$cls" ]] || continue
    logical="${_CFN_RES_KEYS[$i]}"
    ts_epoch="${v##*|}"
    entries[${#entries[@]}]="$ts_epoch|$logical|$v"
  done

  # Sort by leading epoch descending.
  local printed=0 line status reason
  while IFS= read -r line; do
    (( printed >= max )) && break
    [[ -z "$line" ]] && continue
    # Parse: <epoch>|<logical>|<status>|<reason>|<ts>|<epoch>
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

_cfn_dashboard_finish() {
  local started="$1"
  # Final repaint without spinner glyph; cursor restored.
  _CFN_DASH_TICK=0
  _cfn_dashboard_render "$started"
  _cfn_dashboard_cursor_restore
}

# _cfn_log_event — write the event to the main log only (no terminal).
_cfn_log_event() {
  local ts="$1" logical="$2" status="$3" reason="$4"
  if [[ -n "$reason" && "$reason" != "None" ]]; then
    log_info "cfn-event: $logical $status — $reason"
  else
    log_info "cfn-event: $logical $status"
  fi
}

_cfn_print_event() {
  local ts="$1" logical="$2" status="$3" reason="$4"
  local color
  case "$status" in
    *FAILED|*ROLLBACK*)  color="$__UI_C_RED" ;;
    *COMPLETE)           color="$__UI_C_GREEN" ;;
    *IN_PROGRESS)        color="$__UI_C_DIM" ;;
    *)                   color="$__UI_C_RESET" ;;
  esac
  # UI chrome → stderr (per ui.sh discipline rule).
  # Plain copy → log file so the operator's `tail -f` shows it too.
  local plain
  if [[ -n "$reason" && "$reason" != "None" ]]; then
    printf '  %s%-32s %-22s%s  %s\n' "$color" "$logical" "$status" "$__UI_C_RESET" "$reason" >&2
    plain=$(printf '  %-32s %-22s  %s' "$logical" "$status" "$reason")
  else
    printf '  %s%-32s %-22s%s\n'    "$color" "$logical" "$status" "$__UI_C_RESET" >&2
    plain=$(printf '  %-32s %-22s' "$logical" "$status")
  fi
  log_info "cfn-event: $plain"
}

# cfn_outputs <stack> <region> → print KEY=VALUE per output. Used by helm.sh
# and crane.sh to feed values like ECR registry, EKS cluster name into the
# downstream tools.
#
# The opensearch-migrations CFN stacks publish a single output called
# MigrationsExportString — a long string of bash `export VAR=VALUE; …`
# statements that aws-bootstrap.sh sources verbatim. To keep the rest of
# the CLI's logic tidy, we expand that here so callers see flat KEY=VALUE
# pairs:
#
#   MIGRATIONS_EKS_CLUSTER_NAME=migration-eks-cluster-default-us-east-1
#   MIGRATIONS_ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com/...
#   AWS_ACCOUNT=123456789012
#   AWS_CFN_REGION=us-east-1
#   VPC_ID=vpc-...
#   STAGE=default
#   ...
#
# Plus every other CFN output (raw OutputKey=OutputValue) in case the stack
# format changes again.
cfn_outputs() {
  local stack="$1" region="$2"
  local raw
  raw=$(aws cloudformation describe-stacks \
    --region "$region" --stack-name "$stack" \
    --query 'Stacks[0].Outputs[].[OutputKey,OutputValue]' \
    --output text 2>/dev/null || true)
  [[ -z "$raw" ]] && return 0

  # Pass A: raw output-key=value pairs (preserves backwards compat if a
  # future template adds a flat EKSClusterName output).
  printf '%s\n' "$raw" | awk -F'\t' '{ printf "%s=%s\n", $1, $2 }'

  # Pass B: extract the bash exports inside the MigrationsExportString.
  printf '%s\n' "$raw" \
    | awk -F'\t' '$1 == "MigrationsExportString" { print $2 }' \
    | _cfn_extract_exports
}

# _cfn_extract_exports — read bash export statements from stdin, emit one
# KEY=VALUE per line. Tolerates `;` separators, leading whitespace, and
# values that themselves contain `=` (URLs, ARNs).
_cfn_extract_exports() {
  awk '
    BEGIN { RS=";" }
    {
      # Trim leading whitespace.
      sub(/^[[:space:]]+/, "", $0)
      # Strip leading "export ".
      sub(/^export[[:space:]]+/, "", $0)
      if ($0 ~ /^[A-Za-z_][A-Za-z0-9_]*=/) {
        print $0
      }
    }
  '
}

# cfn_output_value <stack> <region> <key> → print the value of <key> or
# nothing. Helper for callers that want a single named field.
cfn_output_value() {
  local stack="$1" region="$2" key="$3"
  cfn_outputs "$stack" "$region" \
    | awk -F= -v k="$key" '$1 == k { sub(/^[^=]+=/, ""); print; exit }'
}

# _cfn_pick <outputs> <key1> [<key2> …]  →  print first matching value.
# Outputs are KEY=VALUE lines (as produced by cfn_outputs). The first key
# that resolves to a non-empty value wins; missing keys silently advance.
# Used by helm.sh / crane.sh to tolerate template renames between releases.
_cfn_pick() {
  local outputs="$1"; shift
  local key val
  for key in "$@"; do
    val=$(printf '%s\n' "$outputs" \
      | awk -F= -v k="$key" '$1 == k { sub(/^[^=]+=/, ""); print; exit }')
    if [[ -n "$val" ]]; then
      printf '%s\n' "$val"
      return 0
    fi
  done
  return 0
}
