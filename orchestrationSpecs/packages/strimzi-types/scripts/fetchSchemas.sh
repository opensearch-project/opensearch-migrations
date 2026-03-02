#!/bin/bash
set -e

OUTPUT_DIR="${1:-./k8sSchemas}"
mkdir -p "$OUTPUT_DIR"

echo "Fetching Strimzi CRD schemas from cluster..."
kubectl get --raw /openapi/v3/apis/kafka.strimzi.io/v1 > "$OUTPUT_DIR/kafka.strimzi.io-v1-schema.json"

VERSION=$(kubectl get crd kafkas.kafka.strimzi.io -o jsonpath='{.metadata.labels.app\.kubernetes\.io/version}')
echo "{\"version\": \"$VERSION\"}" > "$OUTPUT_DIR/version.json"

echo "Done. Schemas saved to $OUTPUT_DIR/ (Strimzi $VERSION)"
