{{/*
Convert a camelcase string to snake case
*/}}
{{- define "toSnakeCase" -}}
{{- /* First split by capital letters and add underscore */ -}}
{{- $result := regexReplaceAll "([A-Z])" . "_${1}" -}}
{{- /* Handle potential leading underscore */ -}}
{{- $result := regexReplaceAll "^_" $result "" -}}
{{- /* Replace '-' by '_' */ -}}
{{- $result := regexReplaceAll "-" $result "_" -}}
{{- /* Convert to uppercase */ -}}
{{- $result | upper -}}
{{- end -}}