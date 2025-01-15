{{- define "generic.buildArgsFromEnvVarParameters" -}}
    {{- $argsName := .ArgsVarName | default "ARGS" -}}
    {{- $lines := list -}}
    {{- $lines = append $lines "set -e" -}}

    {{- /* Default environment variables if not set */ -}}
    {{- range $key, $param := .Parameters }}
        {{- $envVarName := snakecase $key | upper -}}
        {{- $lines = append $lines (printf "if [ -z \"$%s\" ]; then" $envVarName) -}}
        {{- $lines = append $lines (printf "  export %s=\"$%s_DEFAULT\"" $envVarName $envVarName) -}}
        {{- $lines = append $lines "fi" -}}
    {{- end }}

    {{- /* Construct the command based on parameter types */ -}}
    {{- $keyToPositionMap := dict }}
    {{- $positionalMap := dict }}
    {{- if .PositionalArguments -}}
        {{- range $i, $v := .PositionalArguments }}
            {{- $_ := set $keyToPositionMap $v $i }}
        {{- end }}
    {{- end }}

    {{- range $key, $param := .Parameters }}
        {{- $envVarName := snakecase $key | upper -}}
        {{- $formattedKeyFlagName := "" -}}
        {{- if hasKey $keyToPositionMap $key -}}
            {{- $positionalMap = merge $positionalMap (dict (get $keyToPositionMap $key) $envVarName) -}}
        {{- else -}}
            {{- $formattedKeyFlagName = printf " --%s " $key -}}
        {{- end -}}

        {{- if hasKey $param "value" -}}
            {{- if not (eq "" $formattedKeyFlagName) -}}
                {{- $lines = append $lines (printf "if [ -n \"$%s\" ]; then" $envVarName) -}}
                {{- $lines = append $lines (printf "  export %s=\"$%s %s $%s\"" $argsName $argsName $formattedKeyFlagName $envVarName) -}}
                {{- $lines = append $lines (printf "fi") -}}
            {{- end -}}
        {{- else if hasKey $param "list" -}}
            {{- $lines = append $lines (printf "if [ -n \"$%s\" ]; then" $envVarName) -}}
            {{- $lines = append $lines (printf "  LIST_ITEMS=$(echo \"$%s\" | yq eval '.[ ]' - | xargs -I{} echo -n \"{} \")" $envVarName $envVarName) -}}
            {{- if not (eq "" $formattedKeyFlagName) -}}
                {{- $lines = append $lines (printf "  export %s=\"$%s %s $LIST_ITEMS\"" $argsName $argsName $formattedKeyFlagName) -}}
            {{- end -}}
            {{- $lines = append $lines (printf "fi") -}}
        {{- else if hasKey $param "present" -}}
            {{- $lines = append $lines (printf "if [ \"$%s\" = \"true\" ] || [ \"$%s\" = \"1\" ]; then" $envVarName $envVarName) -}}
            {{- if eq "" $formattedKeyFlagName -}}
              {{ fail (printf "Got key %s as a boolean type ('present') and it is also specified as positional" $key) }}
            {{- end -}}
            {{- $lines = append $lines (printf "  export %s=\"$%s %s\"" $argsName $argsName $formattedKeyFlagName) -}}
            {{- $lines = append $lines (printf "fi") -}}
        {{- else -}}
            {{ fail (printf "Key %s did no specify 'value', 'list', or 'present' binding" $key) }}
        {{- end -}}
    {{- end -}}

    {{- if len $positionalMap -}}
        {{- $orderedArgs := "" }}
        {{- range $i := until (len $positionalMap) }}
            {{- $orderedArgs = printf "%s $%s" $orderedArgs (get $positionalMap (toString $i)) -}}
        {{- end -}}
        {{- $lines = append $lines (printf "export %s=\"%s $%s\"" $argsName $orderedArgs $argsName) -}}
    {{- end }}
    {{- join "\n" $lines -}}
{{- end -}}
