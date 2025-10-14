#!/bin/bash

set -e  # Exit on any error

# Create a temporary file
TEMPORARY_FILE=$(mktemp)

# Ensure cleanup on exit
trap "rm -f $TEMPORARY_FILE" EXIT

echo "Running configuration conversion..."
npm run dev-transform-config --silent -- ../../sampleMigration.yaml > "$TEMPORARY_FILE"

UUID=$(uuidgen)
echo "Generated unique prefix: $UUID"

echo "Running initialize..."
npm run dev-initialize-workflow-state -- --prefix $UUID "$TEMPORARY_FILE"

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