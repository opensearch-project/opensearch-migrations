{{- define "generic.setupEnvLoadInitContainer" -}}
{{- $mountName := .MountName -}}
- name: arg-prep
  image: migrations/service_yaml_from_config_maps
  imagePullPolicy: IfNotPresent
  env:
    {{- include "generic.pullEnvVarsFromConfigMaps" (dict
         "Parameters" .Values.parameters
         "PackageName" (include "generic.fullname" .)
         "include" .Template.Include
         "Template" .Template) | indent 4 }}

  command:
    - /bin/sh
    - -c
    - |
      {{- include "generic.buildArgumentsBuilderScript" (dict
          "Parameters" .Values.parameters
          "PackageName" (include "generic.fullname" .)
          "PositionalArguments" .PositionalArguments
          "include" .Template.Include
          "Template" .Template) | nindent 6 }}
      /.venv/bin/python env_vars_from_config_maps.py > /shared/vars.sh

  volumeMounts:
    - name: {{ $mountName }}
      mountPath: /shared
{{- end -}}
