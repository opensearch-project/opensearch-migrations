set -e -x

# Activate the Python virtual environment to get access to workflow CLI
. /etc/profile.d/venv.sh
source /.venv/bin/activate

echo "Building and submitting migration workflow..."

# Decode base64 migration config from environment variable and write to file
echo "$MIGRATION_CONFIG_BASE64" | base64 -d > /tmp/migration_config.json

echo "Migration config contents:"
cat /tmp/migration_config.json

echo "Loading configuration from JSON..."
cat /tmp/migration_config.json | workflow configure edit --stdin

# Submit workflow
echo "Submitting workflow..."
WORKFLOW_OUTPUT=$(workflow submit 2>&1)
echo "Workflow submit output: $WORKFLOW_OUTPUT"
