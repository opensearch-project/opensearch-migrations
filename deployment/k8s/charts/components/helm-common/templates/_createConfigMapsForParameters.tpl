{{- define "generic.createConfigMaps" }}
{{- $packageName := .PackageName -}}
{{- $namespace := .NameSpace -}}
{{- range $key, $param := .Parameters }}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ $key | lower }}-default
  namespace: {{ $namespace }}
  labels:
    type: default
data:
  {{- if hasKey $param "value" }}
  value: "{{ $param.value }}"
  {{- else if hasKey $param "list" }}
  list: |
    {{- range $item := $param.list }}
    - "{{ $item }}"
    {{- end }}
  {{- else }}
  present: "true"
  {{- end }}
{{- if hasKey $param "allowRuntimeOverride" | ternary $param.allowRuntimeOverride true }}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ $key | lower }}
  namespace: {{ $namespace }}
  labels:
    type: override
data: {}  # Empty configmap for user overrides
{{- end }}
{{- end }}
{{- end }}