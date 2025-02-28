{{- define "generic.buildArgsFromEnvVarParameters" -}}
    {{- $argsName := .ArgsVarName | default "ARGS" -}}
    {{- $argsFileName := .ArgsFileVarName | default "/shared/args.txt" -}}
    {{- $lines := list -}}
    {{- $lines = append $lines "set -e" -}}

    {{- /* Default environment variables if not set */ -}}
    {{- range $key, $param := .Parameters }}
        {{- $envVarName := snakecase $key | upper -}}
        {{- $lines = append $lines (printf "if [ -z \"$%s\" ]; then" $envVarName) -}}
        {{- $lines = append $lines (printf "  export %s=\"$%s_DEFAULT\"" $envVarName $envVarName) -}}
        {{- $lines = append $lines "fi" -}}
    {{- end }}

    {{- $positionalMap := dict }}
    {{- range $key, $param := .Parameters }}
        {{- $envVarName := snakecase $key | upper -}}
        {{- $formattedKeyFlagName := "" -}}
        {{- if hasKey $param "postionalArgumentIndex" -}}
            {{- $positionalMap = merge $positionalMap (dict $param.postionalArgumentIndex $envVarName) -}}
        {{- else -}}
            {{- $formattedKeyFlagName = printf " --%s " $key -}}
        {{- end -}}

{{/*paramMe:*/}}
{{/*  key: {{ $key }}*/}}
{{/*  all: {{ $param }}*/}}
{{/*  computed: {{ and (hasKey $param "parameterType") (eq $param.parameterType "booleanFlag") }}*/}}

        {{- if and (hasKey $param "parameterType") (eq $param.parameterType "booleanFlag") -}}
            {{- $lines = append $lines (printf "if [ \"$%s\" = \"true\" ] || [ \"$%s\" = \"1\" ]; then" $envVarName $envVarName) -}}
            {{- if eq "" $formattedKeyFlagName -}}
              {{ fail (printf "Got key %s as a booleanFlag and it is also specified as positional" $key) }}
            {{- end -}}
            {{- $lines = append $lines (printf "  export %s=\"$%s %s\"" $argsName $argsName $formattedKeyFlagName) -}}
            {{- $lines = append $lines (printf "  echo \"%s\" >> %s" $formattedKeyFlagName $argsFileName) -}}
            {{- $lines = append $lines (printf "fi") -}}
        {{- else -}}
            {{- if not (eq "" $formattedKeyFlagName) -}}
                {{- $lines = append $lines (printf "if [ -n \"$%s\" ]; then" $envVarName) -}}
                {{- $lines = append $lines (printf "  export %s=\"$%s %s $%s\"" $argsName $argsName $formattedKeyFlagName $envVarName) -}}
                {{- $lines = append $lines (printf "  echo \"%s\n$%s\" >> %s" $formattedKeyFlagName $envVarName $argsFileName) -}}
                {{- $lines = append $lines (printf "fi") -}}
            {{- end -}}
        {{- end -}}
    {{- end -}}

    {{- if len $positionalMap -}}
        {{- $orderedArgs := "" }}
        {{- range $i := until (len $positionalMap) }}
            {{- $orderedArgs = printf "%s $%s" $orderedArgs (get $positionalMap (toString $i)) -}}
            {{- $lines = append $lines (printf "printf \"$%s\n$(cat %s)\" > \"%s\"" (get $positionalMap (toString $i)) $argsFileName $argsFileName) -}}
        {{- end -}}
        {{- $lines = append $lines (printf "export %s=\"%s $%s\"" $argsName $orderedArgs $argsName) -}}
    {{- end }}
    {{- join "\n" $lines -}}
{{- end -}}
