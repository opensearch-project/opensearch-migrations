set -e -x

echo "Building and submitting migration workflow..."

# Decode base64 migration config and write to file
base64 -d > /tmp/migration_config.json << EOF
{{MIGRATION_CONFIG_BASE64}}
EOF

echo "Migration config contents:"
cat /tmp/migration_config.json

echo "Loading configuration from JSON..."
cat /tmp/migration_config.json | workflow configure edit --stdin

# Submit workflow
echo "Submitting workflow..."
WORKFLOW_OUTPUT=$(workflow submit 2>&1)
echo "Workflow submit output: $WORKFLOW_OUTPUT"
