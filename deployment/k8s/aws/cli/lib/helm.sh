#!/usr/bin/env bash
# helm.sh — install/upgrade the migration-assistant chart and wait for
# migration-console-0 readiness.
#
# Idempotent: re-running invokes `helm upgrade --install`, which is a no-op
# if values + chart version match what's already installed.

[[ -n "${__MIGRATE_HELM_LOADED:-}" ]] && return 0
__MIGRATE_HELM_LOADED=1

# Context-bound kubectl + helm. helm_install_or_upgrade rebinds these to
# `--kube-context X` / `--context X` after looking up state.
# Default to bare invocations so tests / pre-context callers still work.
HELM=(helm)
KUBECTL=(kubectl)

# Allow other modules (cleanup.sh, console.sh) to refresh their copies
# from the saved KUBECTL_CONTEXT without re-implementing this logic.
helm_kctx_init() {
  local ctx; ctx=$(state_get KUBECTL_CONTEXT "")
  if [[ -n "$ctx" ]]; then
    HELM=(helm --kube-context "$ctx")
    KUBECTL=(kubectl --context "$ctx")
  fi
}

HELM_CHART_NAME='migration-assistant'
# The Migration Assistant chart hardcodes namespace=ma in its rendered
# resources AND uses .Release.Name to write the namespace env var into
# its post-install argo-templates Job. To keep things consistent we
# pin BOTH the helm release name AND the kubernetes namespace to "ma"
# regardless of the operator's chosen stage. The stage name is the
# CFN-stack / ECR-repo / state-directory namer; it does NOT flow
# through to k8s.
#
# This means a single EKS cluster runs ONE migration-assistant install
# at a time (release=ma, ns=ma). Multiple stages → multiple CFN stacks
# → multiple EKS clusters.
HELM_RELEASE_NAME='ma'
HELM_NAMESPACE='ma'

# helm_kubeconfig_setup — read the EKS cluster name from CFN outputs,
# `aws eks update-kubeconfig` it, and bind HELM=()/KUBECTL=() to the
# resulting context. Idempotent. Must run after CFN deploy, before
# anything that touches the cluster (build, crane, helm). Splits out
# of helm_install_or_upgrade so the build path (`--build`) and any
# resume fast-path can reuse it.
helm_kubeconfig_setup() {
  local stack_name; stack_name=$(state_get CFN_STACK_NAME)
  local region;     region=$(state_get AWS_REGION)

  ui_step "Updating kubeconfig for EKS cluster"

  # Read all outputs once. Look up MIGRATIONS_EKS_CLUSTER_NAME
  # (the canonical key) and tolerate EKSClusterName as a fallback.
  local outputs cluster_name registry_from_cfn
  outputs=$(cfn_outputs "$stack_name" "$region")

  cluster_name=$(_cfn_pick "$outputs" MIGRATIONS_EKS_CLUSTER_NAME EKSClusterName)
  if [[ -z "$cluster_name" ]]; then
    die "could not read EKS cluster name from CFN outputs of '$stack_name' (looked for MIGRATIONS_EKS_CLUSTER_NAME, EKSClusterName)"
  fi
  registry_from_cfn=$(_cfn_pick "$outputs" MIGRATIONS_ECR_REGISTRY ECRRegistry)
  [[ -n "$registry_from_cfn" ]] && state_set CRANE_REGISTRY "$registry_from_cfn"

  # Always create the kubeconfig entry
  # (with --alias so multi-cluster hosts get a stable context name), and
  # only skip the *active context switch* when --skip-setting-k8s-context
  # is set. Default alias is the cluster name; --kubectl-context overrides.
  local kube_ctx; kube_ctx=$(state_get KUBECTL_CONTEXT "$cluster_name")
  aws eks update-kubeconfig --region "$region" --name "$cluster_name" \
    --alias "$kube_ctx" >/dev/null
  if [[ "$(state_get SKIP_KUBECONFIG_UPDATE N)" == "Y" ]]; then
    log_info "kubeconfig entry created (alias=$kube_ctx); active context unchanged"
  else
    kubectl config use-context "$kube_ctx" >/dev/null 2>&1 || true
  fi
  state_set EKS_CLUSTER "$cluster_name"
  state_set KUBECTL_CONTEXT "$kube_ctx"
  state_save
  export KUBE_CONTEXT="$kube_ctx"

  # Define context-bound helm/kubectl arrays so every later call routes
  # through the operator-selected context. Only one definition site.
  HELM=(helm --kube-context "$kube_ctx")
  KUBECTL=(kubectl --context "$kube_ctx")
}

helm_install_or_upgrade() {
  local stack_name; stack_name=$(state_get CFN_STACK_NAME)
  local region;     region=$(state_get AWS_REGION)
  local ma_ver;     ma_ver=$(state_get MA_VERSION)

  # Kubeconfig may already be set up by manual_path (so build_images can
  # run before helm). If not (test or fast-path), do it now.
  if [[ -z "${HELM[*]+x}" || ${#HELM[@]} -le 1 ]]; then
    helm_kubeconfig_setup
  fi

  # Re-read CFN outputs here because we still need the SNAPSHOT_ROLE etc.
  # for the helm --set flags. cfn_outputs is cached in shell-state-free
  # form; the second call costs one extra describe-stacks.
  local outputs cluster_name registry_from_cfn
  outputs=$(cfn_outputs "$stack_name" "$region")
  cluster_name=$(_cfn_pick "$outputs" MIGRATIONS_EKS_CLUSTER_NAME EKSClusterName)
  registry_from_cfn=$(_cfn_pick "$outputs" MIGRATIONS_ECR_REGISTRY ECRRegistry)

  local kube_ctx; kube_ctx=$(state_get KUBECTL_CONTEXT "$cluster_name")

  # Chart resolution. --ma-chart-dir lets the operator point at a local
  # chart directory (in-repo, customized fork, etc.) and skip the
  # release fetch entirely.
  local chart chart_dir
  chart_dir=$(state_get MA_CHART_DIR "")
  if [[ -n "$chart_dir" ]]; then
    [[ -d "$chart_dir" ]] || die "--ma-chart-dir not found or not a directory: $chart_dir"
    [[ -f "$chart_dir/Chart.yaml" ]] \
      || die "--ma-chart-dir is not a helm chart (no Chart.yaml): $chart_dir"
    ui_step "Using local helm chart: $chart_dir"
    chart="$chart_dir"
  else
    ui_step "Downloading helm chart (MA v$ma_ver)"
    chart=$(artifacts_fetch "${HELM_CHART_NAME}-${ma_ver}.tgz" "$ma_ver")
  fi

  # Extract the chart's bundled valuesEks.yaml (and base values.yaml). The
  # chart's default values.yaml targets LocalStack — running it on EKS
  # without valuesEks.yaml gives wrong defaults; we extract both.
  local chart_extract; chart_extract=$(_helm_extract_chart_values "$chart")

  # Prefer the registry the operator pushed mirrored images to (CRANE_REGISTRY);
  # otherwise fall back to the registry CFN provisioned, OR an empty value
  # which means we'll use the public images path.
  local registry; registry=$(state_get CRANE_REGISTRY "$registry_from_cfn")
  local stage;    stage=$(state_get STAGE_NAME "default")
  local mirror_chosen; mirror_chosen=$(state_get MIRROR_IMAGES Y)

  # The helm release name AND k8s namespace are pinned to "ma" — the
  # chart's templates encode that name in several places (rendered
  # resource namespaces, the post-install argo-templates Job's
  # NAMESPACE env). The operator's chosen `stage` only affects CFN
  # stack naming, ECR repo naming, and our state directory.
  local release="$HELM_RELEASE_NAME"
  local HELM_NS="$HELM_NAMESPACE"

  ui_step "Installing/upgrading helm release: $release (stage=$stage, namespace=$HELM_NS)"

  # Idempotent namespace ensure — quiet on success, loud on failure.
  helm_ensure_namespace "$HELM_NS"

  # Recover from a stuck/failed prior release before invoking helm.
  helm_recover_if_stuck "$release" "$HELM_NS" || return $?

  local values_file="$STAGE_DIR/helm-values.yaml"
  _write_helm_values "$values_file"

  # Build the image-override --set flags. We need this because the chart's
  # default values use bare repos like `migrations/migration_console` that
  # resolve to docker.io (which doesn't host these images). Two paths:
  #   * MIRROR_IMAGES=Y → point images.* at the mirrored ECR (with the
  #                       per-image tag suffix used by the chart)
  #   * MIRROR_IMAGES=N → point images.* at public.ecr.aws/opensearchproject
  #
  # When --build pushed images (BUILD_IMAGE_TAG is set), the per-image tag
  # suffix is the build's --image-tag, not the MA release version.
  local image_flags=()
  local mirror_tag; mirror_tag=$(state_get BUILD_IMAGE_TAG "$ma_ver")
  if [[ "$mirror_chosen" == Y && -n "$registry" ]]; then
    _helm_build_mirrored_image_flags "$registry" "$mirror_tag" image_flags
    log_info "helm: using mirrored images at $registry (tag=$mirror_tag)"
  else
    _helm_build_public_image_flags "$ma_ver" image_flags
    log_info "helm: using public.ecr.aws/opensearchproject images @ $ma_ver"
  fi

  local region; region=$(state_get AWS_REGION)
  local account; account=$(state_get AWS_ACCOUNT)
  # CFN-derived values: snapshot role for S3 IRSA. Pull from outputs we
  # already loaded above (and tolerate older templates that don't export
  # SNAPSHOT_ROLE — chart defaults handle that path).
  local snapshot_role
  snapshot_role=$(_cfn_pick "$outputs" SNAPSHOT_ROLE SnapshotRole)
  local snapshot_args=()
  if [[ -n "$snapshot_role" ]]; then
    snapshot_args+=(--set "defaultBucketConfiguration.snapshotRoleArn=$snapshot_role")
  fi

  # Operator-supplied --helm-values FILE. Appended LAST so it can override
  # everything (the chart's values.yaml + valuesEks.yaml + ours).
  local extra_values_file; extra_values_file=$(state_get HELM_EXTRA_VALUES_FILE "")
  local extra_values_args=()
  if [[ -n "$extra_values_file" ]]; then
    [[ -f "$extra_values_file" ]] || die "--helm-values file not found: $extra_values_file"
    extra_values_args+=(--values "$extra_values_file")
  fi

  # NodePool flags.
  #
  # The chart's `cluster.useCustomKarpenterNodePool` toggles whether to
  # deploy the custom Karpenter NodePool 'general-work-pool' (defaults
  # to true under valuesEks.yaml, which we always pass through -f).
  # `--use-general-node-pool` flips it OFF so workloads schedule on the
  # EKS Auto Mode general-purpose pool.
  #
  # `--disable-general-purpose-pool` is a different, rarer toggle: it
  # asks AWS to drop "general-purpose" from the EKS Auto Mode compute
  # config entirely (post-install via update-cluster-config). Wired
  # below in _helm_apply_disable_general_purpose_pool.
  local nodepool_args=()
  if [[ "$(state_get USE_GENERAL_NODE_POOL N)" == "Y" ]]; then
    nodepool_args+=(--set "cluster.useCustomKarpenterNodePool=false")
  fi

  # TLS flags from --tls-mode / --pca-arn. See _helm_tls_flags.
  local tls_args=()
  _helm_tls_flags tls_args || return 1

  # Spawn parallel watchers so the operator sees movement while
  # `helm --wait` blocks. Tracked TWO ways for each:
  #   1. on_signal_track_pid → SIGINT trap kills them
  #   2. each polls kill -0 $parent_pid and self-exits if we're gone
  #      (safety net for the backgrounded-subshell-shares-pgroup case)
  local our_pid=$$
  helm_watch_pods "$HELM_NS" "$our_pid" &
  local watch_pid=$!
  on_signal_track_pid "$watch_pid"

  # The chart's pre-install hook runs all the real installs inside a
  # `<release>-helm-installer` Job. The outer helm command is silent for
  # the entire duration of that Job (5-15 min). Stream the installer
  # pod's logs in parallel.
  helm_watch_installer_logs "$release" "$HELM_NS" "$our_pid" &
  local installer_pid=$!
  on_signal_track_pid "$installer_pid"

  ui_dim "  helm output streamed below; full log: $LOG_FILE"
  ui_dim "  image source: $([[ "$mirror_chosen" == Y && -n "$registry" ]] && echo "mirrored ($registry)" || echo "public.ecr.aws/opensearchproject")"
  # IMPORTANT: `log_stream` returns the wrapped command's exit code. Without
  # `set +e` here, a non-zero helm exit aborts this function before we can
  # read $? — meaning helm_dump_diagnostics never runs and the operator
  # gets nothing but the bare "Error: …" line. Don't remove the set +e.
  local helm_rc=0
  set +e
  log_stream "helm" "${HELM[@]}" upgrade --install "$release" "$chart" \
    --namespace "$HELM_NS" \
    --values "$chart_extract/values.yaml" \
    --values "$chart_extract/valuesEks.yaml" \
    --values "$values_file" \
    "${extra_values_args[@]+"${extra_values_args[@]}"}" \
    --set "stageName=$stage" \
    --set "aws.region=$region" \
    --set "aws.account=$account" \
    "${snapshot_args[@]+"${snapshot_args[@]}"}" \
    "${nodepool_args[@]+"${nodepool_args[@]}"}" \
    "${tls_args[@]+"${tls_args[@]}"}" \
    "${image_flags[@]}" \
    --timeout 25m \
    --wait
  helm_rc=$?
  set -e

  # Stop the watchers before we move on.
  kill "$watch_pid" 2>/dev/null || true
  wait "$watch_pid" 2>/dev/null || true
  on_signal_untrack_pid "$watch_pid"
  kill "$installer_pid" 2>/dev/null || true
  wait "$installer_pid" 2>/dev/null || true
  on_signal_untrack_pid "$installer_pid"

  if [[ $helm_rc -ne 0 ]]; then
    helm_dump_diagnostics "$release" "$HELM_NS"
    ui_err "helm install/upgrade failed (rc=$helm_rc); diagnostics in $LOG_FILE"
    ui_dim "  retry hint: rerun migration-assistant; recovery will clean up stuck releases AND orphan helm-hook Jobs"
    return $helm_rc
  fi

  ui_step "Waiting for migration-console-0 to become Ready"
  # Same set +e guard for the readiness wait.
  local wait_rc=0
  set +e
  log_stream "kubectl-wait" "${KUBECTL[@]}" wait --namespace "$HELM_NS" \
    --for=condition=ready pod/migration-console-0 \
    --timeout=10m
  wait_rc=$?
  set -e
  if [[ $wait_rc -ne 0 ]]; then
    helm_dump_diagnostics "$release" "$HELM_NS"
    ui_err "migration-console-0 did not become Ready (rc=$wait_rc); diagnostics in $LOG_FILE"
    return $wait_rc
  fi

  ui_ok "migration-console-0 is Ready"
  state_set HELM_RELEASE "$release"
  state_set last_step "helm_done"
  state_save

  # Post-install: --disable-general-purpose-pool removes "general-purpose"
  # from EKS Auto Mode compute config. Done AFTER helm install so the
  # workloads have already been scheduled (otherwise the pre-install
  # Job would have nowhere to land).
  if [[ "$(state_get DISABLE_GENERAL_NODE_POOL N)" == "Y" ]]; then
    _helm_apply_disable_general_purpose_pool "$cluster_name" "$region" || return 1
  fi
}

# helm_ensure_namespace <ns>
#
# Idempotent: if the namespace exists in Active phase, nothing is
# printed; if it doesn't, we create it.
#
# Handles the race after `helm uninstall`: kubernetes drops the
# namespace into `Terminating` and finalizers can take 30-90 seconds
# to drain. Re-creating during that window 403s with "unable to create
# new content in namespace X because it is being terminated". Wait for
# the namespace to disappear or come back Active before continuing.
helm_ensure_namespace() {
  local ns="$1"
  _helm_wait_namespace_settled "$ns" || die "namespace $ns did not settle; see $LOG_FILE"

  if "${KUBECTL[@]}" get namespace "$ns" >/dev/null 2>&1; then
    log_info "namespace $ns already exists; skipping create"
    return 0
  fi
  ui_dim "  creating namespace: $ns"
  if ! "${KUBECTL[@]}" create namespace "$ns" >>"$LOG_FILE" 2>&1; then
    die "could not create namespace $ns; see $LOG_FILE"
  fi
  log_info "namespace $ns created"
}

# _helm_wait_namespace_settled <ns> [<timeout_s>]
#
# Wait until the namespace is either fully gone OR back in Active
# phase. While it sits in Terminating, helm + kubectl create both
# 403. Default timeout: 180s.
_helm_wait_namespace_settled() {
  local ns="$1" timeout="${2:-${NAMESPACE_SETTLE_TIMEOUT_S:-180}}"
  local started; started=$(date +%s)
  local printed_warning=0
  while :; do
    local phase
    phase=$("${KUBECTL[@]}" get namespace "$ns" -o jsonpath='{.status.phase}' 2>/dev/null) || phase=""
    case "$phase" in
      "")
        # Namespace doesn't exist (helm uninstall + finalizers
        # finished, or never existed).
        return 0
        ;;
      Active)
        return 0
        ;;
      Terminating)
        if (( ! printed_warning )); then
          ui_dim "  namespace $ns is Terminating; waiting up to ${timeout}s for finalizers"
          printed_warning=1
        fi
        ;;
    esac
    local now; now=$(date +%s)
    if (( now - started >= timeout )); then
      ui_err "namespace $ns still in '$phase' after ${timeout}s"
      ui_dim "  hint: kubectl get namespace $ns -o yaml  (look for stuck finalizers)"
      return 1
    fi
    sleep 5
  done
}

# helm_recover_if_stuck <release> <namespace>
#
# Detects releases stuck in pending-* or failed states AND orphaned helm-
# hook Jobs from a previous run. Offers the operator a recovery path.
#
#   pending-install       → uninstall (the install never finished)
#   pending-upgrade       → rollback to the last good revision (or uninstall)
#   pending-rollback      → uninstall (rollback itself got stuck)
#   failed                → rollback or uninstall
#   uninstalling          → wait briefly, then uninstall --no-hooks
#
# Independent of helm-release state, we ALSO clean up orphan helm-hook
# Jobs (default-helm-installer, default-helm-uninstaller, etc.) that
# survive a failed helm uninstall and block subsequent installs with
# "resource not ready, name: …, kind: Job, status: InProgress".
#
# Returns 0 on success (clean to proceed) or non-zero if the operator
# declined recovery and the release is still stuck.
helm_recover_if_stuck() {
  local release="$1" ns="$2"

  # Always check for orphan hook Jobs — they can be stuck even when the
  # helm release itself is "absent" (because helm uninstall failed in a
  # way that left the pre-install Job behind).
  helm_recover_orphan_jobs "$release" "$ns"

  local status
  status=$(_helm_release_status "$release" "$ns")
  case "$status" in
    ''|deployed|superseded)
      # Healthy or absent — nothing more to recover.
      return 0
      ;;
    pending-install)
      ui_warn "previous helm install for '$release' never finished (status=$status)"
      if ui_confirm "Uninstall the stuck release and start fresh?" "Y"; then
        _helm_uninstall_quiet "$release" "$ns"
      else
        return 1
      fi
      ;;
    pending-upgrade|failed)
      ui_warn "previous helm operation for '$release' is stuck (status=$status)"
      _helm_explain_failure "$release" "$ns"
      ui_info "Recovery options:"
      printf '    %s[1]%s rollback to the last successful revision\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
      printf '    %s[2]%s uninstall the release entirely (then helm will reinstall)\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
      printf '    %s[3]%s abort and let me investigate manually\n' "$__UI_C_BOLD" "$__UI_C_RESET" >&2
      printf '    %s[4]%s reconcile in place — clear the stuck status and let helm upgrade run again\n' \
        "$__UI_C_BOLD" "$__UI_C_RESET" >&2
      local pick
      ui_prompt "Choose" "1" pick
      case "$pick" in
        1)
          ui_dim "  rolling back ${release}…"
          if log_stream "helm-rollback" "${HELM[@]}" rollback "$release" --namespace "$ns" --wait --timeout 5m; then
            ui_ok "rollback complete"
          else
            ui_warn "rollback failed; falling back to uninstall"
            _helm_uninstall_quiet "$release" "$ns"
          fi
          ;;
        2)
          _helm_uninstall_quiet "$release" "$ns"
          ;;
        4)
          # Clear the stuck pending-upgrade/failed status so the
          # upcoming `helm upgrade --install` reconciles in place
          # rather than fighting with the prior revision's lock.
          # Implementation: mark the latest revision as superseded
          # so helm picks the prior deployed one as the head, then
          # let the upgrade flow create a new revision. If there's
          # no prior deployed revision, fall through to a rollback.
          _helm_clear_stuck_revision "$release" "$ns" || {
            ui_warn "in-place reconcile not possible; falling back to rollback"
            if log_stream "helm-rollback" "${HELM[@]}" rollback "$release" --namespace "$ns" --wait --timeout 5m; then
              ui_ok "rollback complete"
            else
              ui_warn "rollback failed; falling back to uninstall"
              _helm_uninstall_quiet "$release" "$ns"
            fi
          }
          ;;
        *)
          ui_warn "aborting; release $release is still in '$status' state"
          return 1
          ;;
      esac
      ;;
    pending-rollback)
      ui_warn "rollback for '$release' got stuck; uninstalling"
      _helm_uninstall_quiet "$release" "$ns"
      ;;
    uninstalling)
      ui_dim "  release $release is mid-uninstall; waiting up to 60s for it to finish…"
      local i=0
      while (( i < 12 )); do
        sleep 5
        status=$(_helm_release_status "$release" "$ns")
        [[ -z "$status" ]] && return 0
        i=$(( i + 1 ))
      done
      ui_warn "release still uninstalling after 60s; forcing"
      _helm_uninstall_quiet "$release" "$ns"
      ;;
    *)
      log_warn "unknown helm status '$status' for $release; proceeding anyway"
      ;;
  esac
  return 0
}

# helm_recover_orphan_jobs <release> <ns>
#
# Find Kubernetes Jobs in <ns> that look like leftover helm hooks for
# <release> (or anything matching `<release>-helm-*`) and that have NOT
# completed successfully. Delete them so the next helm install / upgrade
# isn't blocked by "resource not ready, name: <release>-helm-installer,
# kind: Job, status: InProgress" — that exact failure mode chewed our
# operator's last 25 minutes.
#
# Conservative: we only delete Jobs whose name starts with the release
# name AND that have no successful completion. We never touch Pods or
# anything outside the namespace.
helm_recover_orphan_jobs() {
  local release="$1" ns="$2"
  local stuck_jobs
  stuck_jobs=$("${KUBECTL[@]}" get jobs --namespace "$ns" \
    --no-headers \
    -o custom-columns=NAME:.metadata.name,COMPLETIONS:.status.succeeded \
    2>/dev/null \
    | awk -v r="$release" '
        $1 ~ "^" r "-helm-"  &&  ($2=="" || $2=="0" || $2=="<none>") { print $1 }
      ')

  if [[ -z "$stuck_jobs" ]]; then
    return 0
  fi

  ui_warn "found orphan helm-hook Jobs in namespace $ns:"
  while IFS= read -r job; do
    printf '    %s- %s%s\n' "$__UI_C_DIM" "$job" "$__UI_C_RESET" >&2
  done <<<"$stuck_jobs"

  if ! ui_confirm "Delete these stuck Jobs so the install can proceed?" "Y"; then
    ui_warn "leaving orphan Jobs in place; helm install will likely fail"
    return 0
  fi

  while IFS= read -r job; do
    [[ -z "$job" ]] && continue
    set +e
    log_stream "kubectl-del-job-$job" \
      "${KUBECTL[@]}" delete job "$job" --namespace "$ns" --wait=true --timeout=60s
    set -e
  done <<<"$stuck_jobs"
  ui_ok "orphan Jobs cleared"
}

# _helm_explain_failure <release> <ns> — print the WHY behind a failed
# / pending-upgrade release so the operator can decide between rollback,
# uninstall, and "leave it alone" without opening a second terminal.
#
# Surfaces:
#   * helm's own DESCRIPTION line (often names the failed hook resource)
#   * the failed Job (named in the description) — its events, and the
#     events for any pods it spawned (logs are best-effort: if the pod
#     was GC'd, surface the GC events so the operator can re-run)
#   * migration-console-0's pod phase + container readiness (so a
#     "release failed but pod is Running/Ready" case is visible up front)
#
# Stderr only, dim color — informational, not an error.
_helm_explain_failure() {
  local release="$1" ns="$2"
  local desc console job
  desc=$("${HELM[@]}" status "$release" --namespace "$ns" -o json 2>/dev/null \
        | jq -r '.info.description // empty' 2>/dev/null)
  if [[ -n "$desc" ]]; then
    printf '%s  why: %s%s\n' "$__UI_C_DIM" "$desc" "$__UI_C_RESET" >&2
    # "name: <job-name>, kind: Job" is the canonical helm failure shape.
    job=$(printf '%s' "$desc" | sed -nE 's/.*name: ([A-Za-z0-9_.-]+), kind: Job.*/\1/p' | head -1)
    if [[ -n "$job" ]]; then
      _helm_dump_failed_job "$job" "$ns"
    fi
  fi
  console=$("${KUBECTL[@]}" get pod migration-console-0 --namespace "$ns" \
              -o jsonpath='{.status.phase}|{range .status.containerStatuses[*]}{.ready},{end}' \
              2>/dev/null) || console=""
  if [[ -n "$console" ]]; then
    printf '%s  migration-console-0: %s%s\n' \
      "$__UI_C_DIM" "$console" "$__UI_C_RESET" >&2
  fi
  printf '%s  full diagnostics: migration-assistant diag%s\n' \
    "$__UI_C_DIM" "$__UI_C_RESET" >&2
}

# _helm_dump_failed_job <job> <ns>
#
# When a post-install Job fails, its pod is often already deleted (by
# job-controller GC, by the chart's hook-delete-policy, or by Karpenter
# consolidation). `kubectl logs <pod>` then 404s and the operator is
# stuck. This helper prints what's still discoverable AND tells the
# operator how to capture logs by re-running the Job.
_helm_dump_failed_job() {
  local job="$1" ns="$2"
  printf '%s  failed job: %s%s\n' "$__UI_C_DIM" "$job" "$__UI_C_RESET" >&2

  # Job events (deadline / backoff / image-pull failures).
  local events
  events=$("${KUBECTL[@]}" get events --namespace "$ns" \
             --field-selector "involvedObject.kind=Job,involvedObject.name=$job" \
             --sort-by=.lastTimestamp \
             -o custom-columns='LAST:.lastTimestamp,REASON:.reason,MESSAGE:.message' \
             --no-headers 2>/dev/null | tail -8) || events=""
  if [[ -n "$events" ]]; then
    printf '%s    job events:%s\n' "$__UI_C_DIM" "$__UI_C_RESET" >&2
    printf '%s\n' "$events" | sed 's/^/      /' >&2
  fi

  # Pod logs (best-effort — pod is often GC'd).
  local pods pod
  pods=$("${KUBECTL[@]}" get pods --namespace "$ns" \
           --selector "job-name=$job" \
           -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' \
           2>/dev/null) || pods=""
  if [[ -n "$pods" ]]; then
    while IFS= read -r pod; do
      [[ -z "$pod" ]] && continue
      printf '%s    pod %s logs:%s\n' "$__UI_C_DIM" "$pod" "$__UI_C_RESET" >&2
      "${KUBECTL[@]}" logs "$pod" --namespace "$ns" --all-containers --tail=50 2>&1 \
        | sed 's/^/      /' >&2
    done <<<"$pods"
  else
    printf '%s    pod logs unavailable (pod was GC'\''d after failure)%s\n' \
      "$__UI_C_DIM" "$__UI_C_RESET" >&2
    printf '%s    re-run the job to capture logs:%s\n' "$__UI_C_DIM" "$__UI_C_RESET" >&2
    printf '%s      kubectl get job %s -n %s -o yaml > /tmp/%s.yaml%s\n' \
      "$__UI_C_DIM" "$job" "$ns" "$job" "$__UI_C_RESET" >&2
    printf '%s      kubectl delete job %s -n %s%s\n' \
      "$__UI_C_DIM" "$job" "$ns" "$__UI_C_RESET" >&2
    printf '%s      kubectl create -f /tmp/%s.yaml%s\n' \
      "$__UI_C_DIM" "$job" "$__UI_C_RESET" >&2
    printf '%s      kubectl logs -f -l job-name=%s -n %s --all-containers%s\n' \
      "$__UI_C_DIM" "$job" "$ns" "$__UI_C_RESET" >&2
  fi
}

# _helm_release_status <release> <ns> → echoes the helm status (deployed,
# pending-install, pending-upgrade, failed, uninstalling, …) or empty if
# the release does not exist. Uses jq when available (we require it for
# the model layer); falls back to a tolerant grep+sed for pure compat.
_helm_release_status() {
  local release="$1" ns="$2"
  local json
  json=$("${HELM[@]}" status "$release" --namespace "$ns" -o json 2>/dev/null) || return 0
  [[ -z "$json" ]] && return 0
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$json" | jq -r '.info.status // empty'
  else
    # Fallback parser. Looks for `"status":"<value>"` (with optional spaces)
    # specifically inside the .info object — first occurrence wins.
    printf '%s' "$json" \
      | tr -d '\n' \
      | sed -E 's/.*"info"[[:space:]]*:[[:space:]]*\{[^}]*"status"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' \
      | head -1
  fi
}

# _helm_clear_stuck_revision <release> <ns>
#
# When helm's bookkeeping says pending-upgrade or failed but the cluster
# itself is mostly healthy, clearing the stuck revision lets the next
# `helm upgrade --install` reconcile in place instead of forcing a full
# rollback or uninstall (which would delete + recreate workloads).
#
# Mechanism: helm stores each revision as a Secret named
#   sh.helm.release.v1.<release>.v<rev>
# with metadata.labels.status ∈ {deployed, superseded, pending-*, failed,
# uninstalled}. We mark the latest revision as `superseded` so helm
# picks the prior `deployed` revision as the current head; the upcoming
# upgrade will then write a new v(rev+1) and roll forward.
#
# Returns 0 on success, 1 if there's no prior deployed revision to fall
# back to (caller should rollback / uninstall instead).
_helm_clear_stuck_revision() {
  local release="$1" ns="$2"
  ui_step "Clearing stuck helm revision so upgrade can reconcile in place"

  # Latest revision number from helm itself.
  local latest_rev
  latest_rev=$("${HELM[@]}" history "$release" --namespace "$ns" --max 1 -o json 2>/dev/null \
                | jq -r '.[0].revision // empty' 2>/dev/null)
  if [[ -z "$latest_rev" ]]; then
    log_warn "helm-clear: could not read latest revision for $release"
    return 1
  fi

  # Need at least one prior `deployed` revision OR a fresh install
  # (rev=1 stuck) — for fresh installs, in-place reconcile means
  # uninstalling the failed first revision, which is functionally the
  # same as the [2] uninstall path. Recommend that instead.
  if (( latest_rev <= 1 )); then
    log_warn "helm-clear: only revision $latest_rev exists; nothing to fall back to. Use [2] uninstall."
    return 1
  fi

  local prior_rev=$(( latest_rev - 1 ))
  local prior_status
  prior_status=$("${HELM[@]}" history "$release" --namespace "$ns" -o json 2>/dev/null \
                  | jq -r --argjson r "$prior_rev" '.[] | select(.revision == $r) | .status' \
                  2>/dev/null)
  if [[ "$prior_status" != "deployed" && "$prior_status" != "superseded" ]]; then
    log_warn "helm-clear: prior revision $prior_rev has status '$prior_status' (not 'deployed'); cannot reconcile in place"
    return 1
  fi

  # Patch the stuck Secret's status label to 'superseded'.
  local secret="sh.helm.release.v1.${release}.v${latest_rev}"
  ui_dim "  marking $secret as superseded (was the stuck revision)"
  if ! "${KUBECTL[@]}" label secret "$secret" --namespace "$ns" \
       --overwrite "status=superseded" >>"$LOG_FILE" 2>&1; then
    log_warn "helm-clear: kubectl label failed; release secret may have a different name"
    return 1
  fi

  # Promote the prior deployed revision to current head if it isn't
  # already (helm reads the highest-numbered Secret with status=deployed).
  if [[ "$prior_status" == "superseded" ]]; then
    local prior_secret="sh.helm.release.v1.${release}.v${prior_rev}"
    "${KUBECTL[@]}" label secret "$prior_secret" --namespace "$ns" \
      --overwrite "status=deployed" >>"$LOG_FILE" 2>&1 || true
  fi

  ui_ok "stuck revision cleared; helm upgrade will create a new revision on top of v$prior_rev"
  return 0
}

# _helm_uninstall_quiet <release> <ns>
_helm_uninstall_quiet() {
  local release="$1" ns="$2"
  ui_dim "  uninstalling release: $release"
  if log_stream "helm-uninstall" "${HELM[@]}" uninstall "$release" --namespace "$ns" --wait --timeout 5m; then
    ui_ok "release $release uninstalled"
  else
    # Last-ditch: --no-hooks so a stuck pre/post hook doesn't block forever.
    ui_warn "uninstall blocked; retrying with --no-hooks"
    log_stream "helm-uninstall-force" "${HELM[@]}" uninstall "$release" --namespace "$ns" --no-hooks
  fi
}

# helm_dump_diagnostics <release> <ns>
#
# Called on helm failure. Captures everything the operator (or an agent
# reading the log) would normally have to gather by hand:
#   * helm status
#   * kubectl get pods (wide)
#   * kubectl describe of any pod NOT in Running/Succeeded
#   * kubectl get events (last 30, by lastTimestamp)
#   * kubectl describe of any Job that's blocking (helm hooks)
#
# Each section streams via log_stream so the operator sees a brief on-
# screen rendering AND the full content lands in $LOG_FILE.
helm_dump_diagnostics() {
  local release="$1" ns="$2"
  ui_step "Collecting diagnostics for failed helm operation"

  log_stream "diag-helm" "${HELM[@]}" status "$release" --namespace "$ns" || true
  log_stream "diag-pods" "${KUBECTL[@]}" get pods --namespace "$ns" -o wide || true

  # Per-pod describe for unhealthy pods.
  local unhealthy
  unhealthy=$("${KUBECTL[@]}" get pods --namespace "$ns" \
    --no-headers \
    -o custom-columns=NAME:.metadata.name,PHASE:.status.phase 2>/dev/null \
    | awk '$2!="Running" && $2!="Succeeded" {print $1}')
  if [[ -n "$unhealthy" ]]; then
    local pod
    while IFS= read -r pod; do
      [[ -z "$pod" ]] && continue
      log_stream "diag-pod-$pod" "${KUBECTL[@]}" describe pod "$pod" --namespace "$ns" || true
    done <<<"$unhealthy"
  fi

  # Recent events (most recent 30, ordered).
  log_stream "diag-events" \
    "${KUBECTL[@]}" get events --namespace "$ns" \
      --sort-by=.lastTimestamp 2>/dev/null \
    | tail -30 || true

  # Helm hook jobs — if any are stuck, describe them. Common culprits:
  # default-helm-installer, default-helm-uninstaller.
  local stuck_jobs
  stuck_jobs=$("${KUBECTL[@]}" get jobs --namespace "$ns" \
    --no-headers \
    -o custom-columns=NAME:.metadata.name,COMPLETIONS:.status.succeeded 2>/dev/null \
    | awk '$2=="" || $2=="0" {print $1}')
  if [[ -n "$stuck_jobs" ]]; then
    local job
    while IFS= read -r job; do
      [[ -z "$job" ]] && continue
      log_stream "diag-job-$job" "${KUBECTL[@]}" describe job "$job" --namespace "$ns" || true
    done <<<"$stuck_jobs"
  fi

  ui_ok "diagnostics written to $LOG_FILE"
}

# helm_watch_installer_logs <release> <namespace> [<parent_pid>]
#
# The chart's pre-install hook runs all sub-chart installs inside a Job
# called "<release>-helm-installer". The outer `helm upgrade --install`
# is silent for the entire duration of that Job — typically 5-15 minutes.
# This sub finds the Job's Pod (waiting up to ~2 minutes for it to come
# up), then `kubectl logs -f` follows its stdout/stderr through log_stream
# so every line of every helm-add-repo / helm-upgrade-install lands in
# the operator's terminal AND $LOG_FILE.
#
# Exits when:
#   * the parent process disappears (parent_pid kill -0 fails)
#   * the pod transitions to Succeeded or Failed
#   * we never find a pod within DETECT_TIMEOUT_S seconds (default 180)
#
# Idempotent: re-running picks up the new pod if the Job was reissued.
helm_watch_installer_logs() {
  local release="$1" ns="$2" parent_pid="${3:-${PPID:-}}"
  local pod_name="${release}-helm-installer"
  local detect_timeout="${INSTALLER_LOG_DETECT_TIMEOUT_S:-180}"
  local poll="${INSTALLER_LOG_POLL_S:-2}"

  trap '' INT
  trap 'exit 0' TERM HUP

  # Helper: bail if our parent went away.
  _alive() {
    [[ -n "$parent_pid" ]] && ! kill -0 "$parent_pid" 2>/dev/null && return 1
    return 0
  }

  # Phase 1: wait for the Job's Pod to exist + be Running. The pod name
  # for a Job is "<job-name>-<5-char-hash>", so we resolve by label.
  # Track time by clock so we don't have to do float arithmetic on
  # sub-second poll intervals.
  local started_s pod="" now_s pending_pod=""
  local pending_warn_at="${INSTALLER_LOG_PENDING_WARN_S:-30}"
  local warned_pending=0
  started_s=$(date +%s)
  _helm_inst_log "watching for pod of Job/$pod_name in namespace $ns"
  while :; do
    _alive || exit 0
    # Look for any pod backing the Job, regardless of phase. This lets
    # us spot the pod even when it's stuck in Pending / ImagePullBackOff
    # so we can surface diagnostics instead of timing out silently.
    local snapshot
    snapshot=$("${KUBECTL[@]}" get pods --namespace "$ns" \
            --selector "job-name=$pod_name" \
            --no-headers \
            -o custom-columns=NAME:.metadata.name,PHASE:.status.phase \
            2>/dev/null) || snapshot=""

    pod=$(printf '%s\n' "$snapshot" \
          | awk '$2=="Running" || $2=="Succeeded" || $2=="Failed" {print $1; exit}')
    [[ -n "$pod" ]] && break

    # Pod exists but is Pending — surface why ONCE after a threshold,
    # then keep waiting up to detect_timeout. The chart's pre-install
    # Job often legitimately spends 30-60s in Pending while node-pool
    # scaling happens, so we don't dump diagnostics immediately.
    pending_pod=$(printf '%s\n' "$snapshot" \
            | awk '$2=="Pending" {print $1; exit}')
    now_s=$(date +%s)
    if [[ -n "$pending_pod" ]] \
        && (( warned_pending == 0 )) \
        && (( now_s - started_s >= pending_warn_at )); then
      _helm_inst_log "pod $pending_pod has been Pending for ${pending_warn_at}s; dumping diagnostics"
      _helm_inst_dump_pending_pod "$pending_pod" "$ns"
      warned_pending=1
    fi

    sleep "$poll"
    now_s=$(date +%s)
    if (( now_s - started_s >= detect_timeout )); then
      break
    fi
  done

  if [[ -z "$pod" ]]; then
    if [[ -n "$pending_pod" ]]; then
      _helm_inst_log "pod $pending_pod never left Pending within ${detect_timeout}s — final diagnostics:"
      _helm_inst_dump_pending_pod "$pending_pod" "$ns"
    else
      _helm_inst_log "no $pod_name pod appeared within ${detect_timeout}s; giving up"
    fi
    exit 0
  fi

  _helm_inst_log "tailing logs from $pod"

  # Phase 2: follow logs. log_stream tees stdout+stderr to log + terminal
  # with the "installer│" prefix. `kubectl logs -f` exits naturally when
  # the pod's containers complete; if our parent dies first, the SIGTERM
  # from the trap kills us mid-follow.
  log_stream "installer" "${KUBECTL[@]}" logs -f \
    --namespace "$ns" "$pod" \
    --all-containers=true \
    --prefix=false \
    || true

  _helm_inst_log "$pod log follow complete"

  # Phase 3: dump the installation-notes ConfigMap. The chart writes this
  # at the end of the installer Job — it contains the per-sub-chart
  # success NOTES that helm normally prints to stdout (which were buried
  # inside the Job pod). Once the Job completes, the pod is GC'd and
  # `kubectl logs` 404s — but the ConfigMap survives. Poll briefly for
  # it, then stream it line-by-line.
  _helm_dump_install_notes "$release" "$ns" "$parent_pid"
}

# _helm_dump_install_notes <release> <ns> [<parent_pid>]
#
# Polls for the <release>-installation-notes ConfigMap (created by the
# chart's pre-install Job at the end of its run) and streams its
# all-notes.txt key into the operator log. Bounded by NOTES_TIMEOUT_S.
_helm_dump_install_notes() {
  local release="$1" ns="$2" parent_pid="${3:-}"
  local cm="${release}-installation-notes"
  local timeout_s="${INSTALLER_NOTES_TIMEOUT_S:-60}"
  local poll_s="${INSTALLER_NOTES_POLL_S:-2}"

  local started_s now_s
  started_s=$(date +%s)
  while :; do
    if [[ -n "$parent_pid" ]] && ! kill -0 "$parent_pid" 2>/dev/null; then
      return 0
    fi
    if "${KUBECTL[@]}" get configmap "$cm" --namespace "$ns" >/dev/null 2>&1; then
      break
    fi
    sleep "$poll_s"
    now_s=$(date +%s)
    if (( now_s - started_s >= timeout_s )); then
      _helm_inst_log "no ConfigMap/$cm appeared within ${timeout_s}s; skipping notes dump"
      return 0
    fi
  done

  _helm_inst_log "streaming installation notes from ConfigMap/$cm"
  # Pull the all-notes.txt key. Falls back to dumping the entire CM if
  # that key is missing for any reason.
  local notes
  notes=$("${KUBECTL[@]}" get configmap "$cm" --namespace "$ns" \
    -o jsonpath='{.data.all-notes\.txt}' 2>/dev/null) || notes=""
  if [[ -z "$notes" ]]; then
    _helm_inst_log "ConfigMap/$cm has no all-notes.txt key; falling back to describe"
    log_stream "install-notes" "${KUBECTL[@]}" describe configmap "$cm" --namespace "$ns" || true
    return 0
  fi

  # Stream each line through the same install-notes channel so the file
  # log gets per-line timestamps and the operator gets per-line stderr
  # output with the "install-notes│" prefix.
  local line
  while IFS= read -r line || [[ -n "$line" ]]; do
    local ts; ts=$(date '+%Y-%m-%dT%H:%M:%S%z')
    printf '[%s] STREAM[install-notes] %s\n' "$ts" "$line" >>"$LOG_FILE"
    printf '%s  install-notes│%s %s\n' "$__UI_C_DIM" "$__UI_C_RESET" "$line" >&2
  done <<<"$notes"
}

# _helm_inst_log — like _helm_pods_log but with the "installer" channel
# label, so the operator can visually distinguish helm-output from
# helm-installer-pod-output from pod-summary lines.
_helm_inst_log() {
  local msg="$1"
  local ts; ts=$(date '+%Y-%m-%dT%H:%M:%S%z')
  printf '[%s] STREAM[installer] %s\n' "$ts" "$msg" >>"$LOG_FILE"
  printf '%s  installer│%s %s\n' "$__UI_C_DIM" "$__UI_C_RESET" "$msg" >&2
}

# _helm_inst_dump_pending_pod <pod> <namespace>
#
# When a Job's Pod has been Pending past the warn threshold, surface
# the actual reason. Two streams of evidence:
#   * The pod's container statuses (waiting reason — ImagePullBackOff,
#     CrashLoopBackOff, ErrImageNeverPull, …)
#   * The pod's recent Events (e.g. "Failed to pull image …").
# Both go through log_stream so the operator sees them live AND they
# land in the main log for post-mortem.
_helm_inst_dump_pending_pod() {
  local pod="$1" ns="$2"
  _helm_inst_log "container statuses for $pod:"
  log_stream "diag-pod-status" \
    "${KUBECTL[@]}" get pod "$pod" --namespace "$ns" \
      -o 'custom-columns=NAME:.metadata.name,IMAGE:.spec.containers[*].image,STATUS:.status.containerStatuses[*].state,REASON:.status.containerStatuses[*].state.waiting.reason,MESSAGE:.status.containerStatuses[*].state.waiting.message' \
      || true
  _helm_inst_log "recent events for $pod:"
  log_stream "diag-pod-events" \
    "${KUBECTL[@]}" get events --namespace "$ns" \
      --field-selector "involvedObject.name=$pod" \
      --sort-by=.lastTimestamp \
      || true
}

# helm_watch_pods <namespace> [<parent_pid>]
#
# Polls `kubectl get pods` every WATCH_INTERVAL seconds; emits one summary
# line per cycle (counts + the names of any pod NOT in Running/Completed
# state). Lines go via log_stream's "pods" prefix so they tee to file +
# stderr the same way helm/kubectl do.
#
# Self-termination: if a parent_pid is supplied, the watcher checks each
# cycle whether the parent is still alive (kill -0). If not, it exits
# immediately. This is the SAFETY NET that prevents the watcher from
# outliving the operator's Ctrl-C — the bare TERM/KILL trap can't be
# trusted because:
#   * On macOS without `set -m`, backgrounded children share the parent's
#     process group; `kill -- -$pid` doesn't reach them.
#   * If the watcher is mid-`sleep`, SIGTERM is delivered after the sleep
#     returns; if the operator's terminal closes before that, the watcher
#     becomes a zombie writing to a closed tty.
helm_watch_pods() {
  local ns="$1"
  local parent_pid="${2:-${PPID:-}}"
  local interval="${WATCH_INTERVAL:-10}"
  local prev_summary=""

  # Subshell: ignore SIGINT — the parent's SIGINT handler kills us
  # explicitly. SIGTERM still terminates us cleanly.
  trap '' INT
  # On EXIT, restore an empty trap so we don't print a stray line during
  # teardown.
  trap 'exit 0' TERM HUP

  while :; do
    # Belt-and-braces: if the parent went away (Ctrl-C took it down,
    # SIGTERM, panic, terminal close), exit immediately. This is the
    # one-line guard that prevents `pods│ waiting…` from leaking past
    # the prompt return.
    if [[ -n "$parent_pid" ]] && ! kill -0 "$parent_pid" 2>/dev/null; then
      exit 0
    fi
    sleep "$interval"
    if [[ -n "$parent_pid" ]] && ! kill -0 "$parent_pid" 2>/dev/null; then
      exit 0
    fi
    local snapshot
    snapshot=$("${KUBECTL[@]}" get pods -n "$ns" \
      --no-headers \
      -o custom-columns=NAME:.metadata.name,STATUS:.status.phase,READY:.status.containerStatuses[*].ready \
      2>/dev/null) || snapshot=""

    if [[ -z "$snapshot" ]]; then
      _helm_pods_log "waiting for pods in namespace $ns to appear…"
      continue
    fi

    # Count fields with awk so we don't depend on `grep -c` (which returns
    # rc=1 on zero matches and would trip `set -e + pipefail`).
    local total running pending failed pending_names failed_names not_ready
    total=$(printf   '%s\n' "$snapshot" | awk 'NF>0 {n++} END {print n+0}')
    running=$(printf '%s\n' "$snapshot" | awk '$2=="Running" {n++} END {print n+0}')
    pending=$(printf '%s\n' "$snapshot" | awk '$2=="Pending" {n++} END {print n+0}')
    failed=$(printf  '%s\n' "$snapshot" | awk '$2=="Failed"  {n++} END {print n+0}')
    # Names per non-Ready category — when the operator sees `pending=3`
    # they need to know WHICH 3 pods are stuck so they can investigate.
    pending_names=$(printf '%s\n' "$snapshot" \
      | awk '$2=="Pending" {print $1}' \
      | tr '\n' ',' | sed 's/,$//')
    failed_names=$(printf '%s\n' "$snapshot" \
      | awk '$2=="Failed" {print $1}' \
      | tr '\n' ',' | sed 's/,$//')
    # Pods that are Running but with at least one unready container.
    not_ready=$(printf '%s\n' "$snapshot" \
      | awk '$2=="Running" && $3 ~ /false/ {print $1}' \
      | tr '\n' ',' | sed 's/,$//')

    local summary
    summary=$(printf 'pods total=%d running=%d pending=%d failed=%d' \
      "$total" "$running" "$pending" "$failed")
    [[ -n "$pending_names" ]] && summary="$summary pending=[$pending_names]"
    [[ -n "$failed_names"  ]] && summary="$summary failed=[$failed_names]"
    [[ -n "$not_ready"     ]] && summary="$summary not_ready=[$not_ready]"

    # Only emit when the summary changed — keeps the operator's terminal
    # quiet during steady states, loud during transitions.
    if [[ "$summary" != "$prev_summary" ]]; then
      _helm_pods_log "$summary"
      prev_summary="$summary"
    fi
  done
}

# _helm_pods_log — write a single line to log file + stderr, with the
# same "<bucket>│ msg" decoration log_stream uses, so the operator sees
# helm and pod lines in one consistent stream.
_helm_pods_log() {
  local msg="$1"
  local ts; ts=$(date '+%Y-%m-%dT%H:%M:%S%z')
  printf '[%s] STREAM[pods] %s\n' "$ts" "$msg" >>"$LOG_FILE"
  printf '%s  pods│%s %s\n' "$__UI_C_DIM" "$__UI_C_RESET" "$msg" >&2
}

_write_helm_values() {
  local out="$1"
  # The chart's default values.yaml + valuesEks.yaml + the --set flags
  # we pass on the command line cover everything we need today. This file
  # exists as a hook so future per-stage overrides can land here without
  # changing the helm invocation.
  cat >"$out" <<EOF
# Generated by migration-assistant — edit at your own risk; overwritten on rerun.
# Per-stage overrides go here. Chart's values.yaml + valuesEks.yaml are
# applied first; this file applies last.
EOF
}

# _helm_extract_chart_values <chart-tgz> → echoes the directory containing
# values.yaml + valuesEks.yaml extracted from the chart tarball.
#
# Helm cannot reference value files inside an archive, so we extract them
# alongside the install. Idempotent (re-extract is fine).
_helm_extract_chart_values() {
  local chart="$1"
  local dir="$STAGE_DIR/chart-values"
  mkdir -p "$dir"
  if [[ -d "$chart" ]]; then
    # --ma-chart-dir path: the values files are already on disk.
    [[ -f "$chart/values.yaml" ]]    || die "chart dir missing values.yaml: $chart"
    [[ -f "$chart/valuesEks.yaml" ]] || die "chart dir missing valuesEks.yaml: $chart"
    cp -f "$chart/values.yaml"    "$dir/values.yaml"
    cp -f "$chart/valuesEks.yaml" "$dir/valuesEks.yaml"
  else
    # The tarball top-level is `migration-assistant/`. We strip it to land
    # the files at the bottom of $dir.
    tar -xzf "$chart" -C "$dir" --strip-components=1 \
      migration-assistant/values.yaml \
      migration-assistant/valuesEks.yaml \
      2>/dev/null \
      || die "could not extract values from chart: $chart"
  fi
  printf '%s\n' "$dir"
}

# _helm_build_public_image_flags <ma_version> <out_array_name>
#
# Populates <out_array_name> with the --set flags that point all five
# migration images at public.ecr.aws/opensearchproject/opensearch-migrations-*
# at <ma_version>. This is the path used when MIRROR_IMAGES=N OR when
# we couldn't determine a private registry.
_helm_build_public_image_flags() {
  local ver="$1" out_name="$2"
  local pub='public.ecr.aws/opensearchproject'
  # The five images the chart ships, mapping internal name → public suffix.
    local pairs=(
    "captureProxy|opensearch-migrations-traffic-capture-proxy"
    "trafficReplayer|opensearch-migrations-traffic-replayer"
    "reindexFromSnapshot|opensearch-migrations-reindex-from-snapshot"
    "migrationConsole|opensearch-migrations-console"
    "installer|opensearch-migrations-console"
  )
  # Use a distinct local name (__hf) so the eval back-assignment can target
  # the caller's array even when they named theirs `flags`.
  local p name suffix __hf=()
  for p in "${pairs[@]}"; do
    name=${p%%|*}
    suffix=${p##*|}
    __hf+=(--set "images.${name}.repository=${pub}/${suffix}")
    __hf+=(--set "images.${name}.tag=${ver}")
  done
  eval "$out_name=(\"\${__hf[@]}\")"
}

# _helm_build_mirrored_image_flags <registry> <ma_version> <out_array_name>
#
# Same idea, but for the operator's private ECR registry that crane copied
# into. Mirrored layout uses tags like "migrations_capture_proxy_<ver>" within a single repo.
# Match that exactly.
_helm_build_mirrored_image_flags() {
  local registry="$1" ver="$2" out_name="$3"
  local pairs=(
    "captureProxy|migrations_capture_proxy"
    "trafficReplayer|migrations_traffic_replayer"
    "reindexFromSnapshot|migrations_reindex_from_snapshot"
    "migrationConsole|migrations_migration_console"
    "installer|migrations_migration_console"
  )
  local p name tag_prefix __hf=()
  for p in "${pairs[@]}"; do
    name=${p%%|*}
    tag_prefix=${p##*|}
    __hf+=(--set "images.${name}.repository=${registry}")
    __hf+=(--set "images.${name}.tag=${tag_prefix}_${ver}")
  done
  eval "$out_name=(\"\${__hf[@]}\")"
}

# _helm_tls_flags <out_array_name>
#
# Populate <out_array_name> with the helm `--set` arguments derived from
# state.env's TLS_MODE and PCA_ARN.
#
# Modes:
#   none, self-signed  → no extra flags (chart defaults handle these)
#   pca-existing       → enables aws-privateca-issuer + sets awsPrivateCA.arn
#                        and awsPrivateCA.region. Requires --pca-arn.
#   pca-create         → enables aws-privateca-issuer + ack-acmpca-controller,
#                        sets awsPrivateCA.create=true so the chart provisions
#                        a PCA at install time.
#
# Returns non-zero (and dies via the caller) on validation failure
# (unknown mode, or pca-existing without --pca-arn).
_helm_tls_flags() {
  local out_name="$1"
  local mode; mode=$(state_get TLS_MODE "")
  local pca;  pca=$(state_get PCA_ARN "")
  local region; region=$(state_get AWS_REGION)
  local __tf=()
  case "$mode" in
    "" | none | self-signed)
      ;;
    pca-existing)
      [[ -z "$pca" ]] && die "--tls-mode pca-existing requires --pca-arn"
      __tf+=(--set "conditionalPackageInstalls.aws-privateca-issuer=true")
      __tf+=(--set "awsPrivateCA.arn=${pca}")
      __tf+=(--set "awsPrivateCA.region=${region}")
      ;;
    pca-create)
      __tf+=(--set "conditionalPackageInstalls.aws-privateca-issuer=true")
      __tf+=(--set "conditionalPackageInstalls.ack-acmpca-controller=true")
      __tf+=(--set "awsPrivateCA.create=true")
      __tf+=(--set "awsPrivateCA.region=${region}")
      ;;
    *)
      die "unknown --tls-mode: $mode (expected: none, self-signed, pca-existing, pca-create)"
      ;;
  esac
  eval "$out_name=(\"\${__tf[@]+\"\${__tf[@]}\"}\")"
  return 0
}

# _helm_apply_disable_general_purpose_pool <cluster> <region>
#
# Post-install hook: ask EKS Auto Mode to drop "general-purpose" from
# the cluster's compute config. Done after helm install so the
# chart's pre-install Job has somewhere to schedule.
#
# We need the cluster's existing nodeRoleArn to feed back into
# update-cluster-config; aws cli requires the whole compute-config blob.
_helm_apply_disable_general_purpose_pool() {
  local cluster="$1" region="$2"
  ui_step "Disabling EKS Auto Mode general-purpose pool (cluster=$cluster)"
  local node_role_arn
  node_role_arn=$(aws eks describe-cluster --name "$cluster" --region "$region" \
    --query 'cluster.computeConfig.nodeRoleArn' --output text 2>/dev/null) \
    || { ui_err "could not describe-cluster $cluster"; return 1; }
  if [[ -z "$node_role_arn" || "$node_role_arn" == "None" ]]; then
    ui_warn "cluster $cluster has no Auto Mode compute config; nothing to disable"
    return 0
  fi
  set +e
  log_stream "eks-update" aws eks update-cluster-config \
    --name "$cluster" --region "$region" \
    --compute-config '{"enabled":true,"nodePools":["system"],"nodeRoleArn":"'"$node_role_arn"'"}' \
    --kubernetes-network-config '{"elasticLoadBalancing":{"enabled":true}}' \
    --storage-config '{"blockStorage":{"enabled":true}}'
  local rc=$?
  set -e
  if [[ $rc -ne 0 ]]; then
    ui_err "aws eks update-cluster-config failed; see $LOG_FILE"
    return $rc
  fi
  ui_ok "general-purpose pool disabled on $cluster"
  return 0
}
