#!/usr/bin/env bash
set -euo pipefail

SOLR_URL="${SOLR_URL:?SOLR_URL is required}"
OPENSEARCH_URL="${OPENSEARCH_URL:?OPENSEARCH_URL is required}"
COLLECTION="${COLLECTION:-testharness}"
INDEX="${INDEX:-testharness}"
BATCH_SIZE="${BATCH_SIZE:-500}"
MAX_DOCS="${MAX_DOCS:-200000}"
DATASET_PATH="/tmp/dataset.json"

echo "=== Data Loader ==="
echo "  Solr:       ${SOLR_URL}"
echo "  OpenSearch: ${OPENSEARCH_URL}"
echo "  Collection: ${COLLECTION}"
echo "  Index:      ${INDEX}"

# Wait for Solr to be ready
echo "Waiting for Solr..."
until curl -sf "${SOLR_URL}/solr/admin/info/system" > /dev/null 2>&1; do
  sleep 3
done
echo "  Solr is ready."

# Wait for OpenSearch to be ready
echo "Waiting for OpenSearch..."
until curl -sfk "${OPENSEARCH_URL}/_cluster/health" > /dev/null 2>&1; do
  sleep 3
done
echo "  OpenSearch is ready."

# Generate dataset
echo "Generating dataset (${MAX_DOCS} docs)..."
python3 /app/generate_dataset.py --output "${DATASET_PATH}" --max-docs "${MAX_DOCS}"

# Create Solr collection
echo "Creating Solr collection '${COLLECTION}'..."
curl -sf "${SOLR_URL}/solr/admin/collections?action=CREATE&name=${COLLECTION}&numShards=1&replicationFactor=1" || true

# Create OpenSearch index with mapping
echo "Creating OpenSearch index '${INDEX}'..."
curl -sfk -X PUT "${OPENSEARCH_URL}/${INDEX}" \
  -H "Content-Type: application/json" \
  -d @/app/index-mapping.json || true

# Load data
echo "Loading data..."
python3 /app/load_data.py \
  --dataset "${DATASET_PATH}" \
  --solr-url "${SOLR_URL}" \
  --opensearch-url "${OPENSEARCH_URL}" \
  --collection "${COLLECTION}" \
  --index "${INDEX}" \
  --batch-size "${BATCH_SIZE}"

echo "=== Data loading complete ==="
