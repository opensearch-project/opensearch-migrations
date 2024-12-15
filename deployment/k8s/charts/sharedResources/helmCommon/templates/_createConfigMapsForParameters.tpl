{{- define "generic.createParameterConfigMap" }}
{{ $key := .Key }}
{{ $param := .Param }}
{{ $namePrefix := .Prefix }}
{{ $namespace := .NameSpace }}
{{- $weight := .Weight | default 0 }}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ $namePrefix }}{{ $key | include "toKebabCase" }}-default
  namespace: {{ $namespace }}
  labels:
    type: default
  annotations:
    helm.sh/hook-weight: "{{ $weight }}"
data:
  {{- if hasKey $param "value" }}
  value: "{{ $param.value }}"
  {{- else if hasKey $param "list" }}
  list: |
    {{- range $item := $param.list }}
    - "{{ $item }}"
    {{- end }}
  {{- else if hasKey $param "list" }}
  data: |
    {{ $param.data | toYaml | indent 4 }}
  {{- else }}
  present: "true"
  {{- end }}

{{- if hasKey $param "allowRuntimeOverride" | ternary $param.allowRuntimeOverride true }}
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ $namePrefix }}{{ $key | include "toKebabCase" }}
  namespace: {{ $namespace }}
  labels:
    type: override
  annotations:
    helm.sh/hook-weight: "{{ $weight }}"
data: {}  # Empty configmap for user overrides
{{- end }}
{{- end -}}

{{- define "generic.createConfigMaps" }}
{{- $outerCtx := . -}}
{{- $packageName := .PackageName -}}
{{- range $key, $param := .Parameters }}
{{- include "generic.createParameterConfigMap" (merge (dict
  "Key" $key
  "Param" $param
  "Prefix" (printf "%s-" $packageName)) $outerCtx) }}
{{- end }}
{{- end }}
