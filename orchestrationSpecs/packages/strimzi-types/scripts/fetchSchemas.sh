#!/bin/bash
set -e

OUTPUT_DIR="${1:-./k8sSchemas}"
mkdir -p "$OUTPUT_DIR"

echo "Fetching Strimzi CRD schemas from cluster..."
kubectl get --raw /openapi/v3/apis/kafka.strimzi.io/v1beta2 > "$OUTPUT_DIR/kafka.strimzi.io-v1beta2-schema.json"

echo "Done. Schemas saved to $OUTPUT_DIR/"
