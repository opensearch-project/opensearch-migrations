#!/usr/bin/env bash
# diag.sh — `migration-assistant diag` subcommand.
#
# On-demand diagnostics dump for the deployed stage. Reads state, resolves
# the kube context, runs the same diag bundle helm_dump_diagnostics emits
# on a failed install — but standalone, so the operator can pull it any
# time without re-running install/upgrade.

[[ -n "${__MIGRATE_DIAG_LOADED:-}" ]] && return 0
__MIGRATE_DIAG_LOADED=1

cmd_diag() {
  local args=("$@") i
  # shellcheck disable=SC2034  # STAGE_DIR is read by state_load / log_init
  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[$i]}" in
      --stage)    STAGE="${args[$((i + 1))]}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
      --stage=*)  STAGE="${args[$i]#--stage=}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
    esac
  done
  log_init
  ui_banner "OpenSearch Migration Assistant — diag"
  ui_dim "  cli=$CLI_VERSION  stage=$STAGE  workdir=$STAGE_DIR"
  state_load

  if [[ -z "$(state_get STAGE_NAME "")" ]]; then
    die "no deployed stage found in state. Run \`migration-assistant\` first or pass --stage <name>."
  fi
  local release="${HELM_RELEASE_NAME}" ns="${HELM_NAMESPACE}"

  helm_kctx_init
  ui_info "stage=$STAGE  release=$release  namespace=$ns"
  helm_dump_diagnostics "$release" "$ns"
  ui_ok "diag complete; full output: $LOG_FILE"
}
