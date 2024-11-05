# _helpers.tpl (in a common chart or copied to each service chart)
{{- define "common.validateSharedConfig" }}
apiVersion: batch/v1
kind: Job
metadata:
  name: config-validation-{{ .Release.Name }}
  annotations:
    "helm.sh/hook": pre-install,pre-upgrade
    "helm.sh/hook-weight": "-5"  # Run after flag creation/console validation
    "helm.sh/hook-delete-policy": hook-succeeded,hook-failed
spec:
  template:
    spec:
      serviceAccountName: config-validator
      containers:
      - name: validator
        image: bitnami/kubectl:latest
        command:
        - /bin/bash
        - -c
        - |
          echo "Validating config mode..."
          FLAG=$(kubectl get configmap shared-config-mode -o jsonpath='{.data.enabled}' 2>/dev/null || echo "")
          DESIRED="{{ .sharedConfigEnabled }}"

          if [ -n "$FLAG" ]; then
            if [ "$FLAG" != "$DESIRED" ]; then
              echo "Error: {{ .Chart.Name }} sharedConfigEnabled ($DESIRED) does not match system setting ($FLAG)"
              exit 1
            fi
          else
            # No flag - create it as false
            kubectl create configmap shared-config-mode --from-literal=enabled=false
          fi

          echo "Config mode validation passed"
      restartPolicy: Never
{{- end }}