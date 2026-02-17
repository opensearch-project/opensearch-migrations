{{/*
  Uninstall Helper — Helm Release Cleanup Functions
  ==================================================

  This template defines `uninstall_charts`, the core function used by both the
  pre-delete and post-delete uninstall jobs.

  ## Uninstall Strategy (Two-Phase)

  Phase 1 — Pre-delete hook (uninstallJob.yaml, hook-weight: -10):
    Runs BEFORE Helm deletes the umbrella chart's own resources.
    Calls: uninstall_charts fluent-bit
    Effect: Uninstalls all managed child charts IN PARALLEL, except:
      - fluent-bit: kept alive so logs are captured during teardown
      - kyverno: always uninstalled LAST and SYNCHRONOUSLY (see below)

  Phase 2 — Post-delete hook (uninstallJob.yaml, hook-weight: 10):
    Runs AFTER Helm deletes the umbrella chart's own resources.
    Calls: uninstall_charts
    Effect: Uninstalls any remaining charts (primarily fluent-bit).
    The function re-discovers releases dynamically, so charts already removed
    in Phase 1 are simply not found — no double-uninstall occurs.

  ## Why Kyverno Is Uninstalled Last

  Kyverno installs admission webhooks that intercept API requests cluster-wide.
  If kyverno is removed while other charts are still being uninstalled, the
  webhook endpoints disappear but the webhook configurations remain, causing
  API calls from other helm uninstalls to hang or fail. By uninstalling kyverno
  synchronously after all other charts are gone, we avoid this.

  The kyverno cleanup sequence is:
    1. Delete webhook configurations (prevents API blocking)
    2. helm uninstall with timeout (clean removal)
    3. Force-delete remaining resources if timeout exceeded
    4. Delete CRDs (final cleanup)

  ## Ownership Model

  Each child chart is tagged with `global.managedBy=<release-name>` during
  install. The uninstall function only removes charts whose managedBy value
  matches the current umbrella release, preventing accidental deletion of
  charts managed by other releases.
*/}}
{{- define "migration.helmUninstallFunctions" -}}
{{- $kyvernoChart := index .Values.charts "kyverno" -}}
uninstall_charts() {
  # Args: space-separated release names to skip in the parallel uninstall phase
  local RELEASES_TO_SKIP=("$@")
  # Kyverno is always handled separately at the end (see header comment)
  RELEASES_TO_SKIP+=("kyverno")

  echo "Starting Helm uninstallation sequence..."
  UMBRELLA_CHART_ID="{{ .Release.Name }}"

  # Discover all helm releases across all namespaces by inspecting helm secrets.
  # This is dynamic — we don't rely on values.yaml to know what's installed.
  echo "Discovering all Helm releases in the cluster..."
  NAMESPACES=$(kubectl get namespaces -o jsonpath='{.items[*].metadata.name}')

  for NAMESPACE in $NAMESPACES; do
    echo "Checking for Helm releases in namespace: $NAMESPACE"

    HELM_SECRETS=$(kubectl get secrets -n $NAMESPACE -l "owner=helm" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || echo "")

    if [ -n "$HELM_SECRETS" ]; then
      for SECRET in $HELM_SECRETS; do
        RELEASE_NAME=$(echo $SECRET | sed -E 's/sh\.helm\.release\.v1\.([^\.]+).*$/\1/')

        # Skip releases in the exclusion list
        if [ "${#RELEASES_TO_SKIP[@]}" -gt 0 ]; then
          for CANDIDATE in "${RELEASES_TO_SKIP[@]}"; do
            if [ "$RELEASE_NAME" = "$CANDIDATE" ]; then
              echo "Skipping release $RELEASE_NAME (explicitly excluded)"
              continue 2  # skip outer for-loop iteration
            fi
          done
        fi

        if [ -n "$RELEASE_NAME" ]; then
          echo "Found Helm release: $RELEASE_NAME in namespace: $NAMESPACE"

          # Only uninstall charts owned by this umbrella release
          OWNERSHIP=$(helm get values $RELEASE_NAME -n $NAMESPACE -o json 2>/dev/null | jq -r '.global.managedBy // empty')

          if [ "$OWNERSHIP" = "$UMBRELLA_CHART_ID" ]; then
            echo "Chart $RELEASE_NAME is managed by this umbrella chart. Uninstalling..."
            (helm uninstall $RELEASE_NAME -n $NAMESPACE --debug && echo "Successfully uninstalled chart: $RELEASE_NAME" || echo "Failed to uninstall chart: $RELEASE_NAME") &
          else
            echo "Chart $RELEASE_NAME exists but is not managed by this umbrella chart ($UMBRELLA_CHART_ID). Skipping."
          fi
        fi
      done
    else
      echo "No Helm releases found in namespace: $NAMESPACE"
    fi
  done

  # Wait for all parallel uninstalls to complete before handling kyverno
  wait
  echo "Parallel uninstallation sequence completed."

  # Kyverno cleanup: always attempted, discovery-based (safe no-op if not installed)
  for NAMESPACE in $NAMESPACES; do
    OWNERSHIP=$(helm get values kyverno -n $NAMESPACE -o json 2>/dev/null | jq -r '.global.managedBy // empty')
    if [ "$OWNERSHIP" = "$UMBRELLA_CHART_ID" ]; then
{{- if and $kyvernoChart $kyvernoChart.preUninstallCleanup $kyvernoChart.preUninstallCleanup.webhooks }}
      # Step 1: Remove webhook configs first so they don't block API calls during uninstall
      echo "Deleting kyverno webhook configurations..."
      kubectl delete mutatingwebhookconfigurations -l app.kubernetes.io/instance=kyverno --ignore-not-found
      kubectl delete validatingwebhookconfigurations -l app.kubernetes.io/instance=kyverno --ignore-not-found
{{- end }}

      # Step 2: Uninstall with timeout; force-clean if it hangs
      echo "Uninstalling kyverno last..."
      timeout {{ if and $kyvernoChart $kyvernoChart.uninstallTimeout }}{{ $kyvernoChart.uninstallTimeout }}{{ else }}120{{ end }} helm uninstall kyverno -n $NAMESPACE --debug || {
        echo "kyverno uninstall timed out, force-cleaning remaining resources..."
        kubectl delete all -l app.kubernetes.io/instance=kyverno -n $NAMESPACE --force --grace-period=0 || true
      }

{{- if and $kyvernoChart $kyvernoChart.postUninstallCleanup $kyvernoChart.postUninstallCleanup.crds }}
      # Step 3: CRDs aren't removed by helm uninstall — clean them up explicitly
      echo "Cleaning up kyverno CRDs..."
      kubectl delete crd -l app.kubernetes.io/instance=kyverno --ignore-not-found
{{- end }}

      # Step 4: Delete kyverno namespace (if separate from release namespace)
      if [ "$NAMESPACE" != "{{ .Release.Namespace }}" ]; then
        echo "Deleting kyverno namespace: $NAMESPACE"
        kubectl delete namespace "$NAMESPACE" --ignore-not-found --grace-period=0 || true
      fi
      break
    fi
  done
}
{{- end }}
