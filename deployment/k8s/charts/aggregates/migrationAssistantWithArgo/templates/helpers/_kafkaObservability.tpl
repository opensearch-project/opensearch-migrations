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
{{- $forecastLookback := toString $storage.forecastLookback -}}
{{- if or (eq $forecastLookback "") (not (regexMatch "^([0-9]+y)?([0-9]+w)?([0-9]+d)?([0-9]+h)?([0-9]+m)?([0-9]+s)?([0-9]+ms)?$" $forecastLookback)) -}}
  {{- fail "kafkaObservability.storage.forecastLookback must be a Prometheus duration such as 6h" -}}
{{- end -}}
{{- if lt (int $storage.projectedFillWarningHorizonHours) 1 -}}
  {{- fail "kafkaObservability.storage.projectedFillWarningHorizonHours must be at least 1" -}}
{{- end -}}
{{- end -}}

{{/*
One kubelet volume series per Migration Assistant-managed Kafka PVC. kubelet
identifies a volume only by namespace/name, so the series are filtered by the
Strimzi labels that kube-state-metrics exports for the claim.
Arguments: root (the chart context), metric (the kubelet_volume_stats_* name).
*/}}
{{- define "migration.kafkaPvcMetric" -}}
{{- $namespace := .root.Release.Namespace -}}
(max by (namespace, persistentvolumeclaim) ({{ .metric }}{job="kubelet",metrics_path="/metrics",namespace="{{ $namespace }}"}) and on (namespace, persistentvolumeclaim) max by (namespace, persistentvolumeclaim) (kube_persistentvolumeclaim_labels{namespace="{{ $namespace }}",label_strimzi_io_cluster!="",label_strimzi_io_pool_name!=""}))
{{- end -}}

{{/*
Percentage of capacity remaining free for each managed Kafka PVC.
Arguments: root, free (the free-quantity metric), total (the capacity metric).
*/}}
{{- define "migration.kafkaPvcFreePercent" -}}
({{ include "migration.kafkaPvcMetric" (dict "root" .root "metric" .free) }} / {{ include "migration.kafkaPvcMetric" (dict "root" .root "metric" .total) }}) * 100
{{- end -}}
