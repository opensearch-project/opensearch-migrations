{{- define "generic.createParameterConfigMap" }}
{{- $lines := list -}}
{{ $key := .Key }}
{{ $param := .Param }}
{{ $namePrefix := .Prefix }}
{{- $weight := .Weight | default 0 }}
    {{- if or (not (hasKey $param "source")) (eq $param.source "parameterConfig") }}
        {{- $lines = append $lines "apiVersion: v1" -}}
        {{- $lines = append $lines "kind: ConfigMap" -}}
        {{- $lines = append $lines "metadata:" -}}
        {{- $lines = append $lines (printf "  name: %s%s-default"  $namePrefix (kebabcase $key)) -}}
        {{- $lines = append $lines "  labels:" -}}
        {{- $lines = append $lines "    type: default" -}}
        {{- $lines = append $lines "  annotations:" -}}
        {{- $lines = append $lines (printf "    helm.sh/hook-weight: \"%d\""  $weight) -}}
        {{- $lines = append $lines "data:" -}}

        {{- if hasKey $param "value" }}
            {{- $lines = append $lines (printf "  value: \"%s\"" $param.value) -}}
        {{- else if hasKey $param "list" }}
            {{- $lines = append $lines "  list: |" -}}
            {{- range $item := $param.list }}
                {{- $lines = append $lines (printf "    - %s" $item) -}}
            {{- end }}
        {{- else if hasKey $param "object" }}
            {{- $lines = append $lines ($param.object | toYaml | indent 2) }}
        {{- else }}
            {{- $lines = append $lines "  present: \"true\"" -}}
            {{/* It's possible that the parameter comes from an already existing config-map and  */}}
            {{/* that we don't need to create a new one for this parameter. */}}
        {{- end -}}
        {{- $lines = append $lines "---" -}}

        {{- if hasKey $param "allowRuntimeOverride" | ternary $param.allowRuntimeOverride true -}}
            {{- $lines = append $lines "apiVersion: v1" -}}
            {{- $lines = append $lines "kind: ConfigMap" -}}
            {{- $lines = append $lines "metadata:" -}}
            {{- $lines = append $lines (printf "  name: %s%s"  $namePrefix (kebabcase $key)) -}}
            {{- $lines = append $lines "  labels:" -}}
            {{- $lines = append $lines "    type: default" -}}
            {{- $lines = append $lines "  annotations:" -}}
            {{- $lines = append $lines (printf "    helm.sh/hook-weight: \"%d\""  $weight) -}}
            {{- $lines = append $lines "data: {}" -}} {{/* Empty configmap for user overrides */}}
            {{- $lines = append $lines "---" -}}
        {{- end -}}
    {{- end -}}
    {{- join "\n" $lines -}}
{{- end -}}

{{- define "generic.createConfigMaps" -}}
{{- $outerCtx := . -}}
{{- $packageName := .PackageName -}}
    {{- range $key, $param := .Parameters -}}
        {{- include "generic.createParameterConfigMap" (merge (dict
          "Key" $key
          "Param" $param
          "Prefix" (printf "%s-" $packageName)) $outerCtx) -}}
    {{- end -}}
{{- end -}}
