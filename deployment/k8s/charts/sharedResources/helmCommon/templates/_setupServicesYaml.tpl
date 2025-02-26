{{- define "generic.setupServicesYamlContainer" -}}
{{- $mountName := .MountName -}}
- name: service-yaml-agent
  image: migrations/k8s_config_map_util_scripts
  imagePullPolicy: IfNotPresent
  restartPolicy: Always
  volumeMounts:
    - name: {{ $mountName }}
      mountPath: /config
  command:
    - /bin/sh
    - -c
    - |
      pipenv run config_watcher --namespace={{.Release.Namespace}} --outfile=/config/migration_services.yaml
{{- end -}}
