#!/bin/bash

set -e  # Exit on any error

# Create a temporary file
TEMPORARY_FILE=$(mktemp)

# Ensure cleanup on exit
trap "rm -f $TEMPORARY_FILE" EXIT

UUID=$(uuidgen)
echo "Generated unique prefix: $UUID"

CONFIG_FILENAME=../../sampleMigration.yaml

echo "Running configuration conversion..."
npm run dev-initialize --silent -- --user-config $CONFIG_FILENAME --prefix $UUID > "$TEMPORARY_FILE"

echo "Applying workflow to Kubernetes..."
cat <<EOF | kubectl create -f -
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  generateName: full-migration-
spec:
  workflowTemplateRef:
    name: full-migration
  entrypoint: main
  arguments:
    parameters:
      - name: latchCoordinationPrefix
        value: "$UUID"
      - name: migrationConfigs
        value: |
$(sed 's/^/          /' "$TEMPORARY_FILE")
EOF



echo "Done! Workflow submitted successfully."