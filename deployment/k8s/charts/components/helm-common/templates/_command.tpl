{{- define "generic.buildCommandBuilderScript" -}}
set -e
{{- $cmdvarname := .CmdVarName }}
{{ $cmdvarname }}="{{ .Command }}"

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
  {{ $cmdvarname }}="${{ $cmdvarname }} --{{ $key }} ${{ $envVarName }}"
fi
{{- else if hasKey $param "list" }}
if [ -n "${{ $envVarName }}" ]; then
  LIST_ITEMS=$(echo "${{ $envVarName }}" | yq eval '.[]' - | xargs -I{} echo -n "{} ")
  {{ $cmdvarname }}="${{ $cmdvarname }} --{{ $key }} $LIST_ITEMS"
fi
{{- else }}
if [ "${{ $envVarName }}" = "true" ] || [ "${{ $envVarName }}" = "1" ]; then
  {{ $cmdvarname }}="${{ $cmdvarname }} --{{ $key }}"
fi
{{- end }}
{{- end }}
{{- end -}}

{{- define "generic.buildCommand" -}}
{{- include "generic.buildCommand" (dict
  "CmdVarName" "CMD"
  "Command" "{{ .Command }}"
  "include" .Template.Include
  "Template" .Template) }}

echo "Executing command: ${{ .VarName }}"
exec ${{ .VarName }}
{{- end -}}