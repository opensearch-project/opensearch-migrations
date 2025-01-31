{{/*
Format a value appropriately for a ConfigMap based on its type:
- Maps and slices are converted to YAML with proper indentation
- Numbers and booleans are quoted as strings
- Strings are returned as-is
Usage: include "configMapValue" (dict "value" $value "context" $)
*/}}
{{- define "generic.formatValueForConfigMapValue" -}}
{{- $value := .value -}}
{{- $lines := list -}}

{{- $lines = append $lines "data:" -}}
{{- if kindIs "map" $value -}}
    {{- range $line := splitList "\n" (toYaml $value) -}}
        {{- $pair := regexSplit ":" $line 2 -}}
        {{- $key := trim (index $pair 0) -}}
        {{- $value := trim (index $pair 1) -}}

        {{ if and $value (not (hasPrefix "\"" $value)) (not (hasPrefix "'" $value)) -}}
            {{- $lines = append $lines (printf "  %s: %s" $key (quote $value)) -}}
        {{- else -}}
            {{- $lines = append $lines (printf "  %s" $line) -}}
        {{- end -}}
    {{- end -}}
{{- else -}}
    {{- if kindIs "slice" $value -}}
        {{- $lines = append $lines "  value: |-" -}}
        {{- range $line := splitList "\n" (toYaml $value) -}}
            {{- $lines = append $lines (printf "  %s" $line) -}}
        {{- end -}}
    {{- else if kindIs "string" $value -}}
        {{- $lines = append $lines (printf "  value: %s" $value) -}}
    {{- else -}}
        {{- $lines = append $lines (printf "  value: %s" (quote $value)) -}}
    {{- end -}}
{{- end -}}

{{- join "\n" $lines -}}
{{- end -}}