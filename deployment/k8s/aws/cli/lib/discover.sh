#!/usr/bin/env bash
# discover.sh — read-only environment discovery.
#
# Populates state KEYS:
#   OS_NAME              linux|darwin|wsl
#   PKG_MGR              brew|apt|dnf|yum|none
#   AWS_ACCOUNT          12-digit account number
#   AWS_REGION           e.g. us-east-1
#   AWS_USER_ARN         caller identity ARN
#   EKS_CLUSTERS         space-separated cluster names
#   CFN_MA_STACKS        space-separated stack names matching MigrationAssistant
#
# All discovery functions are NON-DESTRUCTIVE. They only read.

[[ -n "${__MIGRATE_DISCOVER_LOADED:-}" ]] && return 0
__MIGRATE_DISCOVER_LOADED=1

# discover_os — set OS_NAME, PKG_MGR.
discover_os() {
  local os pm
  case "$(uname -s)" in
    Linux)
      if grep -qi microsoft /proc/version 2>/dev/null; then
        os=wsl
      else
        os=linux
      fi
      ;;
    Darwin) os=darwin ;;
    *)      os=other ;;
  esac

  if [[ "$os" == darwin ]] && command -v brew >/dev/null 2>&1; then
    pm=brew
  elif command -v apt-get >/dev/null 2>&1; then
    pm=apt
  elif command -v dnf >/dev/null 2>&1; then
    pm=dnf
  elif command -v yum >/dev/null 2>&1; then
    pm=yum
  elif command -v brew >/dev/null 2>&1; then
    pm=brew
  else
    pm=none
  fi

  state_set OS_NAME "$os"
  state_set PKG_MGR "$pm"
  log_info "discover_os: os=$os pkg=$pm"
}

# discover_aws — set AWS_ACCOUNT, AWS_REGION, AWS_USER_ARN. Returns 1 if
# unauthenticated.
discover_aws() {
  if ! command -v aws >/dev/null 2>&1; then
    log_warn "aws CLI not on PATH; skipping aws discovery"
    return 1
  fi
  local out
  if ! out=$(aws sts get-caller-identity --output json 2>/dev/null); then
    ui_warn "AWS credentials not configured. Run \`aws configure\` or set AWS_PROFILE."
    return 1
  fi
  local account arn
  account=$(printf '%s\n' "$out" | _jq_or_grep '.Account' 'Account')
  arn=$(printf '%s\n' "$out" | _jq_or_grep '.Arn' 'Arn')

  local region="${AWS_REGION:-${AWS_DEFAULT_REGION:-}}"
  if [[ -z "$region" ]]; then
    region=$(aws configure get region 2>/dev/null || echo "")
  fi
  state_set AWS_ACCOUNT "$account"
  state_set AWS_USER_ARN "$arn"
  state_set AWS_REGION   "$region"
  log_info "discover_aws: account=$account region=$region arn=$arn"
}

# discover_resources — list EKS clusters and MA-related CFN stacks.
# shellcheck disable=SC2016  # JMESPath query strings are intentionally literal
discover_resources() {
  local region
  region=$(state_get AWS_REGION)
  [[ -z "$region" ]] && { log_warn "no region set; skipping resources discovery"; return 0; }

  if command -v aws >/dev/null 2>&1; then
    local clusters stacks
    clusters=$(aws eks list-clusters --region "$region" --query 'clusters[]' --output text 2>/dev/null || echo "")
    stacks=$(aws cloudformation list-stacks \
      --region "$region" \
      --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE UPDATE_ROLLBACK_COMPLETE ROLLBACK_COMPLETE \
      --query 'StackSummaries[?contains(StackName,`MigrationAssistant`)].StackName' \
      --output text 2>/dev/null || echo "")
    state_set EKS_CLUSTERS    "${clusters//$'\t'/ }"
    state_set CFN_MA_STACKS   "${stacks//$'\t'/ }"
    log_info "discover_resources: clusters='$clusters' stacks='$stacks'"
  fi
}

# Internal helper: extract a top-level JSON field. Uses jq if present,
# otherwise a coarse grep+sed. Good enough for sts:GetCallerIdentity output.
_jq_or_grep() {
  local jq_expr="$1" grep_key="$2"
  if command -v jq >/dev/null 2>&1; then
    jq -r "$jq_expr"
  else
    grep -o "\"${grep_key}\":[[:space:]]*\"[^\"]*\"" \
      | head -1 \
      | sed -E 's/.*"([^"]+)"$/\1/'
  fi
}
