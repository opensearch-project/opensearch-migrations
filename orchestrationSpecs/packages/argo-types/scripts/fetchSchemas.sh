#!/bin/bash
set -e

OUTPUT_DIR="${1:-./k8sSchemas}"
mkdir -p "$OUTPUT_DIR"

echo "Fetching Argo Workflows CRD schemas from cluster..."
kubectl get --raw /openapi/v3/apis/argoproj.io/v1alpha1 > "$OUTPUT_DIR/argoproj.io-v1alpha1-schema.json"

echo "Done. Schemas saved to $OUTPUT_DIR/"
