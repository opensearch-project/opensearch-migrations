#!/usr/bin/env bash
# console.sh — exec into the migration-console-0 pod.
#
# This call replaces the running process with `kubectl exec`. Control returns
# to the user's shell; migration-assistant exits with the kubectl exit code.

[[ -n "${__MIGRATE_CONSOLE_LOADED:-}" ]] && return 0
__MIGRATE_CONSOLE_LOADED=1

# cmd_console — `migration-assistant console [--stage <name>]`. Skip the
# whole resume/discover/wizard/CFN/helm flow and jump straight into the
# migration-console pod. Errors clearly when the stage isn't deployed.
cmd_console() {
  local args=("$@") i
  # shellcheck disable=SC2034  # STAGE_DIR is read by state_load / log_init
  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[$i]}" in
      --stage)    STAGE="${args[$((i + 1))]}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
      --stage=*)  STAGE="${args[$i]#--stage=}"; STAGE_DIR="$MIGRATE_HOME/$STAGE" ;;
    esac
  done
  log_init
  state_load
  if [[ -z "$(state_get STAGE_NAME "")" ]]; then
    die "no deployed stage found in state. Run \`migration-assistant\` first or pass --stage <name>."
  fi
  helm_kctx_init
  _console_exec_into_pod "$HELM_NAMESPACE"
}

console_exec() {
  ui_ok "Migration Assistant deployment complete."
  ui_info "AWS identity at handoff:"
  aws sts get-caller-identity --output table 2>/dev/null \
    || ui_warn "could not call sts:GetCallerIdentity"

  state_set last_step "console_handoff"
  state_save

  # Honor --skip-console-exec (used by Jenkins / CI). When set, the run
  # ends here as a successful deployment; the caller can later attach
  # via `kubectl exec` themselves.
  if [[ "$(state_get SKIP_CONSOLE_EXEC N)" == "Y" ]]; then
    ui_ok "deployment complete; skipping console exec (--skip-console-exec)"
    return 0
  fi

  helm_kctx_init
  _console_exec_into_pod "$HELM_NAMESPACE"
}

# _console_exec_into_pod <ns>
#
# Run the migration-console-0 shell as a child rather than exec-ing it.
# Translate the operator's natural exit signals (Ctrl-C → 130, Ctrl-\
# → 131, SIGTERM → 143) into a clean exit 0 so the user's outer shell
# doesn't see a misleading "command terminated with exit code 130"
# every time they Ctrl-C inside the console.
_console_exec_into_pod() {
  local ns="$1"
  ui_step "Handing off to migration-console-0 (kubectl exec)"
  ui_dim "  Press Ctrl-D or 'exit' to leave the console."

  set +e
  "${KUBECTL[@]}" exec --stdin --tty --namespace "$ns" \
    migration-console-0 -- /bin/bash
  local rc=$?
  set -e

  case "$rc" in
    0|130|131|143)
      # 0   = clean exit / Ctrl-D
      # 130 = SIGINT (Ctrl-C inside the pod's shell)
      # 131 = SIGQUIT (Ctrl-\)
      # 143 = SIGTERM
      # All operator-driven; not a CLI failure.
      exit 0
      ;;
    *)
      ui_warn "kubectl exec exited with rc=$rc"
      exit "$rc"
      ;;
  esac
}
