{{- define "generic.setupServicesYamlContainer" -}}
{{- $mountName := .MountName -}}
- name: service-yaml-agent
  image: migrations/k8s_config_map_util_scripts
  imagePullPolicy: IfNotPresent
  restartPolicy: Always
  volumeMounts:
    - name: {{ $mountName }}
      mountPath: /config
      subPath: migration_services.yaml
  command:
    - /.venv/bin/python
    - config_watcher.py
    - "--namespace={{.Release.Namespace}}"
    - "--outfile=/config/migration_services.yaml"
{{- end -}}
