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
  version_check

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
      # --deploy-create-vpc-cfn is the only supported CFN mode; accept
      # it as a no-op for legacy callers.
      --deploy-create-vpc-cfn) shift ;;
      --eks-access-principal-arn)   state_set EKS_ACCESS_PRINCIPAL_ARN "$2"; shift 2 ;;
      --eks-access-principal-arn=*) state_set EKS_ACCESS_PRINCIPAL_ARN "${1#--eks-access-principal-arn=}"; shift ;;
      --ma-images-source)  state_set MA_IMAGES_SOURCE "$2"; shift 2 ;;
      --ma-images-source=*) state_set MA_IMAGES_SOURCE "${1#--ma-images-source=}"; shift ;;

      # Flags accepted by aws-bootstrap.sh that this CLI does not yet
      # implement. Warn loudly and continue rather than silently
      # storing-and-ignoring (which makes a "bad invocation" look
      # successful at first and fail much later).
      --use-general-node-pool|--disable-general-purpose-pool \
        |--skip-test-images|--ignore-checks \
        |--base-dir|--base-dir=* \
        |--build|--image-tag|--image-tag=* \
        |--ma-chart-dir|--ma-chart-dir=* \
        |--tls-mode|--tls-mode=* \
        |--pca-arn|--pca-arn=* \
        |--deploy-import-vpc-cfn \
        |--vpc-id|--vpc-id=* \
        |--subnet-ids|--subnet-ids=* \
        |--create-vpc-endpoints|--create-vpc-endpoints=*)
                           ui_warn "flag '$1' is not implemented in migrate-cli; ignoring"
                           # Eat the value too if it's a value-taking flag.
                           case "$1" in
                             --base-dir|--image-tag|--ma-chart-dir|--tls-mode \
                              |--pca-arn|--vpc-id|--subnet-ids \
                              |--create-vpc-endpoints) shift 2 ;;
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
    ui_info "previous run progressed to: $prev_step"
    if ui_confirm "Resume from this point?" "Y"; then
      resuming=1
    else
      ui_warn "starting over (state preserved, but flow restarts from discovery)"
      state_set last_step ""
      state_save
      resuming=0
    fi
  fi

  local mode; mode=$(state_get MODE "")
  # Explicit --mode wins over both saved state and any prompts.
  if [[ -n "$force_mode" ]]; then
    mode="$force_mode"
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
  migration-assistant                            Resume (default subcommand)
  migration-assistant resume [flags]             Same as default
  migration-assistant cleanup [--stage NAME]     Tear down deploy + archive state
  migration-assistant version                    Print CLI version
  migration-assistant help                       This help

Native flags:
  --stage NAME            Named stage; also k8s namespace and helm release.
                          Default: ma  (must equal namespace per chart contract)
  --switch                Re-prompt Manual/Agent at startup
  --mode Manual|Agent     Force a mode without prompting
  --non-interactive, -y   Auto-accept every default; for Jenkins/CI
  --reset-cache           Wipe artifact cache before resume
  --verbose, -v           Mirror logs to stderr

aws-bootstrap.sh-compat flags (this CLI replaces aws-bootstrap.sh):
  --region REGION                   AWS region (default us-east-1)
  --stack-name NAME                 Override CFN stack name
  --version VER                     Migration Assistant artifact version
  --use-public-images               Skip mirroring; pull from public.ecr.aws
  --build                           Build images from source (developer)
  --image-tag TAG                   Image tag for --build
  --skip-cfn-deploy                 Reuse the existing CFN stack
  --skip-console-exec               Don't `kubectl exec` after install
  --skip-setting-k8s-context        Don't run `aws eks update-kubeconfig`
  --kubectl-context CTX             Override the kube context name
  --namespace NS                    Same as --stage (alias)
  --helm-values FILE                Extra helm values file (last-applied)
  --deploy-create-vpc-cfn           Use the Create-VPC CFN template
  --deploy-import-vpc-cfn           Use the Import-VPC CFN template
  --vpc-id ID                       Existing VPC for --deploy-import-vpc-cfn
  --subnet-ids LIST                 Existing subnets for --deploy-import-vpc-cfn
  --tls-mode MODE                   TLS configuration mode
  --pca-arn ARN                     ACM Private CA ARN
  --eks-access-principal-arn ARN    Extra principal granted EKS access
  --ma-images-source SRC            Pull images from a different ECR
  --ma-chart-dir DIR                Use a local chart dir instead of release

State layout:
  ~/.opensearch-migrate/<stage>/state.env        (sourceable bash)
  ~/.opensearch-migrate/<stage>/state.json       (jq-canonical)
  ~/.opensearch-migrate/<stage>/log/migrate.log  (run log + rotation)
  ~/.opensearch-migrate/<stage>/artifacts/       (sha-pinned downloads)

Modes:
  Manual : runs CFN + crane + helm, then kubectl exec migration-console-0.
  Agent  : same prerequisites; exec's claude/q/kiro/codex/… in its place.

Examples:
  # Interactive (a la aws-bootstrap.sh):
  migration-assistant --deploy-create-vpc-cfn --stage prod --region us-east-2

  # Jenkins / CI:
  migration-assistant --non-interactive --skip-console-exec \
    --skip-setting-k8s-context --use-public-images \
    --stage ma --region us-east-1
EOF
  printf '\nVersion: %s\n' "$CLI_VERSION"
}
