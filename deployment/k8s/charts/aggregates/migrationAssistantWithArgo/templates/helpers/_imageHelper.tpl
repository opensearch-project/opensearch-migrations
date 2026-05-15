{{- define "migration.imageTag" -}}
{{- default .root.Chart.AppVersion .image.tag -}}
{{- end -}}

{{- define "migration.image" -}}
{{- printf "%s:%s" .image.repository (include "migration.imageTag" .) -}}
{{- end -}}
