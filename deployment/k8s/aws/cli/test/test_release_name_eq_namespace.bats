#!/usr/bin/env bats
# test_release_name_eq_namespace.bats — pin the helm release name and
# k8s namespace to "ma" regardless of the operator's chosen stage.
#
# The chart's create-argo-migration-templates Job templates
# Release.Name into a $NAMESPACE env var, then `kubectl apply -f - -n
# $NAMESPACE` rendered argo workflow YAMLs that hardcode
# `metadata.namespace: ma`. Release name AND namespace MUST be "ma".
#
# Stage is the AWS-side identifier (CFN stack suffix, ECR repo, state
# dir); it does NOT flow through to k8s.

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh dashboard.sh cfn.sh \
            crane.sh helm.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

# ---------- HELM_RELEASE_NAME / HELM_NAMESPACE constants ----------

@test "HELM_RELEASE_NAME is pinned to 'ma'" {
  [ "$HELM_RELEASE_NAME" = "ma" ]
}

@test "HELM_NAMESPACE is pinned to 'ma'" {
  [ "$HELM_NAMESPACE" = "ma" ]
}

@test "helm.sh no longer reads STAGE_NAME for the helm release / namespace" {
  # Regression guard: the old code did
  #   local stage=\$(state_get STAGE_NAME …)
  #   helm upgrade --install "\$stage" --namespace "\$stage"
  # Operators with --stage prod would then get a release named "prod"
  # in namespace "prod", which the chart can't render.
  ! grep -E 'helm.*upgrade --install "\$stage"' "$PROJECT_ROOT/lib/helm.sh"
  ! grep -E 'local HELM_NS="\$stage"'           "$PROJECT_ROOT/lib/helm.sh"
}

@test "helm_install_or_upgrade uses HELM_RELEASE_NAME and HELM_NAMESPACE" {
  grep -qE 'local release="\$HELM_RELEASE_NAME"' "$PROJECT_ROOT/lib/helm.sh"
  grep -qE 'local HELM_NS="\$HELM_NAMESPACE"'    "$PROJECT_ROOT/lib/helm.sh"
}

# ---------- wizard: stage stays operator-chosen ----------

@test "wizard prompts default STAGE_NAME=ma when state is empty" {
  ui_prompt() {
    local varname="${3:-}"
    if [[ -n "$varname" ]]; then
      printf -v "$varname" '%s' "$2"
    else
      printf '%s\n' "$2"
    fi
  }
  ui_confirm() { return 0; }
  export -f ui_prompt ui_confirm

  state_load
  wizard_collect

  run state_get STAGE_NAME
  [ "$status" -eq 0 ]
  [ "$output" = "ma" ]
}

@test "wizard pre-populates STAGE_NAME from existing state on resume" {
  state_load
  state_set STAGE_NAME "prod"
  state_set AWS_REGION "us-east-1"
  state_save

  ui_prompt() {
    local varname="${3:-}"
    if [[ -n "$varname" ]]; then
      printf -v "$varname" '%s' "$2"
    else
      printf '%s\n' "$2"
    fi
  }
  ui_confirm() { return 0; }
  export -f ui_prompt ui_confirm

  wizard_collect
  run state_get STAGE_NAME
  [ "$output" = "prod" ]
}
