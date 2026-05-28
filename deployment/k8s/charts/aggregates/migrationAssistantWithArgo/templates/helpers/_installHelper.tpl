{{- define "migration.chartWaitForInstallation" -}}
{{- $root := .root -}}
{{- $chart := .chart | default dict -}}
{{- $forceWait := .forceWait | default false -}}
{{- if $forceWait -}}
true
{{- else if hasKey $chart "waitForInstallation" -}}
{{- $chart.waitForInstallation -}}
{{- else -}}
{{- $installer := $root.Values.installer | default dict -}}
{{- if hasKey $installer "defaultWaitForInstallation" -}}
{{- $installer.defaultWaitForInstallation -}}
{{- else -}}
true
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "migration.installChart" -}}
{{- $name := .name -}}
{{- $chart := .chart -}}
{{- $root := .root -}}
{{- $forceWait := .forceWait -}}
{{- $waitForInstallation := include "migration.chartWaitForInstallation" (dict "chart" $chart "root" $root "forceWait" $forceWait) -}}
{{- if $chart.values }}
              VALUES_B64="{{ $chart.values | toYaml | b64enc }}"
              echo "$VALUES_B64" | base64 -d > /tmp/values-{{ $name }}.yaml
              VALUE_PARAM="--values /tmp/values-{{ $name }}.yaml"
{{- else }}
              VALUE_PARAM=""
{{- end }}
              do_install_{{ $name | replace "-" "_" }}() {
{{- $repo := $chart.repository }}
{{- if hasPrefix "oci://" $repo }}
                helm upgrade --install {{ $name }} {{ $repo }} \
{{- else }}
                helm upgrade --install {{ $name }} chart-repo-{{ $name }}/{{ $name }} \
{{- end }}
{{- if $chart.version }}
                  --version {{ $chart.version }} \
{{- end }}
{{- if $chart.namespace }}
                  --namespace {{ $chart.namespace }} \
                  --create-namespace \
{{- end }}
                  --timeout {{ default "300" $chart.timeout }}s \
                  --set global.managedBy="{{ $root.Release.Name }}" \
{{- if eq $waitForInstallation "true" }}
                  $VALUE_PARAM \
                  --wait
{{- else }}
                  $VALUE_PARAM
{{- end }}
              }
              retry "$HELM_INSTALL_RETRY_ATTEMPTS" "$HELM_INSTALL_RETRY_SLEEP_SECONDS" "helm upgrade --install {{ $name }}" -- do_install_{{ $name | replace "-" "_" }}
              touch /tmp/helm-status/{{ $name }}.done
{{- end }}
