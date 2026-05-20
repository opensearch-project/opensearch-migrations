#!/usr/bin/env bash

set -e -x

: "${CONFIG_CONTENTS_BASE64:?}"
: "${MIGRATION_CONSOLE_COMMAND:?}"

# Save pod name to output path
echo $HOSTNAME > /tmp/podname

echo File contents...
 
printf '%s' "$CONFIG_CONTENTS_BASE64" | base64 -d > /config/migration_services.yaml_

cat /config/migration_services.yaml_ |
jq -f workflowConfigToServicesConfig.jq > /config/migration_services.yaml

echo file dump
echo ---
export MIGRATION_USE_SERVICES_YAML_CONFIG=true
cat /config/migration_services.yaml
echo ---

eval "$MIGRATION_CONSOLE_COMMAND"
