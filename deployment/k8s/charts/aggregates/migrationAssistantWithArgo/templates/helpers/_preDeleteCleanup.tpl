{{/*
  Pre-delete cleanup that must run while Argo, Strimzi, and ACK controllers are
  still available.  Child chart ordering remains the responsibility of
  _uninstallHelper.tpl; this helper only drains release-owned runtime state.
*/}}
{{- define "migration.preDeleteCleanupFunctions" -}}
RELEASE_NAMESPACE={{ .Release.Namespace | quote }}

list_active_argo_workflows() {
  kubectl get workflows.argoproj.io -n "$RELEASE_NAMESPACE" -o json |
    jq -r '
      .items[]
      | select(
          (.status.phase // "") != "Succeeded"
          and (.status.phase // "") != "Failed"
          and (.status.phase // "") != "Error"
          and (.status.phase // "") != "Stopped"
          and (.status.phase // "") != "Terminated"
        )
      | .metadata.name
    '
}

wait_for_argo_workflows_to_quiesce() {
  _workflow_wait_attempt=1
  while [ "$_workflow_wait_attempt" -le 30 ]; do
    if ! _active_workflows=$(list_active_argo_workflows); then
      echo "FAIL: unable to list active Argo workflows in $RELEASE_NAMESPACE" >&2
      return 1
    fi
    if [ -z "$_active_workflows" ]; then
      echo "All Argo workflows are quiesced."
      return 0
    fi
    echo "Waiting for Argo workflows to quiesce: $(echo "$_active_workflows" | tr '\n' ' ')"
    sleep 2
    _workflow_wait_attempt=$((_workflow_wait_attempt + 1))
  done

  echo "FAIL: Argo workflows did not quiesce before uninstall:" >&2
  kubectl get workflows.argoproj.io -n "$RELEASE_NAMESPACE" \
    -o custom-columns=NAME:.metadata.name,PHASE:.status.phase,SHUTDOWN:.spec.shutdown >&2 || true
  return 1
}

delete_all_argo_workflows() {
  kubectl delete workflows.argoproj.io --all -n "$RELEASE_NAMESPACE" \
    --ignore-not-found --wait=true --timeout=60s
}

quiesce_and_delete_argo_workflows() {
  if ! kubectl get crd workflows.argoproj.io >/dev/null 2>&1; then
    echo "Argo Workflow CRD is not installed; no workflows to quiesce."
    return 0
  fi

  if ! _active_workflows=$(list_active_argo_workflows); then
    echo "FAIL: unable to list active Argo workflows in $RELEASE_NAMESPACE" >&2
    return 1
  fi
  for _workflow in $_active_workflows; do
    echo "Stopping Argo workflow: $_workflow"
    retry 3 5 "stop Argo workflow $_workflow" -- \
      kubectl patch workflows.argoproj.io "$_workflow" -n "$RELEASE_NAMESPACE" \
        --type=merge -p '{"spec":{"shutdown":"Stop"}}'
  done

  wait_for_argo_workflows_to_quiesce
  retry 2 5 "delete Argo workflows" -- delete_all_argo_workflows

  if ! _remaining_workflows=$(kubectl get workflows.argoproj.io -n "$RELEASE_NAMESPACE" -o name); then
    echo "FAIL: unable to verify Argo workflow deletion" >&2
    return 1
  fi
  if [ -n "$_remaining_workflows" ]; then
    echo "FAIL: Argo workflows remain after deletion:" >&2
    echo "$_remaining_workflows" >&2
    return 1
  fi
}

run_workflow_reset() {
  timeout 240 workflow reset --all --include-proxies --delete-storage \
    --namespace "$RELEASE_NAMESPACE"
}

reset_migration_resources() {
  echo "Resetting migration resources before child controllers are uninstalled..."
  retry 2 10 "workflow reset" -- run_workflow_reset
}

delete_migration_run_history() {
  if ! kubectl get crd migrationruns.migrations.opensearch.org >/dev/null 2>&1; then
    echo "MigrationRun CRD is not installed; no run history to delete."
    return 0
  fi

  echo "Deleting immutable MigrationRun history for the release namespace..."
  retry 2 5 "delete MigrationRun history" -- \
    kubectl delete migrationruns.migrations.opensearch.org --all \
      -n "$RELEASE_NAMESPACE" --ignore-not-found --wait=true --timeout=60s
}

list_group_resources() {
  _api_group=$1
  if ! _api_resources=$(kubectl api-resources --api-group="$_api_group" \
      --namespaced=true -o name); then
    echo "FAIL: unable to discover resources for API group $_api_group" >&2
    return 1
  fi

  for _api_resource in $_api_resources; do
    if ! kubectl get "$_api_resource" -n "$RELEASE_NAMESPACE" -o name; then
      echo "FAIL: unable to list $_api_resource in $RELEASE_NAMESPACE" >&2
      return 1
    fi
  done
}

verify_no_migration_or_strimzi_resources() {
  _remaining_resources=""
  for _api_group in migrations.opensearch.org kafka.strimzi.io core.strimzi.io; do
    if ! _group_resources=$(list_group_resources "$_api_group"); then
      return 1
    fi
    if [ -n "$_group_resources" ]; then
      _remaining_resources="${_remaining_resources}
${_group_resources}"
    fi
  done

  if [ -n "$_remaining_resources" ]; then
    echo "FAIL: migration or Strimzi resources remain after workflow reset:" >&2
    echo "$_remaining_resources" >&2
    return 1
  fi
  echo "Verified that no migration or Strimzi custom resources remain."
}

{{- if get .Values.conditionalPackageInstalls "ack-cloudwatch-controller" }}
# Mounted from the cloudwatch-dashboard-manifests hook ConfigMap; see cloudwatchDashboardManifests.yaml.
delete_rendered_cloudwatch_dashboards() {
  kubectl delete -f /dashboards/dashboards.yaml --ignore-not-found --wait=true --timeout=90s
}

delete_release_cloudwatch_dashboards() {
  echo "Deleting release CloudWatch Dashboard resources while ACK is available..."
  retry 2 5 "delete CloudWatch Dashboard resources" -- delete_rendered_cloudwatch_dashboards
}
{{- else }}
delete_release_cloudwatch_dashboards() {
  echo "ACK CloudWatch controller is disabled; no Dashboard resources to delete."
}
{{- end }}

prepare_release_for_uninstall() {
  quiesce_and_delete_argo_workflows
  reset_migration_resources
  delete_migration_run_history
  verify_no_migration_or_strimzi_resources
  delete_release_cloudwatch_dashboards
}
{{- end }}
