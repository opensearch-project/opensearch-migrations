{{- define "generic.appName" -}}
{{- $chartName := "" }}
{{- $nameOverride := "" }}
{{- if .Chart }}{{ $chartName = default "chart" .Chart.Name }}{{ else }}{{ $chartName = "chart" }}{{ end }}
{{- if and .Values .Values.nameOverride }}{{ $nameOverride = .Values.nameOverride }}{{ else }}{{ $nameOverride = $chartName }}{{ end }}
{{- $nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "generic.fullname" -}}
{{- if and .Values .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $releaseName := "" }}
{{- $chartName := "" }}
{{- if .Release }}{{ $releaseName = default "release" .Release.Name }}{{ else }}{{ $releaseName = "release" }}{{ end }}
{{- if .Chart }}{{ $chartName = default "chart" .Chart.Name }}{{ else }}{{ $chartName = "chart" }}{{ end }}
{{- printf "%s-%s" $releaseName $chartName | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}