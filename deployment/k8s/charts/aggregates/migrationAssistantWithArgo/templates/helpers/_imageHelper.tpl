{{/*
Construct full image name with optional registry prefix.
Usage: {{ include "migrationAssistant.image" (dict "imageConfig" .Values.images.installer "registryPrefix" .Values.images.registryPrefix) }}
*/}}
{{- define "migrationAssistant.image" -}}
{{- $registryPrefix := .registryPrefix | default "" -}}
{{- $repository := .imageConfig.repository -}}
{{- $tag := .imageConfig.tag | default "latest" -}}
{{- if $registryPrefix -}}
{{- /* Strip http:// or https:// prefix if present */ -}}
{{- $registryPrefix = $registryPrefix | trimPrefix "http://" | trimPrefix "https://" -}}
{{- /* Ensure registry prefix ends with / */ -}}
{{- if not (hasSuffix "/" $registryPrefix) -}}
{{- $registryPrefix = printf "%s/" $registryPrefix -}}
{{- end -}}
{{- printf "%s%s:%s" $registryPrefix $repository $tag -}}
{{- else -}}
{{- printf "%s:%s" $repository $tag -}}
{{- end -}}
{{- end -}}
