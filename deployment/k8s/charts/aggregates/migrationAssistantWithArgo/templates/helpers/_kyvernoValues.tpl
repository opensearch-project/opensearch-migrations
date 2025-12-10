{{/*
Generate Kyverno chart values with dynamic image references
*/}}
{{- define "migrationAssistant.kyvernoValues" -}}
admissionController:
  replicas: {{ .Values.charts.kyverno.values.admissionController.replicas | default 1 }}
backgroundController:
  replicas: {{ .Values.charts.kyverno.values.backgroundController.replicas | default 1 }}
cleanupController:
  replicas: {{ .Values.charts.kyverno.values.cleanupController.replicas | default 1 }}
reportsController:
  replicas: {{ .Values.charts.kyverno.values.reportsController.replicas | default 1 }}
cleanupJobs:
  admissionReports:
    image:
      repository: {{ .Values.images.migrationConsole.repository }}
      tag: {{ .Values.images.migrationConsole.tag }}
  clusterAdmissionReports:
    image:
      repository: {{ .Values.images.migrationConsole.repository }}
      tag: {{ .Values.images.migrationConsole.tag }}
webhooksCleanup:
  image:
    repository: {{ .Values.images.migrationConsole.repository }}
    tag: {{ .Values.images.migrationConsole.tag }}
{{- end -}}
