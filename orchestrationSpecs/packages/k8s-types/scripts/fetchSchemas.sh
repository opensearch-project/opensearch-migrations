#!/bin/bash
set -e

OUTPUT_DIR="${1:-./k8sSchemas}"
mkdir -p "$OUTPUT_DIR"

echo "Fetching core Kubernetes schemas from cluster..."
kubectl get --raw /openapi/v3/api/v1 > "$OUTPUT_DIR/core-v1-schema.json"
kubectl get --raw /openapi/v3/apis/apps/v1 > "$OUTPUT_DIR/apps-v1-schema.json"
kubectl get --raw /openapi/v3/apis/batch/v1 > "$OUTPUT_DIR/batch-v1-schema.json"

VERSION=$(kubectl version -o json | python3 -c "import sys,json; print(json.load(sys.stdin)['serverVersion']['gitVersion'])")
echo "{\"version\": \"$VERSION\"}" > "$OUTPUT_DIR/version.json"

echo "Done. Schemas saved to $OUTPUT_DIR/ (K8s $VERSION)"
