{{/*
Create a default fully qualified app name.
*/}}
{{- define "generic.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}
