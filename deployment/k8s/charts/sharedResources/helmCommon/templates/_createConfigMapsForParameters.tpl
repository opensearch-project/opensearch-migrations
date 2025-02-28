{{- define "generic.createConfigMap" }}
    {{- $lines := list -}}
    {{ $name := .Name }}
    {{ $allowRuntimeOverride := .AllowRuntimeOverride }}
    {{ $value := .Value }}
    {{- $weight := .Weight | default 0 }}

    {{- $lines = append $lines "apiVersion: v1" -}}
    {{- $lines = append $lines "kind: ConfigMap" -}}
    {{- $lines = append $lines "metadata:" -}}
    {{- $lines = append $lines (printf "  name: %s-default"  $name) -}}
    {{- $lines = append $lines "  labels:" -}}
    {{- $lines = append $lines "    type: default" -}}
    {{- $lines = append $lines "  annotations:" -}}
    {{- $lines = append $lines (printf "    helm.sh/hook-weight: \"%d\""  $weight) -}}
    {{- $lines = append $lines (include "generic.formatValueForConfigMapValue" (dict "value" $value "context" $)) -}}
    {{- $lines = append $lines "---" -}}

    {{- if $allowRuntimeOverride -}}
        {{- $lines = append $lines "apiVersion: v1" -}}
        {{- $lines = append $lines "kind: ConfigMap" -}}
        {{- $lines = append $lines "metadata:" -}}
        {{- $lines = append $lines (printf "  name: %s"  $name) -}}
        {{- $lines = append $lines "  labels:" -}}
        {{- $lines = append $lines "    type: default" -}}
        {{- $lines = append $lines "  annotations:" -}}
        {{- $lines = append $lines (printf "    helm.sh/hook-weight: \"%d\""  $weight) -}}
        {{- $lines = append $lines "data: {}" -}}
        {{- $lines = append $lines "---" -}}
    {{- end -}}

    {{- join "\n" $lines -}}
{{- end -}}

{{- define "generic.createParameterConfigMap" }}
    {{- $lines := list -}}
    {{ $key := .Key }}
    {{ $param := .Param }}
    {{ $namePrefix := .Prefix }}
    {{- $weight := .Weight | default 0 }}

    {{- if or (not (hasKey $param "source")) (eq $param.source "parameterConfig") }}
        {{- include "generic.createConfigMap"
            (dict
            "Name" (printf "%s%s" $namePrefix (kebabcase $key))
            "AllowRuntimeOverride" (hasKey $param "allowRuntimeOverride" | ternary $param.allowRuntimeOverride true)
            "Value" $param.value
            "Weight" $weight)
        }}
    {{- end -}}

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
