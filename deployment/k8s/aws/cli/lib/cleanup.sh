#!/usr/bin/env bash
# cleanup.sh — tear down the deployed Migration Assistant.
#
# Order matters:
#   1. helm uninstall   (drops k8s workloads)
#   2. cfn delete-stack (drops EKS, ECR, VPC)
#   3. archive state into <stage>/archive/<timestamp>/
#
# Optionally purges mirrored ECR repos (kept by default — cheap to keep, costly
# to re-mirror).

[[ -n "${__MIGRATE_CLEANUP_LOADED:-}" ]] && return 0
__MIGRATE_CLEANUP_LOADED=1

cmd_cleanup() {
  log_init
  log_announce
  on_exit_register log_announce_exit
  state_load

  local stack;   stack=$(state_get CFN_STACK_NAME "")
  local release; release=$(state_get HELM_RELEASE "")
  local region;  region=$(state_get AWS_REGION "")
  # Release-name == namespace per chart contract; STAGE_NAME holds both.
  local helm_ns; helm_ns=$(state_get STAGE_NAME "$release")
  # Pick up KUBECTL_CONTEXT from state so cleanup operates on the right
  # cluster when the operator's active context is something else.
  helm_kctx_init

  if [[ -z "$stack" && -z "$release" ]]; then
    ui_warn "nothing to clean up; state is empty for stage '$STAGE'"
    return 0
  fi

  ui_banner "Cleanup stage: $STAGE"
  if [[ -n "$release" ]]; then
    ui_info "  helm release : $release (namespace: $helm_ns)"
  fi
  if [[ -n "$stack" ]]; then
    ui_info "  CFN stack    : $stack (region: $region)"
  fi
  ui_warn "This will delete the EKS cluster, the VPC, and all migration-console state."
  if ! ui_confirm "Proceed with cleanup?" "N"; then
    ui_info "cleanup cancelled"
    return 0
  fi

  if [[ -n "$release" ]]; then
    ui_step "helm uninstall $release"
    "${HELM[@]}" uninstall "$release" --namespace "$helm_ns" --wait \
      || ui_warn "helm uninstall returned non-zero (continuing)"
  fi

  if [[ -n "$stack" ]]; then
    ui_step "Deleting CFN stack: $stack"
    aws cloudformation delete-stack --region "$region" --stack-name "$stack"
    aws cloudformation wait stack-delete-complete --region "$region" --stack-name "$stack" \
      || ui_warn "stack-delete-complete wait timed out (still deleting in AWS)"
  fi

  state_archive
  ui_ok "stage '$STAGE' cleaned up; state archived under $STAGE_DIR/archive/"
}
