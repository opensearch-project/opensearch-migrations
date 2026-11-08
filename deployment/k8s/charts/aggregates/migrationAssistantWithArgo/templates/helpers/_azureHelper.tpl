{{- /*
  Azure Blob Storage names shared by the snapshot ConfigMap, the Argo artifact repository, the
  console env and the container jobs. Local defaults apply only when Azurite backs the container.
*/}}
{{- define "migration.azureIsLocal" -}}
{{- if or .Values.azureStorageConfiguration.useLocalAzurite (ne .Values.cloudProvider "azure") -}}true{{- end -}}
{{- end -}}

{{- define "migration.azureAccountName" -}}
{{- if include "migration.azureIsLocal" . -}}
{{- .Values.azureStorageConfiguration.accountName | default "devstoreaccount1" -}}
{{- else -}}
{{- required "Missing required value: .Values.azureStorageConfiguration.accountName" .Values.azureStorageConfiguration.accountName -}}
{{- end -}}
{{- end -}}

{{- define "migration.azureContainerName" -}}
{{- .Values.azureStorageConfiguration.containerName | default (ternary "migration-test-bucket" "migration-snapshots" (eq (include "migration.azureIsLocal" .) "true")) -}}
{{- end -}}

{{- /* Base URL without the account: the snapshot readers and the console append /<account>. */}}
{{- define "migration.azureBaseEndpoint" -}}
{{- if .Values.azureStorageConfiguration.useLocalAzurite -}}
{{- printf "http://%s.%s.svc.cluster.local:10000" .Values.azureStorageConfiguration.localAzuriteEndpoint .Release.Namespace -}}
{{- else -}}
{{- .Values.azureStorageConfiguration.endpoint | default "" -}}
{{- end -}}
{{- end -}}

{{- /* Account URL, as Argo's artifact driver and az CLI want it (the driver appends only the container). */}}
{{- define "migration.azureAccountUrl" -}}
{{- $base := include "migration.azureBaseEndpoint" . -}}
{{- $account := include "migration.azureAccountName" . -}}
{{- if $base -}}
{{- printf "%s/%s" (trimSuffix "/" $base) $account -}}
{{- else -}}
{{- printf "https://%s.blob.core.windows.net" $account -}}
{{- end -}}
{{- end -}}

{{- define "migration.azureWorkloadIdentityEnabled" -}}
{{- if and (eq .Values.cloudProvider "azure") .Values.azure.workloadIdentityClientId -}}true{{- end -}}
{{- end -}}

{{- /* Env var the Secret key maps to for the console (artifact_store.py) and az CLI. */}}
{{- define "migration.azureArtifactCredentialsKey" -}}
{{- $key := .Values.azureStorageConfiguration.artifactCredentials.key -}}
{{- if not (has $key (list "accountKey" "sasToken")) -}}
{{- fail (printf "azureStorageConfiguration.artifactCredentials.key must be accountKey or sasToken, got %q" $key) -}}
{{- end -}}
{{- $key -}}
{{- end -}}

{{- define "migration.azureContainerFunctions" }}
  # Same credential order as AzureRepo.create: account key, then SAS, then the Workload ID token.
  azure_login() {
    if [ -n "${AZURE_STORAGE_KEY:-}" ] || [ -n "${AZURE_STORAGE_SAS_TOKEN:-}" ]; then
      return 0
    fi
    if [ -z "${AZURE_FEDERATED_TOKEN_FILE:-}" ]; then
      echo "No Azure credentials: set azure.workloadIdentityClientId or azureStorageConfiguration.artifactCredentials.secretName."
      return 1
    fi
    az login --service-principal --username "$AZURE_CLIENT_ID" --tenant "$AZURE_TENANT_ID" \
      --federated-token "$(cat "$AZURE_FEDERATED_TOKEN_FILE")" --allow-no-subscriptions -o none
    export AZURE_STORAGE_AUTH_MODE=login
  }

  container_exists() {
    local max_retries="${1:-1}"
    local attempt=1
    while [ "$attempt" -le "$max_retries" ]; do
      # tsv prints Python booleans (True/False).
      if [ "$(az storage container exists --name "$CONTAINER_NAME" --query exists -o tsv)" = "True" ]; then
        return 0
      fi
      if [ "$attempt" -lt "$max_retries" ]; then
        sleep 2
      fi
      attempt=$((attempt + 1))
    done
    return 1
  }
{{- end }}
