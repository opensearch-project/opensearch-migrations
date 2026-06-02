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

# Defensive source: uses json_get / is_macos / is_linux / optional_cmd.
# shellcheck source=lib/std.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/std.sh"

# discover_os — set OS_NAME, PKG_MGR.
discover_os() {
  local os pm
  if is_linux; then
    if grep -qi microsoft /proc/version 2>/dev/null; then
      os=wsl
    else
      os=linux
    fi
  elif is_macos; then
    os=darwin
  else
    os=other
  fi

  if [[ "$os" == darwin ]] && optional_cmd brew; then
    pm=brew
  elif optional_cmd apt-get; then
    pm=apt
  elif optional_cmd dnf; then
    pm=dnf
  elif optional_cmd yum; then
    pm=yum
  elif optional_cmd brew; then
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
  account=$(printf '%s\n' "$out" | json_get '.Account')
  arn=$(printf '%s\n' "$out" | json_get '.Arn')

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

# _jq_or_grep was a single-key helper bolted on for sts:GetCallerIdentity;
# json_get (lib/std.sh) generalizes it with the same fallback behavior.
# This stub stays to keep any out-of-tree callers (none today) working.
_jq_or_grep() {
  local jq_expr="$1"
  json_get "$jq_expr"
}
