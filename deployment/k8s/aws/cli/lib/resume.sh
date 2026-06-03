#!/usr/bin/env bash
# resume.sh — top-level controller. Routes a user run to manual or agent path,
# resuming from the previous step where applicable.

[[ -n "${__MIGRATE_RESUME_LOADED:-}" ]] && return 0
__MIGRATE_RESUME_LOADED=1

# Defensive source: resume's pre-prompt uses timeline_render.
# shellcheck source=lib/timeline.sh
source "${LIB_DIR:-$(dirname "${BASH_SOURCE[0]}")}/timeline.sh"

# cmd_resume — invoked by `migration-assistant` (the default subcommand) and
# by `migration-assistant resume`. Parses CLI flags, dispatches to the
# Manual or Agent path.
#
# Flags are documented in `cmd_help`.
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

  # Banner uses branding.appName from manifest.json when set, otherwise
  # falls back to the upstream string. Same for the optional tagline +
  # version line. manifest_init is idempotent and cheap.
  manifest_init
  local app_name; app_name=$(manifest_brand appName)
  [[ -z "$app_name" ]] && app_name="OpenSearch Migration Assistant"
  ui_banner "$app_name"
  local tagline; tagline=$(manifest_brand tagline)
  [[ -n "$tagline" ]] && ui_dim "  $tagline"

  local version_str; version_str=$(manifest_brand versionString)
  if [[ -z "$version_str" ]]; then
    version_str="$CLI_VERSION$(manifest_pack_summary)"
  fi
  ui_dim "  cli=$version_str  stage=$STAGE  workdir=$STAGE_DIR"
  # Preview flag — this CLI is opt-in while it bakes through a few
  # releases. Production deploys still use aws-bootstrap.sh. Suppress
  # only when the operator explicitly opts in via MIGRATE_PREVIEW_ACK=1
  # so the message doesn't nag every CI run that has knowingly chosen
  # the preview path.
  if [[ "${MIGRATE_PREVIEW_ACK:-0}" != "1" ]]; then
    ui_dim "  preview — production deploys still use aws-bootstrap.sh; export MIGRATE_PREVIEW_ACK=1 to silence"
  fi

  # First-run welcome (only when state is fresh — no last_step set).
  if [[ -z "$(state_resumable_step)" ]]; then
    local welcome; welcome=$(manifest_brand welcomeMessage)
    [[ -n "$welcome" ]] && ui_info "$welcome"
  fi

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
      # --stage names the CFN stack suffix + ECR repo + state dir. It
      # does NOT flow through to the helm release name or k8s namespace
      # — those are always "ma" because the chart hardcodes that name
      # in its rendered resources. STAGE_NAME's role is purely
      # AWS-side identifier.
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

      # Deploy-control flags. Stored as state.env entries so downstream
      # lib/* picks them up the same way as the wizard.
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

      # NodePool selection. The chart's `cluster.useCustomKarpenterNodePool`
      # decides whether to deploy the custom Karpenter NodePool resource.
      # --use-general-node-pool flips it off (use EKS Auto Mode general-purpose
      # pool instead). --disable-general-purpose-pool calls
      # `aws eks update-cluster-config` post-install to remove the
      # general-purpose pool from the cluster's compute config (reserved for
      # the rare prod-isolation case).
      --use-general-node-pool)        state_set USE_GENERAL_NODE_POOL "Y"; shift ;;
      --disable-general-purpose-pool) state_set DISABLE_GENERAL_NODE_POOL "Y"; shift ;;

      # Build-from-source path. Wired through lib/build.sh.
      --build)             state_set BUILD_FROM_SOURCE Y; shift ;;
      --image-tag)         state_set IMAGE_TAG "$2"; shift 2 ;;
      --image-tag=*)       state_set IMAGE_TAG "${1#--image-tag=}"; shift ;;
      --base-dir)          state_set BASE_DIR "$2"; shift 2 ;;
      --base-dir=*)        state_set BASE_DIR "${1#--base-dir=}"; shift ;;
      --skip-test-images)  state_set SKIP_TEST_IMAGES Y; shift ;;

      # TLS configuration. Threaded into helm install via _helm_tls_flags.
      --tls-mode)          state_set TLS_MODE "$2"; shift 2 ;;
      --tls-mode=*)        state_set TLS_MODE "${1#--tls-mode=}"; shift ;;
      --pca-arn)           state_set PCA_ARN "$2"; shift 2 ;;
      --pca-arn=*)         state_set PCA_ARN "${1#--pca-arn=}"; shift ;;

      # Import-VPC endpoint control. Stored as a comma-separated list,
      # expanded into Create*Endpoint=true CFN parameters in cfn.sh.
      --create-vpc-endpoints)   state_set CREATE_VPC_ENDPOINTS "$2"; shift 2 ;;
      --create-vpc-endpoints=*) state_set CREATE_VPC_ENDPOINTS "${1#--create-vpc-endpoints=}"; shift ;;

      # Skip the import-VPC subnet-connectivity preflight check. The
      # check warns when subnets have no NAT/IGW route AND the required
      # VPC endpoints (S3 / ECR API / ECR Docker) are missing.
      --ignore-checks)     state_set IGNORE_CHECKS Y; shift ;;
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
        ui_info "previous run progressed to: $(timeline_phase_label "$prev_step")"
        timeline_render "$prev_step"
        printf '\n' >&2
        if ui_confirm "Resume from this point?" "Y"; then
          resuming=1
        else
          # Operator chose "start over". Clear last_step AND the wizard
          # inputs so the wizard re-prompts every value rather than
          # silently reusing the saved config (which is the opposite of
          # what they asked for).
          ui_warn "starting over: re-prompting wizard for stage / mirror / version"
          state_set last_step ""
          state_set STAGE_NAME ""
          state_set MIRROR_IMAGES ""
          state_set MA_VERSION ""
          state_save
          resuming=0
        fi
        ;;
    esac
  fi

  # Mode selection. Picker fires on first run (no MODE in state) or
  # when --switch forces it; the choice persists for every subsequent
  # invocation. --mode <name> bypasses the picker entirely.
  #
  # Agent mode is preview-only and gated behind MIGRATE_ENABLE_AGENT=1.
  # Reject `--mode Agent` (or a stale state.MODE=Agent) when the gate
  # isn't on so operators can't end up in the AI flow accidentally.
  local mode; mode=$(state_get MODE "")
  if [[ "$force_mode" == "Agent" || ( -z "$force_mode" && "$mode" == "Agent" ) ]] \
     && [[ "${MIGRATE_ENABLE_AGENT:-0}" != "1" ]]; then
    die "Agent mode is a preview feature gated behind MIGRATE_ENABLE_AGENT=1. Set the env var if you want to evaluate it; otherwise use --mode Manual."
  fi
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

# _select_mode <current> → echoes the selected mode id on stdout.
#
# Caller does mode=$(_select_mode "$mode"); see ui.sh header for the rule
# (UI=stderr, return value=stdout). The UI helpers below are stderr-safe.
#
# Mode list comes from branding.modes[] in manifest.json. The manifest's
# upstream-default copy lists Manual first (default), Agent second; a
# pack can reorder, relabel, change the default, or hide a mode by
# setting available:false.
#
# Agent mode is a PREVIEW feature behind an env-var gate:
#     MIGRATE_ENABLE_AGENT=1
# Without it the picker hides the Agent entry entirely and the legacy
# `--mode Agent` flag errors out at parse time. This keeps operators
# from selecting an unfinished UX by default while preserving the
# extension surface for partner builds that pre-vet AI use.
_select_mode() {
  local current="${1:-Manual}"

  manifest_init

  # Build parallel arrays of visible modes from the manifest.
  local ids=() labels=() descs=() default_idx=1
  if manifest_have; then
    local i=1 id label desc is_default
    while IFS='|' read -r id label desc is_default; do
      [[ -z "$id" ]] && continue
      # Agent mode is gated; skip when not enabled.
      if [[ "$id" == "Agent" ]] && [[ "${MIGRATE_ENABLE_AGENT:-0}" != "1" ]]; then
        continue
      fi
      ids+=("$id"); labels+=("$label"); descs+=("$desc")
      # Pick the default: prior MODE state wins; otherwise the manifest's
      # default:true entry; otherwise position 1.
      if [[ -n "$current" && "$id" == "$current" ]]; then
        default_idx=$i
      elif [[ -z "$current" && "$is_default" == "1" ]]; then
        default_idx=$i
      fi
      i=$((i + 1))
    done < <(manifest_modes)
  fi

  # Fallback (no manifest): Manual always; Agent only when gated on.
  if (( ${#ids[@]} == 0 )); then
    ids=(Manual)
    labels=(Manual)
    descs=("you in control. CLI deploys the chart, then drops you into migration-console-0 to run the migration commands yourself.")
    if [[ "${MIGRATE_ENABLE_AGENT:-0}" == "1" ]]; then
      ids+=(Agent)
      labels+=(AI)
      descs+=("an LLM coding agent (claude/codex/q/kiro) drives the migration. CLI deploys the chart, then hands control to the agent with a pre-loaded skill set (preview — refine your invocation as needed).")
      [[ "$current" == "Agent" ]] && default_idx=2
    fi
  fi

  ui_step "How do you want to drive this migration?"
  local i
  for ((i = 0; i < ${#ids[@]}; i++)); do
    printf '  %s[%d] %s%s — %s\n' \
      "$__UI_C_BOLD" "$((i + 1))" "${labels[$i]}" "$__UI_C_RESET" \
      "${descs[$i]}" >&2
  done

  local pick
  ui_prompt "Select" "$default_idx" pick

  # Numeric pick → look up by index. Name pick → match against id or
  # label (case-insensitive on the first letter to keep the legacy
  # `manual` / `MANUAL` / `m` shapes working).
  case "$pick" in
    [0-9]*)
      if (( pick >= 1 && pick <= ${#ids[@]} )); then
        printf '%s\n' "${ids[$((pick - 1))]}"
        return 0
      fi
      ;;
  esac
  for ((i = 0; i < ${#ids[@]}; i++)); do
    if [[ "$pick" == "${ids[$i]}" || "$pick" == "${labels[$i]}" ]]; then
      printf '%s\n' "${ids[$i]}"
      return 0
    fi
    # Lowercase first-letter match (legacy "m" / "a" shortcuts)
    local id_lower="${ids[$i]:0:1}"
    id_lower=$(printf '%s' "$id_lower" | tr '[:upper:]' '[:lower:]')
    local pick_lower="${pick:0:1}"
    pick_lower=$(printf '%s' "$pick_lower" | tr '[:upper:]' '[:lower:]')
    if [[ "$pick_lower" == "$id_lower" ]]; then
      printf '%s\n' "${ids[$i]}"
      return 0
    fi
  done

  die "invalid selection: $pick"
}

# manual_path — full deploy + console exec.
#
# Fast path: when state has last_step=ready, the helm release is
# `deployed`, AND the configured kubectl context still resolves, the
# operator is just re-entering the console — skip the entire
# discover→wizard→CFN→crane→helm pipeline (which is idempotent but
# takes ~30s of API roundtrips) and exec straight into console.
manual_path() {
  if _manual_can_skip_to_console; then
    ui_ok "deploy already complete (last_step=ready); skipping to console"
    console_exec
    return
  fi

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

  # Kubeconfig must be set up before --build (which talks to the EKS-hosted
  # buildkit) and before crane (which logs into ECR for the operator's
  # context). Idempotent: helm_install_or_upgrade no-ops on the setup if
  # we've already done it here.
  helm_kubeconfig_setup
  build_images_or_skip
  crane_mirror_or_skip
  helm_install_or_upgrade

  state_set last_step "ready"
  state_save

  console_exec
}

# _manual_can_skip_to_console — exit 0 if we can re-enter the console
# without the full deploy pipeline.
#
# Conditions:
#   * state.last_step == ready (or console_handoff)
#   * helm release exists and is `deployed`
#   * the migration-console-0 pod exists in the saved namespace
# Anything else returns non-zero and the full flow runs.
_manual_can_skip_to_console() {
  local last; last=$(state_resumable_step)
  case "$last" in
    # All three terminal states imply the deploy ran cleanly.
    # agent_handoff: previous run finished into the agent.
    # console_handoff: previous run finished into the console.
    # ready: deploy finished but console_exec hasn't been called yet.
    ready|console_handoff|agent_handoff) ;;
    *) return 1 ;;
  esac

  local ns="$HELM_NAMESPACE"
  local release="$HELM_RELEASE_NAME"
  [[ -z "$(state_get STAGE_NAME "")" ]] && return 1

  helm_kctx_init || return 1

  # helm release must be `deployed`. _helm_release_status echoes empty
  # when the release isn't installed.
  local status; status=$(_helm_release_status "$release" "$ns")
  [[ "$status" == "deployed" ]] || return 1

  # console pod must exist (Running OR a re-startable phase).
  "${KUBECTL[@]}" get pod migration-console-0 --namespace "$ns" >/dev/null 2>&1 \
    || return 1

  return 0
}

# cmd_help — short help text.
cmd_help() {
  manifest_init
  local header; header=$(manifest_brand helpHeader)
  [[ -z "$header" ]] && header="migration-assistant — OpenSearch Migration Assistant CLI"
  printf '%s\n\n' "$header"
  cat <<'EOF'
Usage:
  migration-assistant [flags]                    Deploy / resume (default)
  migration-assistant resume   [flags]           Same as default
  migration-assistant console  [--stage NAME]    kubectl exec migration-console-0
  migration-assistant agent    [--stage NAME] [<agent>]
                                                 Open an LLM coding agent
                                                 (claude / codex / q / kiro)
  migration-assistant diag     [--stage NAME]    Dump diagnostics to migrate.log
  migration-assistant clear    [--stage NAME]    Wipe local state/history (no AWS changes)
  migration-assistant cleanup  [--stage NAME]    Tear down deploy + archive state
  migration-assistant version                    Print CLI version
  migration-assistant help                       This help

Common flags:
  --stage NAME            Stage name. Suffixes the CFN stack name and
                          ECR repo path. (helm release + k8s namespace
                          are always "ma" — chart contract.)
  --region REGION         AWS region. Default: us-east-1.
  --version VER           Pin Migration Assistant artifact version.
                          Default: latest published release.
  --use-public-images     Skip image mirror; pull from public.ecr.aws.
  --non-interactive, -y   Accept defaults; for Jenkins / CI.
  --verbose, -v           Mirror logs to stderr live.
  --reset-cache           Wipe artifact cache before run.
  --switch                Re-prompt the deploy wizard.
  --mode Manual|Agent     Bypass the mode picker. Agent mode is a
                          PREVIEW and requires MIGRATE_ENABLE_AGENT=1.

Env vars:
  MIGRATE_HOME            State root (default: ./migration-assistant-workspace)
  MIGRATE_NONINTERACTIVE  Accept all prompt defaults (=1 implies --non-interactive)
  MIGRATE_ENABLE_AGENT    Set to 1 to surface AI/Agent mode in the picker
                          and accept --mode Agent. Off by default.
  MIGRATE_SKIP_MCP        Skip every MCP registration during agent setup
  MIGRATE_VERBOSE         Mirror logs to stderr live (=1 implies --verbose)

CFN / EKS flags:
  --stack-name NAME                 Override CFN stack name.
  --skip-cfn-deploy                 Adopt an existing stack.
  --skip-setting-k8s-context        Create kubeconfig entry but don't switch active context.
  --kubectl-context CTX             Set the kubeconfig alias name.
  --eks-access-principal-arn ARN    Extra principal granted EKS access.
  --deploy-create-vpc-cfn           Deploy the Create-VPC EKS template (default).
  --deploy-import-vpc-cfn           Deploy the Import-VPC EKS template.
  --vpc-id VPC                      (with --deploy-import-vpc-cfn) target VPC.
  --subnet-ids ID,ID                (with --deploy-import-vpc-cfn) target subnets.
  --create-vpc-endpoints LIST       Comma-separated: s3, ecr, ecrDocker,
                                    cloudwatchLogs, efs, sts, eksAuth. CFN
                                    creates each as a VPC endpoint on
                                    --vpc-id. S3 (gateway) endpoint pulls
                                    route-table IDs from the subnets.
  --ignore-checks                   Skip the import-VPC pre-flight checks.

Build-from-source flags:
  --build                           Build MA images from a local repo and
                                    push to the per-stage ECR. Requires
                                    docker buildx + a kube context.
  --image-tag TAG                   Per-build image tag (default: latest).
  --base-dir DIR                    opensearch-migrations repo root for
                                    --build (auto-resolved from CLI install).
  --ma-chart-dir DIR                Use a local helm chart instead of the
                                    release tarball.
  --skip-test-images                Skip the test-image build targets in
                                    gradle (for CI smoke runs).

Helm flags:
  --skip-console-exec               Don't `kubectl exec` after install.
  --namespace NS                    Alias for --stage.
  --helm-values FILE                Extra helm values file.
  --ma-images-source SRC            Pull MA images from a different ECR.
  --use-general-node-pool           Disable the chart's custom Karpenter
                                    NodePool; use EKS Auto Mode general-
                                    purpose pool instead.
  --disable-general-purpose-pool    Post-install: ask EKS to drop the
                                    general-purpose pool from compute
                                    config (rare prod-isolation case).

TLS flags:
  --tls-mode MODE                   none | self-signed | pca-existing | pca-create.
  --pca-arn ARN                     ARN of the AWS PCA (required for
                                    --tls-mode pca-existing).

State layout ($MIGRATE_HOME defaults to ./migration-assistant-workspace
in the directory you run from — that directory is your migration project;
set MIGRATE_HOME to share one state root across projects):
  $MIGRATE_HOME/<stage>/state.env        (sourceable bash)
  $MIGRATE_HOME/<stage>/state.json       (jq-canonical)
  $MIGRATE_HOME/<stage>/log/migrate.log  (run log + rotation)
  $MIGRATE_HOME/<stage>/artifacts/       (sha-pinned downloads)

Examples:
  # Default interactive deploy:
  migration-assistant

  # Re-enter the console after a deploy:
  migration-assistant console

  # Jenkins / CI from a checkout:
  migration-assistant --non-interactive --skip-console-exec \
    --skip-setting-k8s-context --build --skip-test-images \
    --use-general-node-pool --stage ma --region us-east-1
EOF
  local version_str; version_str=$(manifest_brand versionString)
  if [[ -z "$version_str" ]]; then
    version_str="$CLI_VERSION$(manifest_pack_summary)"
  fi
  printf '\nVersion: %s\n' "$version_str"
}
