{{- define "migration.helmUninstallFunctions" -}}
uninstall_charts() {
  # Accepts space-separated list of releases
  local RELEASES_TO_SKIP=("$@")

  echo "Starting Helm uninstallation sequence..."
  UMBRELLA_CHART_ID="{{ .Release.Name }}"

  # Find all helm releases in the cluster across all namespaces
  echo "Discovering all Helm releases in the cluster..."

  # Get list of all namespaces
  NAMESPACES=$(kubectl get namespaces -o jsonpath='{.items[*].metadata.name}')

  for NAMESPACE in $NAMESPACES; do
    echo "Checking for Helm releases in namespace: $NAMESPACE"

    # Get all Secret resources of type helm.sh/release.v1
    HELM_SECRETS=$(kubectl get secrets -n $NAMESPACE -l "owner=helm" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null || echo "")

    if [ -n "$HELM_SECRETS" ]; then
      for SECRET in $HELM_SECRETS; do
        # Extract the release name from the secret
        RELEASE_NAME=$(echo $SECRET | sed -E 's/sh\.helm\.release\.v1\.([^\.]+).*$/\1/')

        # Skip if in skip list
        if [ "${#RELEASES_TO_SKIP[@]}" -gt 0 ]; then
          for CANDIDATE in "${RELEASES_TO_SKIP[@]}"; do
            if [ "$RELEASE_NAME" = "$CANDIDATE" ]; then
              echo "Skipping release $RELEASE_NAME (explicitly excluded)"
              continue 2  # skip outer loop (not just inner)
            fi
          done
        fi

        # Remove fluent-bit logging chart skipped in main uninstall job
        if [ -n "$RELEASE_NAME" ]; then
          echo "Found Helm release: $RELEASE_NAME in namespace: $NAMESPACE"

          # Get the chart values and check for our ownership label
          OWNERSHIP=$(helm get values $RELEASE_NAME -n $NAMESPACE -o json 2>/dev/null | jq -r '.global.managedBy // empty')

          if [ "$OWNERSHIP" = "$UMBRELLA_CHART_ID" ]; then
            echo "Chart $RELEASE_NAME is managed by this umbrella chart. Uninstalling..."

            # Uninstall chart
            helm uninstall $RELEASE_NAME -n $NAMESPACE --debug &

            echo "Successfully uninstalled chart: $RELEASE_NAME"
          else
            echo "Chart $RELEASE_NAME exists but is not managed by this umbrella chart ($UMBRELLA_CHART_ID). Skipping."
          fi
        fi
      done
    else
      echo "No Helm releases found in namespace: $NAMESPACE"
    fi
  done

  wait
  echo "Uninstallation sequence completed!"
}
{{- end }}
