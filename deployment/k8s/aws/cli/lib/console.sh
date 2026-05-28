#!/usr/bin/env bash
# console.sh — exec into the migration-console-0 pod.
#
# This call replaces the running process with `kubectl exec`. Control returns
# to the user's shell; migration-assistant exits with the kubectl exit code.

[[ -n "${__MIGRATE_CONSOLE_LOADED:-}" ]] && return 0
__MIGRATE_CONSOLE_LOADED=1

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

  local ns; ns=$(state_get STAGE_NAME "$HELM_DEFAULT_NS")
  helm_kctx_init
  ui_step "Handing off to migration-console-0 (kubectl exec)"
  ui_dim "  Press Ctrl-D or 'exit' to leave the console."
  exec "${KUBECTL[@]}" exec --stdin --tty \
    --namespace "$ns" \
    migration-console-0 -- /bin/bash
}
