#!/usr/bin/env bats
# test_release_name_eq_namespace.bats — regression guard for the
# release-name vs namespace mismatch bug.
#
# The chart's `default-create-argo-migration-templates` Job templates
# Release.Name into a $NAMESPACE env var, then `kubectl apply -f - -n
# $NAMESPACE` rendered argo workflow YAMLs. If release_name != namespace
# the apply fails:
#   "the namespace from the provided object 'X' does not match 'Y'"
#
# We tie helm release name to the kubernetes namespace by deriving both
# from STAGE_NAME. Default STAGE_NAME = "ma" (matches aws-bootstrap.sh).

setup() {
  load 'helpers/stub.sh'
  setup_isolated_home
  PROJECT_ROOT="$(cd "$BATS_TEST_DIRNAME/.." && pwd)"
  export PROJECT_ROOT
  load_libs _common.sh ui.sh log.sh state.sh version.sh discover.sh \
            install_tools.sh artifacts.sh wizard.sh cfn.sh crane.sh helm.sh
  log_init
}

teardown() {
  teardown_isolated_home
}

# ---------- (1) HELM_DEFAULT_NS is "ma" (matches upstream bootstrap) ----------

@test "HELM_DEFAULT_NS is 'ma' (matches aws-bootstrap.sh default)" {
  [ "$HELM_DEFAULT_NS" = "ma" ]
}

@test "helm.sh no longer hardcodes a HELM_NS=ma constant separate from STAGE" {
  # The buggy version had `HELM_NS='ma'` at module scope while STAGE_NAME
  # could be 'default' — that mismatch was the bug. Replaced with
  # HELM_DEFAULT_NS plus a per-call local HELM_NS bound to STAGE_NAME.
  ! grep -E "^HELM_NS='ma'" "$PROJECT_ROOT/lib/helm.sh"
}

# ---------- (2) wizard default is "ma" ----------

@test "wizard prompts default STAGE_NAME=ma when state is empty" {
  # ui_prompt writes the default into the named variable when input is empty.
  # We stub ui_prompt to return whatever default it was given.
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
  # If state already has STAGE_NAME, wizard's default should be that value.
  state_load
  state_set STAGE_NAME "prod"
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

# ---------- (3) static checks: helm.sh derives namespace from STAGE_NAME ----------

@test "helm_install_or_upgrade declares 'local HELM_NS=\"\$stage\"'" {
  # The release-name vs namespace tie. If this line is removed or the
  # binding is changed, the chart's argo-templates Job will break again.
  grep -E 'local HELM_NS="\$stage"' "$PROJECT_ROOT/lib/helm.sh"
}

@test "helm_install_or_upgrade reads STAGE_NAME with HELM_DEFAULT_NS as fallback" {
  # The wizard sets STAGE_NAME, but on a fresh run state may be empty.
  # state_get STAGE_NAME "$HELM_DEFAULT_NS" gives us the safe default.
  grep -E 'state_get STAGE_NAME "\$HELM_DEFAULT_NS"' "$PROJECT_ROOT/lib/helm.sh"
}
