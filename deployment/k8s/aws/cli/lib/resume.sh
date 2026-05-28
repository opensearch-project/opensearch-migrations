#!/usr/bin/env bash
# resume.sh — top-level controller. Routes a user run to manual or agent path,
# resuming from the previous step where applicable.

[[ -n "${__MIGRATE_RESUME_LOADED:-}" ]] && return 0
__MIGRATE_RESUME_LOADED=1

# cmd_resume — invoked by `migration-assistant` (the default subcommand) and
# by `migration-assistant resume`. Parses CLI flags, dispatches to the
# Manual or Agent path.
#
# Flags are documented in `cmd_help`. The set here is the union of the
# original migrate-cli flags AND the aws-bootstrap.sh flags it replaces,
# so existing scripts (Jenkins, test/awsRunEksValidation.sh) keep working.
cmd_resume() {
  local force_switch=0 force_mode=""
  local args=()

  # First pass: parse only --stage, so we know which stage's state to
  # load. Everything else is parsed in the second pass below, AFTER
  # state_load — otherwise the flag-driven state_set calls would write
  # against an empty in-memory state, blowing away last_step etc.
  local i
  for ((i = 1; i <= $#; i++)); do
    case "${!i}" in
      --stage)
        local next=$((i + 1))
        STAGE="${!next}"
        STAGE_DIR="$MIGRATE_HOME/$STAGE"
        ;;
      --stage=*)
        STAGE="${!i#--stage=}"
        STAGE_DIR="$MIGRATE_HOME/$STAGE"
        ;;
    esac
  done

  log_init
  ui_banner "OpenSearch Migration Assistant"
  ui_dim "  cli=$CLI_VERSION  stage=$STAGE  workdir=$STAGE_DIR"
  log_announce
  on_exit_register log_announce_exit
  upgrade_notice_for_cli

  # Load existing state BEFORE the second flag pass so the flag-driven
  # `state_set` calls merge into the loaded values rather than start
  # fresh. Without this, last_step="wizard_done" (and all other
  # previously-saved keys) gets clobbered to empty.
  state_load

  # Second pass: now safe to state_set, since the in-memory state
  # already contains everything from disk.
  while [[ $# -gt 0 ]]; do
    case "$1" in
      # Native flags
      # --stage doubles as STAGE_NAME (helm release + k8s namespace + CFN
      # suffix). The first pass already set STAGE/STAGE_DIR; here we
      # write STAGE_NAME so wizard/cfn/helm see it without re-prompting.
      --stage)        state_set STAGE_NAME "$2"; shift 2 ;;
      --stage=*)      state_set STAGE_NAME "${1#--stage=}"; shift ;;
      --switch)       force_switch=1; shift ;;
      --verbose|-v)   export MIGRATE_VERBOSE=1; shift ;;
      --reset-cache)  artifacts_reset_cache; shift ;;
      --non-interactive|-y)
                      export MIGRATE_NONINTERACTIVE=1
                      force_mode="${force_mode:-Manual}"
                      shift ;;
      --mode)         force_mode="$2"; shift 2 ;;
      --mode=*)       force_mode="${1#--mode=}"; shift ;;

      # aws-bootstrap.sh-compat flags. Stored as state.env entries so
      # downstream lib/* picks them up the same way as the wizard.
      --region)            state_set AWS_REGION "$2"; export AWS_REGION="$2"; shift 2 ;;
      --region=*)          local v=${1#--region=}; state_set AWS_REGION "$v"; export AWS_REGION="$v"; shift ;;
      --stack-name)        state_set CFN_STACK_NAME "$2"; shift 2 ;;
      --stack-name=*)      state_set CFN_STACK_NAME "${1#--stack-name=}"; shift ;;
      --version)           state_set MA_VERSION "$2"; shift 2 ;;
      --version=*)         state_set MA_VERSION "${1#--version=}"; shift ;;
      --use-public-images) state_set MIRROR_IMAGES "N"; shift ;;
      --skip-cfn-deploy)   state_set SKIP_CFN_DEPLOY "Y"; shift ;;
      --skip-console-exec) state_set SKIP_CONSOLE_EXEC "Y"; shift ;;
      --skip-setting-k8s-context) state_set SKIP_KUBECONFIG_UPDATE "Y"; shift ;;
      --kubectl-context)   state_set KUBECTL_CONTEXT "$2"; shift 2 ;;
      --kubectl-context=*) state_set KUBECTL_CONTEXT "${1#--kubectl-context=}"; shift ;;
      --namespace)         state_set STAGE_NAME "$2"; shift 2 ;;
      --namespace=*)       state_set STAGE_NAME "${1#--namespace=}"; shift ;;
      --helm-values)       state_set HELM_EXTRA_VALUES_FILE "$2"; shift 2 ;;
      --helm-values=*)     state_set HELM_EXTRA_VALUES_FILE "${1#--helm-values=}"; shift ;;
      # CFN deploy modes. Mutually exclusive; the LAST flag wins, but
      # we save and surface a warning if both are seen.
      --deploy-create-vpc-cfn) state_set CFN_TEMPLATE_VARIANT "create-vpc"; shift ;;
      --deploy-import-vpc-cfn) state_set CFN_TEMPLATE_VARIANT "import-vpc"; shift ;;
      --vpc-id)                state_set IMPORT_VPC_ID "$2"; shift 2 ;;
      --vpc-id=*)              state_set IMPORT_VPC_ID "${1#--vpc-id=}"; shift ;;
      --subnet-ids)            state_set IMPORT_SUBNET_IDS "$2"; shift 2 ;;
      --subnet-ids=*)          state_set IMPORT_SUBNET_IDS "${1#--subnet-ids=}"; shift ;;

      --eks-access-principal-arn)   state_set EKS_ACCESS_PRINCIPAL_ARN "$2"; shift 2 ;;
      --eks-access-principal-arn=*) state_set EKS_ACCESS_PRINCIPAL_ARN "${1#--eks-access-principal-arn=}"; shift ;;
      --ma-images-source)  state_set MA_IMAGES_SOURCE "$2"; shift 2 ;;
      --ma-images-source=*) state_set MA_IMAGES_SOURCE "${1#--ma-images-source=}"; shift ;;

      # Local chart override: skip artifacts_fetch and use a chart on
      # disk instead. Useful for chart development.
      --ma-chart-dir)      state_set MA_CHART_DIR "$2"; shift 2 ;;
      --ma-chart-dir=*)    state_set MA_CHART_DIR "${1#--ma-chart-dir=}"; shift ;;

      # NodePool selection. The chart's general-work-pool is the default;
      # --use-general-node-pool is a no-op (matches legacy semantics).
      # --disable-general-purpose-pool flips it off via helm --set.
      --use-general-node-pool)        state_set USE_GENERAL_NODE_POOL "Y"; shift ;;
      --disable-general-purpose-pool) state_set DISABLE_GENERAL_NODE_POOL "Y"; shift ;;

      # Flags whose backing behavior is not yet implemented. We accept
      # them so legacy invocations don't crash, but warn loudly so
      # operators don't think "bad invocation succeeded".
      --skip-test-images|--ignore-checks)
        ui_warn "flag '$1' has no effect in migrate-cli (was specific to --build flow)"; shift ;;
      --base-dir|--base-dir=*)
        # Repo root pointer for --build. We don't build, so this is a
        # no-op.
        case "$1" in --base-dir) shift 2 ;; *) shift ;; esac ;;
      --build|--image-tag|--image-tag=* \
        |--tls-mode|--tls-mode=* \
        |--pca-arn|--pca-arn=* \
        |--create-vpc-endpoints|--create-vpc-endpoints=*)
        ui_warn "flag '$1' is not yet implemented in migrate-cli; ignoring"
        case "$1" in
          --image-tag|--tls-mode|--pca-arn|--create-vpc-endpoints) shift 2 ;;
          *) shift ;;
        esac
        ;;
      --) shift; break ;;
      *)  args+=("$1"); shift ;;
    esac
  done

  # Persist the merged state (loaded values + any flag-driven overrides).
  state_save

  # `resuming` toggles whether we ask the operator anything. Set when:
  #   * `last_step` is non-empty AND
  #   * the operator accepts the "Resume from this point?" prompt
  # In that case we suppress mode-switch + wizard prompts; everything
  # downstream uses the values already saved in state.
  local resuming=0
  local prev_step; prev_step=$(state_resumable_step)
  if [[ -n "$prev_step" ]]; then
    case "$prev_step" in
      agent_handoff|console_handoff|ready)
        # The deploy is finished. The operator is re-running solely
        # to get back into the agent / console. Skip the resume
        # prompt — there's no "start over from discovery" answer
        # that's right here, and the prompt costs them a turn.
        resuming=1
        ;;
      *)
        ui_info "previous run progressed to: $prev_step"
        if ui_confirm "Resume from this point?" "Y"; then
          resuming=1
        else
          ui_warn "starting over (state preserved, but flow restarts from discovery)"
          state_set last_step ""
          state_save
          resuming=0
        fi
        ;;
    esac
  fi

  # Mode selection. The Agent path is gated behind MIGRATE_ENABLE_AGENT=1
  # (or `--mode Agent` / `agent` subcommand) until the agent UX is
  # production-ready. When the gate is off, Manual is the only mode and
  # we never prompt — there is no choice to make.
  local mode; mode=$(state_get MODE "")
  if [[ -n "$force_mode" ]]; then
    # Explicit --mode wins. Agent still requires the gate (or a
    # dedicated `agent` subcommand path that sets MIGRATE_ENABLE_AGENT).
    if [[ "$force_mode" == "Agent" && "${MIGRATE_ENABLE_AGENT:-0}" != "1" ]]; then
      die "--mode Agent requires MIGRATE_ENABLE_AGENT=1 (the agent path is preview-only)"
    fi
    mode="$force_mode"
    state_set MODE "$mode"
    state_save
  elif [[ "${MIGRATE_ENABLE_AGENT:-0}" != "1" ]]; then
    mode="Manual"
    state_set MODE "$mode"
    state_save
  elif [[ -z "$mode" || $force_switch -eq 1 ]]; then
    mode=$(_select_mode "$mode")
    state_set MODE "$mode"
    state_save
  elif [[ $resuming -eq 1 ]]; then
    ui_dim "  resuming with mode=$mode (use --switch to change)"
  else
    if ui_confirm "Mode: $mode. Switch?" "N"; then
      mode=$(_select_mode "$mode")
      state_set MODE "$mode"
      state_save
    fi
  fi
  export MIGRATE_RESUMING="$resuming"

  case "$mode" in
    Manual) manual_path ;;
    Agent)  agent_path ;;
    *)      die "unknown mode: $mode" ;;
  esac
}

# _select_mode <current> → echoes "Manual" or "Agent" on stdout.
#
# Caller does mode=$(_select_mode "$mode"); see ui.sh header for the rule
# (UI=stderr, return value=stdout). The chrome helpers below are stderr-safe.
_select_mode() {
  local current="${1:-Manual}"
  local default
  if [[ "$current" == "Agent" ]]; then default=2; else default=1; fi
  ui_step "Choose driver"
  printf '  [1] Manual — interactive shell (kubectl exec migration-console-0)\n' >&2
  printf '  [2] Agent  — let claude/q/kiro/… drive the migration\n' >&2
  local pick
  ui_prompt "Select" "$default" pick
  case "$pick" in
    1|Manual|manual) printf 'Manual\n' ;;
    2|Agent|agent)   printf 'Agent\n' ;;
    *)               die "invalid selection: $pick" ;;
  esac
}

# manual_path — full deploy + console exec.
manual_path() {
  ui_step "Discover environment"
  discover_os
  if ! discover_aws; then
    die "AWS credentials are required. Set up 'aws configure' and rerun."
  fi
  state_save

  ui_info "AWS identity:"
  aws sts get-caller-identity --output table 2>/dev/null || true

  ensure_tools_basic
  state_save

  discover_resources
  state_save

  ensure_tools_extended

  wizard_collect

  cfn_deploy_or_skip
  crane_mirror_or_skip
  helm_install_or_upgrade

  state_set last_step "ready"
  state_save

  console_exec
}

# cmd_help — short help text.
cmd_help() {
  cat <<'EOF'
migration-assistant — OpenSearch Migration Assistant CLI

Usage:
  migration-assistant [flags]                    Deploy / resume (default)
  migration-assistant resume   [flags]           Same as default
  migration-assistant console  [--stage NAME]    kubectl exec migration-console-0
  migration-assistant agent    [--stage NAME] [<agent>]
                                                 Open the agent (preview;
                                                 requires MIGRATE_ENABLE_AGENT=1)
  migration-assistant diag     [--stage NAME]    Dump diagnostics to migrate.log
  migration-assistant cleanup  [--stage NAME]    Tear down deploy + archive state
  migration-assistant version                    Print CLI version
  migration-assistant help                       This help

Common flags:
  --stage NAME            Stage name. Used as helm release, k8s namespace,
                          and CFN stack suffix. Default: ma.
  --region REGION         AWS region. Default: us-east-1.
  --version VER           Pin Migration Assistant artifact version.
                          Default: latest published release.
  --use-public-images     Skip image mirror; pull from public.ecr.aws.
  --non-interactive, -y   Accept defaults; for Jenkins / CI.
  --verbose, -v           Mirror logs to stderr live.
  --reset-cache           Wipe artifact cache before run.
  --switch                Re-prompt the deploy wizard.

CFN / EKS flags:
  --stack-name NAME                 Override CFN stack name.
  --skip-cfn-deploy                 Adopt an existing stack.
  --skip-setting-k8s-context        Create kubeconfig entry but don't switch active context.
  --kubectl-context CTX             Set the kubeconfig alias name.
  --eks-access-principal-arn ARN    Extra principal granted EKS access.

Helm flags:
  --skip-console-exec               Don't `kubectl exec` after install.
  --namespace NS                    Alias for --stage.
  --helm-values FILE                Extra helm values file.
  --ma-images-source SRC            Pull MA images from a different ECR.

State layout:
  ~/.opensearch-migrate/<stage>/state.env        (sourceable bash)
  ~/.opensearch-migrate/<stage>/state.json       (jq-canonical)
  ~/.opensearch-migrate/<stage>/log/migrate.log  (run log + rotation)
  ~/.opensearch-migrate/<stage>/artifacts/       (sha-pinned downloads)

Examples:
  # Default interactive deploy:
  migration-assistant

  # Re-enter the console after a deploy:
  migration-assistant console

  # Jenkins / CI:
  migration-assistant --non-interactive --skip-console-exec \
    --skip-setting-k8s-context --use-public-images \
    --stage ma --region us-east-1

Legacy aws-bootstrap.sh flags accepted-but-not-yet-implemented (warn-and-ignore):
  --build, --image-tag, --base-dir, --ma-chart-dir,
  --tls-mode, --pca-arn, --skip-test-images, --ignore-checks,
  --use-general-node-pool, --disable-general-purpose-pool,
  --deploy-import-vpc-cfn, --vpc-id, --subnet-ids, --create-vpc-endpoints
EOF
  printf '\nVersion: %s\n' "$CLI_VERSION"
}
