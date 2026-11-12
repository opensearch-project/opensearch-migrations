{{/*
Validate the global Kafka storage policy once from a template rendered in every
configuration. Helm values are intentionally percentages of capacity remaining
free; dashboards convert them to utilization only at presentation time.
*/}}
{{- define "migration.validateKafkaObservability" -}}
{{- $storage := .Values.kafkaObservability.storage -}}
{{- $warning := int $storage.warningFreePercent -}}
{{- $critical := int $storage.criticalFreePercent -}}
{{- $inode := int $storage.inodeFreePercent -}}
{{- if or (lt $warning 0) (gt $warning 100) -}}
  {{- fail "kafkaObservability.storage.warningFreePercent must be between 0 and 100" -}}
{{- end -}}
{{- if or (lt $critical 0) (gt $critical 100) -}}
  {{- fail "kafkaObservability.storage.criticalFreePercent must be between 0 and 100" -}}
{{- end -}}
{{- if ge $critical $warning -}}
  {{- fail "kafkaObservability.storage.criticalFreePercent must be lower than warningFreePercent" -}}
{{- end -}}
{{- if or (lt $inode 0) (gt $inode 100) -}}
  {{- fail "kafkaObservability.storage.inodeFreePercent must be between 0 and 100" -}}
{{- end -}}
{{- if lt (int $storage.projectedFillWarningHorizonHours) 1 -}}
  {{- fail "kafkaObservability.storage.projectedFillWarningHorizonHours must be at least 1" -}}
{{- end -}}
{{- end -}}
