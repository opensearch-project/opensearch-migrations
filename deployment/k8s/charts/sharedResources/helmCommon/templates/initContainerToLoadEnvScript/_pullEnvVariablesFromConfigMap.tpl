{{- define "generic.pullEnvVarsFromConfigMaps" }}
{{- $packageName := .PackageName -}}
{{- range $sourceKey, $param := .Parameters -}}
{{- $configMapKey := printf "%s-%s" $packageName (kebabcase $sourceKey) }}
{{- $envName := ( snakecase $sourceKey | upper) }}
- name: {{ $envName }}_DEFAULT
  valueFrom:
    configMapKeyRef:
      name: {{ $configMapKey }}-default
      key: {{ if hasKey $param "value" }}value{{ else if hasKey $param "list" }}list{{ else }}present{{ end }} {{/*TODO be explicit*/}}
{{- if hasKey $param "allowRuntimeOverride" | ternary $param.allowRuntimeOverride true }}
- name: {{ $envName }}
  valueFrom:
    configMapKeyRef:
      name: {{ $configMapKey }}
      key: {{ if hasKey $param "value" }}value{{ else if hasKey $param "list" }}list{{ else }}present{{ end }}
      optional: true
{{- end }}
{{- end }}
{{- end }}
