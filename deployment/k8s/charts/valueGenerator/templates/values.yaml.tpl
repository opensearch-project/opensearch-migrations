{{- define "values" -}}
{{- range $key, $value := .Values }}
{{ $key }}:
{{- if kindIs "string" $value }}
{{- $value | fromYaml | toYaml | nindent 2 }}
{{- else }}
{{- $value | toYaml | nindent 2 }}
{{- end }}
{{- end }}
{{- end -}}
{{ include "values" . }}
