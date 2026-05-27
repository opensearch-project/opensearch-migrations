#!/usr/bin/env bash
# wizard.sh — collect deploy-time parameters only.
#
# Three fields. That's it. Anything migration-specific (source endpoint,
# engine, auth, target type) is collected later — by the migration console's
# own CLI in Manual mode, or by the agent in Agent mode (which prompts the
# operator interactively per skills/Startup.md).
#
#   STAGE_NAME     — Helm release name AND Kubernetes namespace AND CFN
#                    stack suffix. Default "ma". Must equal namespace because
#                    the chart's create-argo-migration-templates Job uses
#                    Release.Name as the namespace for `kubectl apply`.
#   MIRROR_IMAGES  — Y/N (skip = pass --use-public-images equivalent)
#   MA_VERSION     — pinned Migration Assistant artifact version
#
# Pre-fills from existing state for resume-friendly UX.

[[ -n "${__MIGRATE_WIZARD_LOADED:-}" ]] && return 0
__MIGRATE_WIZARD_LOADED=1

wizard_collect() {
  ui_step "Configure deployment"

  # Resume fast-path: if the operator accepted the resume prompt and
  # all the wizard fields are already in state, skip every prompt.
  # Saves the operator from re-tapping Enter through identical defaults.
  # Operator can re-prompt the wizard with `--switch` (which clears MODE)
  # OR by manually deleting state.env.
  if [[ "${MIGRATE_RESUMING:-0}" -eq 1 ]] \
      && [[ -n "$(state_get STAGE_NAME)" ]] \
      && [[ -n "$(state_get MIRROR_IMAGES)" ]] \
      && [[ -n "$(state_get MA_VERSION)" ]]; then
    ui_dim "  resuming — using saved deploy config:"
    ui_dim "    stage_name=$(state_get STAGE_NAME)"
    ui_dim "    mirror_images=$(state_get MIRROR_IMAGES)"
    ui_dim "    ma_version=$(state_get MA_VERSION)"
    return 0
  fi

  local stage_name mirror ma_ver

  # Default to "ma" — the kubernetes namespace the chart expects.
  # See lib/helm.sh comment for why release name == namespace.
  ui_prompt "Stage name (helm release + k8s namespace + CFN suffix)" \
            "$(state_get STAGE_NAME ma)" stage_name
  state_set STAGE_NAME "$stage_name"

  if ui_confirm "Mirror images from public.ecr.aws to your private ECR?" \
                "$(state_get MIRROR_IMAGES Y)"; then
    mirror=Y
  else
    mirror=N
  fi
  state_set MIRROR_IMAGES "$mirror"

  ui_prompt "Migration Assistant version" \
            "$(state_get MA_VERSION "$(ma_default_version)")" ma_ver
  state_set MA_VERSION "$ma_ver"

  state_set last_step "wizard_done"
  state_save

  ui_ok "wizard saved → $STAGE_DIR/state.env"
}
