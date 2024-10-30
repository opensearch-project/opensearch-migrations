{{- define "generic.pullEnvVarsFromConfigMaps" }}
{{- $packageName := .PackageName }}
{{- range $key, $param := .Values.parameters }}
  - name: {{ include "toSnakeCase" $key }}_DEFAULT
    valueFrom:
      configMapKeyRef:
        name: {{ $packageName }}-{{ $key | lower }}-default
        key: {{ if hasKey $param "value" }}value{{ else if hasKey $param "list" }}list{{ else }}present{{ end }}
{{- if hasKey $param "allowRuntimeOverride" | ternary $param.allowRuntimeOverride true }}
  - name: {{ include "toSnakeCase" $key }}
    valueFrom:
      configMapKeyRef:
        name: {{ $packageName }}-{{ $key | lower }}
        key: {{ if hasKey $param "value" }}value{{ else if hasKey $param "list" }}list{{ else }}present{{ end }}
        optional: true
{{- end }}
{{- end }}
{{- end }}
