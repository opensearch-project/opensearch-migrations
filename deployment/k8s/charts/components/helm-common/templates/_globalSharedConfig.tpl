# shared configuration flag ConfigMap template (used by all charts)
{{- define "sharedConfigFlag" }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: use-shared-configs
  labels:
    app.kubernetes.io/name: config-mode
  annotations:
    "helm.sh/hook": pre-install,pre-upgrade
    "helm.sh/hook-weight": "-10"  # Create flag before other validations
    "helm.sh/hook-delete-policy": before-hook-creation
data:
  enabled: "{{ . }}"
{{- end }}