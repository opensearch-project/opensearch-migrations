{{- define "generic.setupEnvLoadInitContainer" -}}
{{- $mountName := .MountName -}}
- name: arg-prep
  image: migrations/k8s_config_map_util_scripts
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
      {{- include "generic.buildArgsFromEnvVarParameters" (dict
          "Parameters" .Values.parameters
          "PackageName" (include "generic.fullname" .)
          "PositionalArguments" .PositionalArguments
          "include" .Template.Include
          "Template" .Template) | nindent 6 }}
      /.venv/bin/python print_env_vars_as_exports.py > /shared/vars.sh

  volumeMounts:
    - name: {{ $mountName }}
      mountPath: /shared
{{- end -}}
