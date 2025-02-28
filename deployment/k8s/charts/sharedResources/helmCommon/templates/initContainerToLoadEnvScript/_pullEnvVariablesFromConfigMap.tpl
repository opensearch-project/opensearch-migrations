{{- define "generic.pullEnvVarsFromConfigMaps" }}
{{- $packageName := .PackageName -}}
{{- $lines := list -}}
{{- range $sourceKey, $param := .Parameters -}}

    {{- $configMapKey := (eq $param.source "otherConfig") | ternary
        $param.configMapName
        (printf "%s-%s" $packageName (kebabcase $sourceKey)) -}}
    {{- $keyFromConfigMap := (eq $param.source "otherConfig") | ternary
            $param.configMapKey
            "value" -}}

    {{- $envName := ( snakecase $sourceKey | upper) }}

    {{- $lines = append $lines (printf "- name: %s_DEFAULT" $envName) -}}
    {{- $lines = append $lines         "  valueFrom:" -}}
    {{- $lines = append $lines         "    configMapKeyRef:" -}}
    {{- $lines = append $lines (printf "      name: %s-default" $configMapKey) -}}
    {{- $lines = append $lines (printf "      key: %s" $keyFromConfigMap) -}}

    {{- $lines = append $lines (printf "- name: %s" $envName) -}}
    {{- $lines = append $lines         "  valueFrom:" -}}
    {{- $lines = append $lines         "    configMapKeyRef:" -}}
    {{- $lines = append $lines (printf "      name: %s" $configMapKey) -}}
    {{- $lines = append $lines (printf "      key: %s" $keyFromConfigMap) -}}
    {{- $lines = append $lines (printf "      optional: true") -}}
{{- end -}}
{{- join "\n" $lines -}}
{{- end }}
