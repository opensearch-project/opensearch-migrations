{{- define "generic.createNamespace" }}
{{- $create := .create }}
{{- $namespace := .namespace }}
{{- $annotations := .annotations }}
{{- $labels := .labels }}
{{- if $create }}
apiVersion: v1
kind: Namespace
metadata:
  name: {{ if $namespace }}{{ $namespace }}{{ else }}{{ .Release.Namespace }}{{ end }}
  {{- with $annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  {{- with $labels }}
  labels:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
{{- end }}