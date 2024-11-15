{{- define "generic.buildArgumentsBuilderScript" -}}
{{ $argsName := .ArgsVarName | default "ARGS" }}
set -e

{{- /* Default environment variables if not set */}}
{{- range $key, $param := .Parameters }}
{{- $envVarName := include "toSnakeCase" $key }}
if [ -z "${{ $envVarName }}" ]; then
  export {{ $envVarName }}="${{ $envVarName }}_DEFAULT"
fi
{{- end }}

{{- /* Construct the command based on parameter types */}}
{{- range $key, $param := .Parameters }}
{{- $envVarName := include "toSnakeCase" $key }}
{{- if hasKey $param "value" }}
if [ -n "${{ $envVarName }}" ]; then
  export {{ $argsName }}="${{ $argsName }} --{{ $key }} ${{ $envVarName }}"
fi
{{- else if hasKey $param "list" }}
if [ -n "${{ $envVarName }}" ]; then
  LIST_ITEMS=$(echo "${{ $envVarName }}" | yq eval '.[]' - | xargs -I{} echo -n "{} ")
  export {{ $argsName }}="${{ $argsName }} --{{ $key }} $LIST_ITEMS"
fi
{{- else }}
if [ "${{ $envVarName }}" = "true" ] || [ "${{ $envVarName }}" = "1" ]; then
  export {{ $argsName }}="${{ $argsName }} --{{ $key }}"
fi
{{- end }}
{{- end }}
{{- end -}}

{{- define "generic.buildCommandBuilderScript" -}}
{{- include "generic.buildArgumentsBuilderScript" . }}
{{- $command := .Command }}
{{- $cmdvarname := .CmdVarName }}
{{ $cmdvarname }}="{{ .Command }}"
{{ $cmdvarname}}="{{ $command }} $ARGS"
{{- end }}

{{- define "generic.buildCommand" -}}
{{- include "generic.buildCommandBuilderScript" (dict
  "CmdVarName" "CMD"
  "Command" .Command
  "Parameters" .Parameters
  "include" .Template.Include
  "Template" .Template) }}

echo "Executing command: $CMD"
exec $CMD
{{- end -}}