#!/usr/bin/env bash
# cfn.sh — deploy the Migration-Assistant Create-VPC EKS template.
#
# Idempotent: if a stack with the configured name is already in CREATE_COMPLETE
# or UPDATE_COMPLETE, this function returns 0 immediately. Otherwise it runs
# `aws cloudformation deploy` and tails the events.

[[ -n "${__MIGRATE_CFN_LOADED:-}" ]] && return 0
__MIGRATE_CFN_LOADED=1

# Defensive source: uses array_contains, split_csv, join_by, is_interactive.
# shellcheck source=lib/std.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/std.sh"

CFN_TEMPLATE_CREATE_VPC='Migration-Assistant-Infra-Create-VPC-eks.template.json'
CFN_TEMPLATE_IMPORT_VPC='Migration-Assistant-Infra-Import-VPC-eks.template.json'

cfn_deploy_or_skip() {
  # State holds the chosen stack name (set by --stack-name); default is
  # "MigrationAssistant-<stage>".
  local stage_name; stage_name=$(state_get STAGE_NAME "ma")
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

  # Template + parameter overrides depend on the deploy mode. Default:
  # Create-VPC. --deploy-import-vpc-cfn flips to Import-VPC and requires
  # VPC_ID and SUBNET_IDS (validated up front so the operator gets a
  # clear error before AWS runs the deploy).
  local variant; variant=$(state_get CFN_TEMPLATE_VARIANT "create-vpc")
  local template_name params=("Stage=$(state_get STAGE_NAME)")
  case "$variant" in
    create-vpc)
      template_name="$CFN_TEMPLATE_CREATE_VPC"
      ;;
    import-vpc)
      template_name="$CFN_TEMPLATE_IMPORT_VPC"
      local vpc_id; vpc_id=$(state_get IMPORT_VPC_ID "")
      local subnets; subnets=$(state_get IMPORT_SUBNET_IDS "")
      [[ -z "$vpc_id" ]] && die "--deploy-import-vpc-cfn requires --vpc-id"
      [[ -z "$subnets" ]] && die "--deploy-import-vpc-cfn requires --subnet-ids"
      params+=("VPCId=$vpc_id" "VPCSubnetIds=$subnets")
      _cfn_import_vpc_endpoint_params "$vpc_id" "$subnets" "$region" params
      ;;
    *)
      die "unknown CFN_TEMPLATE_VARIANT: $variant"
      ;;
  esac

  ui_step "Downloading CFN template (MA v$ma_ver, variant=$variant)"
  local tmpl
  tmpl=$(artifacts_fetch "$template_name" "$ma_ver")
  ui_dim "  template: $tmpl"

  ui_step "Deploying CFN stack: $stack_name (region=$region)"
  local deploy_log="$STAGE_DIR/log/cfn-deploy.log"

  ui_dim "  events tailed live below; full deploy output: $deploy_log"
  log_info "cfn: aws cloudformation deploy stack=$stack_name region=$region variant=$variant"

  # Kick the deploy off in the background. Its stdout+stderr stream into
  # cfn-deploy.log; we tee that file into the main log periodically too.
  set +e
  ( aws cloudformation deploy \
      --region "$region" \
      --stack-name "$stack_name" \
      --template-file "$tmpl" \
      --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM CAPABILITY_AUTO_EXPAND \
      --parameter-overrides "${params[@]}" \
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
    local cfn_lines line
    read_lines cfn_lines "$deploy_log"
    for line in "${cfn_lines[@]+"${cfn_lines[@]}"}"; do
      log_info "cfn-deploy: $line"
    done
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
  # Seen-event tracking lives in memory now. The previous on-disk
  # cfn-events-seen-<pid>.txt + `grep -qxF "$key" "$seen_log"` per event
  # was the worst fork hotspot in the CLI: a 20-minute deploy with
  # hundreds of events forked grep hundreds of times. Phase-level resume
  # is handled by resume.sh; we don't need event-tail crash recovery.
  local cfn_seen=()

  local started; started=$(date +%s)
  local dashboard_mode=0
  if is_interactive; then
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
        if array_contains "$key" cfn_seen; then continue; fi
        cfn_seen+=("$key")

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
}

# ---------- Dashboard ----------
#
# CFN-domain status classifier. Every other dashboard mechanic
# (state, render, finish) is delegated to lib/dashboard.sh which
# multiple modules share.
#
# ABI v2: classifiers set __DASH_CLS via _dash_set_cls instead of
# printing to stdout. Zero forks per row — used to be the hottest fork
# path when a CFN deploy had dozens of resources.
_cfn_status_class() {
  case "$1" in
    *FAILED|*ROLLBACK*) _dash_set_cls fail    ;;
    *IN_PROGRESS)       _dash_set_cls prog    ;;
    # 'done' must be quoted — it's a bash reserved word and the cls value
    # we use to denote completion. Quoted, it's a literal string arg.
    *COMPLETE)          _dash_set_cls 'done'  ;;
    *)                  _dash_set_cls other   ;;
  esac
}

_cfn_dashboard_init() {
  local stack="$1" region="$2"
  dash_init cfn "$(printf '%sCFN%s %s/%s' "$__UI_C_DIM" "$__UI_C_RESET" "$stack" "$region")"
  dash_status_classifier cfn _cfn_status_class
}

# Compatibility shims for the rest of cfn.sh and any tests.
_cfn_res_upsert()      { dash_upsert cfn "$@"; }
_cfn_dashboard_render() { dash_render cfn "$@"; }
_cfn_dashboard_finish() { dash_finish cfn "$@"; }

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
  # UI output → stderr (per ui.sh discipline rule).
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
# statements. To keep the rest of
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

# _cfn_import_vpc_endpoint_params <vpc-id> <subnet-ids-csv> <region> <params-array>
#
# Translate the comma-separated --create-vpc-endpoints list into the
# per-endpoint Create*Endpoint=true CFN parameters:
#
#   s3              → CreateS3Endpoint=true (+ S3EndpointRouteTableIds)
#   ecr             → CreateECREndpoint=true
#   ecrDocker       → CreateECRDockerEndpoint=true
#   cloudwatchLogs  → CreateCloudWatchLogsEndpoint=true
#   efs             → CreateEFSEndpoint=true
#   sts             → CreateSTSEndpoint=true
#   eksAuth         → CreateEKSAuthEndpoint=true
#
# S3 is special: it's a Gateway endpoint, which needs the route-table IDs
# for every subnet we want to associate it with.
_cfn_import_vpc_endpoint_params() {
  local vpc_id="$1" subnets_csv="$2" region="$3" out_name="$4"
  local list; list=$(state_get CREATE_VPC_ENDPOINTS "")
  [[ -z "$list" ]] && return 0

  # shellcheck disable=SC2034 # appended via eval
  local __ep_extras=()
  # parts is populated by split_csv via eval; declare it locally so the
  # for-loop expansion is clean and so we don't leak into globals.
  local parts=()
  split_csv "$list" parts
  local ep
  for ep in "${parts[@]+"${parts[@]}"}"; do
    case "$ep" in
      s3)
        __ep_extras+=("CreateS3Endpoint=true")
        local rt_ids
        rt_ids=$(_cfn_subnet_route_table_ids "$vpc_id" "$subnets_csv" "$region")
        if [[ -n "$rt_ids" ]]; then
          __ep_extras+=("S3EndpointRouteTableIds=$rt_ids")
        else
          ui_warn "no route-table IDs resolved for S3 gateway endpoint; CFN may reject the deploy"
        fi
        ;;
      ecr)            __ep_extras+=("CreateECREndpoint=true") ;;
      ecrDocker)      __ep_extras+=("CreateECRDockerEndpoint=true") ;;
      cloudwatchLogs) __ep_extras+=("CreateCloudWatchLogsEndpoint=true") ;;
      efs)            __ep_extras+=("CreateEFSEndpoint=true") ;;
      sts)            __ep_extras+=("CreateSTSEndpoint=true") ;;
      eksAuth)        __ep_extras+=("CreateEKSAuthEndpoint=true") ;;
      "" | *)
        ui_warn "ignoring unknown --create-vpc-endpoints entry: '$ep' (expected: s3, ecr, ecrDocker, cloudwatchLogs, efs, sts, eksAuth)"
        ;;
    esac
  done

  # Append to the caller's params array. The caller is `cfn_deploy_or_skip`
  # whose `params` is local; we extend it via eval.
  if (( ${#__ep_extras[@]} > 0 )); then
    eval "$out_name+=(\"\${__ep_extras[@]}\")"
  fi
}

# _cfn_subnet_route_table_ids <vpc-id> <subnet-ids-csv> <region>
#
# Print a comma-separated list of route-table IDs for the given subnets,
# falling back to the VPC's main route table when a subnet has no explicit
# association. CFN expects one route-table per subnet in
# S3EndpointRouteTableIds.
_cfn_subnet_route_table_ids() {
  local vpc_id="$1" subnets_csv="$2" region="$3"
  local sids=()
  split_csv "$subnets_csv" sids
  local out=() main_rt="" sid rt
  for sid in "${sids[@]+"${sids[@]}"}"; do
    rt=$(aws ec2 describe-route-tables \
      ${region:+--region "$region"} \
      --filters "Name=association.subnet-id,Values=$sid" \
      --query 'RouteTables[0].RouteTableId' --output text 2>/dev/null \
      || echo "None")
    if [[ -z "$rt" || "$rt" == "None" ]]; then
      if [[ -z "$main_rt" ]]; then
        main_rt=$(aws ec2 describe-route-tables \
          ${region:+--region "$region"} \
          --filters "Name=vpc-id,Values=$vpc_id" "Name=association.main,Values=true" \
          --query 'RouteTables[0].RouteTableId' --output text 2>/dev/null \
          || echo "None")
      fi
      rt="$main_rt"
    fi
    [[ -n "$rt" && "$rt" != "None" ]] && out+=("$rt")
  done
  # Dedup (linear bash) + join with commas.
  local seen=() unique=() x
  for x in "${out[@]+"${out[@]}"}"; do
    if ! array_contains "$x" seen; then
      seen+=("$x")
      unique+=("$x")
    fi
  done
  join_by , "${unique[@]+"${unique[@]}"}"
}
