{{- define "migration.installChart" -}}
{{- $name := .name -}}
{{- $chart := .chart -}}
{{- $root := .root -}}
{{- $forceWait := .forceWait -}}
{{- if $chart.values }}
              VALUES_B64="{{ $chart.values | toYaml | b64enc }}"
              echo "$VALUES_B64" | base64 -d > /tmp/values-{{ $name }}.yaml
              VALUE_PARAM="--values /tmp/values-{{ $name }}.yaml"
{{- else }}
              VALUE_PARAM=""
{{- end }}
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
                $VALUE_PARAM \
{{- if or $chart.waitForInstallation $forceWait }}
                --wait
{{- end }}
              touch /tmp/helm-status/{{ $name }}.done
{{- end }}
