{{- define "generic.pullEnvVarsFromConfigMaps" }}
{{- $namespace := .NameSpace }}
{{- range $key, $param := .Parameters }}
  - name: {{ include "toSnakeCase" $key }}_DEFAULT
    valueFrom:
      configMapKeyRef:
        name: {{ $key | lower }}-default
        namespace: {{ $namespace }}
        key: {{ if hasKey $param "value" }}value{{ else if hasKey $param "list" }}list{{ else }}present{{ end }} {{/*TODO be explicit*/}}
{{- if hasKey $param "allowRuntimeOverride" | ternary $param.allowRuntimeOverride true }}
  - name: {{ include "toSnakeCase" $key }}
    valueFrom:
      configMapKeyRef:
        name: {{ $key | lower }}
        namespace: {{ $namespace }}
        key: {{ if hasKey $param "value" }}value{{ else if hasKey $param "list" }}list{{ else }}present{{ end }}
        optional: true
{{- end }}
{{- end }}
{{- end }}
