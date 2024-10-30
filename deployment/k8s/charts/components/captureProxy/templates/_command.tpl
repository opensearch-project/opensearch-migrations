{{- define "generic.buildCommandBuilderScript" -}}
set -e
CMD="{{ .Command }}"

{{- /* Default environment variables if not set */}}
{{- range $key, $param := .Values.parameters }}
{{- $envVarName := include "toSnakeCase" $key }}
if [ -z "${{ $envVarName }}" ]; then
  export {{ $envVarName }}="${{ $envVarName }}_DEFAULT"
fi
{{- end }}

{{- /* Construct the command based on parameter types */}}
{{- range $key, $param := .Values.parameters }}
{{- $envVarName := include "toSnakeCase" $key }}
{{- if hasKey $param "value" }}
if [ -n "${{ $envVarName }}" ]; then
  CMD="$CMD --{{ $key }} ${{ $envVarName }}"
fi
{{- else if hasKey $param "list" }}
if [ -n "${{ $envVarName }}" ]; then
  LIST_ITEMS=$(echo "${{ $envVarName }}" | yq eval '.[]' - | xargs -I{} echo -n "{} ")
  CMD="$CMD --{{ $key }} $LIST_ITEMS"
fi
{{- else }}
if [ "${{ $envVarName }}" = "true" ] || [ "${{ $envVarName }}" = "1" ]; then
  CMD="$CMD --{{ $key }}"
fi
{{- end }}
{{- end }}

echo "Executing command: $CMD"
exec $CMD
{{- end -}}
