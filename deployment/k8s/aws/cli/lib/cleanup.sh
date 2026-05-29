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
  # Release name AND namespace are pinned to "ma" (chart contract).
  # If state has any deploy artifact AT ALL but no HELM_RELEASE row
  # (legacy state files), assume the standard "ma" release.
  local helm_ns="$HELM_NAMESPACE"
  if [[ -z "$release" && -n "$stack" ]]; then
    release="$HELM_RELEASE_NAME"
  fi
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

# cmd_clear — wipe local state + history for the current stage AND any
# agent session storage rooted in this stage's workdir, WITHOUT touching
# AWS / kubernetes. Useful when state has drifted from the real world
# (e.g. AWS resources got deleted out of band) and the operator wants
# a fresh deploy + fresh agent conversation without `cleanup` failing
# on missing stacks.
cmd_clear() {
  local args=("$@") i force=0
  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[$i]}" in
      --stage)    STAGE="${args[$((i + 1))]}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
      --stage=*)  STAGE="${args[$i]#--stage=}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
      -y|--yes)   force=1 ;;
    esac
  done
  log_init
  ui_banner "Clear local state for stage: $STAGE"

  # Build a list of paths we'll be removing so the operator sees
  # exactly what's about to disappear before they confirm.
  local stage_dir_exists=0 agent_paths=()
  [[ -d "$STAGE_DIR" ]] && stage_dir_exists=1
  _cmd_clear_agent_paths_for "$STAGE_DIR" agent_paths

  if (( ! stage_dir_exists )) && (( ${#agent_paths[@]} == 0 )); then
    ui_info "nothing to clear; no state or agent sessions for stage '$STAGE'"
    return 0
  fi

  if (( stage_dir_exists )); then
    ui_dim "  workdir: $STAGE_DIR"
    ui_dim "  removes state.env / state.json / log / plan / history"
  fi
  if (( ${#agent_paths[@]} > 0 )); then
    ui_dim "  agent sessions rooted in this stage's workdir:"
    local p
    for p in "${agent_paths[@]+"${agent_paths[@]}"}"; do
      ui_dim "    $p"
    done
  fi
  ui_dim "  this does NOT touch AWS resources or kubernetes (use 'cleanup' for that)"

  if (( ! force )); then
    local _confirm_rc=0
    ui_confirm "Wipe local state + agent sessions for stage '$STAGE'?" "N" || _confirm_rc=$?
    if (( _confirm_rc != 0 )); then
      ui_info "clear cancelled"
      return 0
    fi
  fi

  if (( stage_dir_exists )); then
    rm -rf "$STAGE_DIR"
  fi
  local p
  for p in "${agent_paths[@]+"${agent_paths[@]}"}"; do
    rm -rf "$p"
  done
  ui_ok "stage '$STAGE' state + agent sessions cleared"
}

# _cmd_clear_agent_paths_for <stage_dir> <out_array>
#
# Append every agent session-storage path that's keyed on $stage_dir
# (or its encoded form) to the named output array. Skip paths that
# don't exist — the picker already handles "missing" gracefully.
#
# Each agent encodes the cwd differently:
#   claude → ~/.claude/projects/<cwd-with-/-and-.-replaced-by-->
#   codex  → no public per-cwd storage; rely on conversation reset
#            via the agent's own /clear (codex isn't pegged to cwd)
#   kiro   → ~/.kiro/conversations is workspace-scoped; conversations
#            for this STAGE_DIR aren't separately addressable, but
#            kiro's own /clear handles new-conversation-in-workspace
_cmd_clear_agent_paths_for() {
  local stage_dir="$1" out_arr="$2"
  # Claude: encode cwd → key by replacing / and . with -.
  local key="${stage_dir//\//-}"
  key="${key//./-}"
  local claude_dir="$HOME/.claude/projects/$key"
  if [[ -d "$claude_dir" ]]; then
    eval "${out_arr}+=(\"\$claude_dir\")"
  fi
}
