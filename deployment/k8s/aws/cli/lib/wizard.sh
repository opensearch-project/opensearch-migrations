#!/usr/bin/env bash
# wizard.sh — collect deploy-time inputs.
#
# Three inputs. That's it. Anything migration-specific (source endpoint,
# engine, auth, target type) is collected later — by the migration console's
# own CLI in Manual mode, or by the agent in Agent mode.
#
#   STAGE_NAME     — Helm release name AND Kubernetes namespace AND CFN
#                    stack suffix. Default "ma". Must equal namespace because
#                    the chart's create-argo-migration-templates Job uses
#                    Release.Name as the namespace for `kubectl apply`.
#   MIRROR_IMAGES  — Y/N (skip = pass --use-public-images equivalent)
#   MA_VERSION     — pinned Migration Assistant artifact version
#
# Discovery-aware: if discover_resources found existing MigrationAssistant-*
# CFN stack(s), the wizard offers to adopt one rather than ask for a stage
# name from scratch. This handles the terraform / external-CFN /
# already-deployed cases Greg asked about.
#
# Pre-fills from existing state for resume-friendly UX.

[[ -n "${__MIGRATE_WIZARD_LOADED:-}" ]] && return 0
__MIGRATE_WIZARD_LOADED=1

wizard_collect() {
  ui_step "Configure deployment"

  # Resume fast-path: if the operator accepted the resume prompt and
  # all the wizard inputs are already in state, skip every prompt.
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

  # Adopt-existing path. discover_resources writes CFN_MA_STACKS into
  # state — a space-separated list of MigrationAssistant-* stacks
  # already in the account/region. If we find any, offer to adopt one
  # before prompting for a fresh stage name.
  local existing; existing=$(state_get CFN_MA_STACKS "")
  if [[ -n "$existing" ]] && [[ -z "$(state_get STAGE_NAME "")" ]]; then
    local adopted
    adopted=$(_wizard_offer_existing_stack "$existing")
    if [[ -n "$adopted" ]]; then
      stage_name="${adopted#MigrationAssistant-}"
      state_set STAGE_NAME "$stage_name"
      state_set CFN_STACK_NAME "$adopted"
      state_set SKIP_CFN_DEPLOY "Y"
      ui_ok "adopting existing stack: $adopted (stage_name=$stage_name)"
    fi
  fi

  if [[ -z "$(state_get STAGE_NAME "")" ]]; then
    # Default to "ma" — the kubernetes namespace the chart expects.
    # See lib/helm.sh comment for why release name == namespace.
    ui_prompt "Stage name (used for helm release, k8s namespace, and CFN suffix)" \
              "$(state_get STAGE_NAME ma)" stage_name
    state_set STAGE_NAME "$stage_name"
  fi

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

# _wizard_offer_existing_stack <space-separated stack list>
#
# Returns the chosen stack name on stdout (UI on stderr per discipline)
# or empty if the operator opted out / declined.
_wizard_offer_existing_stack() {
  local list="$1"
  # shellcheck disable=SC2206  # word-split list intentional
  local arr=($list)
  local n=${#arr[@]}

  if (( n == 0 )); then
    return 0
  fi

  if (( n == 1 )); then
    ui_info "found existing Migration Assistant stack: ${arr[0]}" >&2
    if ui_confirm "Adopt this stack (skip CFN deploy)?" "Y"; then
      printf '%s\n' "${arr[0]}"
    fi
    return 0
  fi

  ui_info "found multiple Migration Assistant stacks:" >&2
  local i=1 s
  for s in "${arr[@]}"; do
    printf '  [%d] %s\n' "$i" "$s" >&2
    i=$((i + 1))
  done
  printf '  [%d] none — deploy a new stack\n' "$i" >&2
  local pick
  ui_prompt "Pick" "1" pick
  if [[ "$pick" =~ ^[0-9]+$ ]] && (( pick >= 1 && pick <= n )); then
    printf '%s\n' "${arr[$((pick - 1))]}"
  fi
}
