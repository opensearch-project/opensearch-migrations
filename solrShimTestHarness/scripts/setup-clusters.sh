#!/usr/bin/env bash
# Waits for Solr and OpenSearch health checks, creates OpenSearch index with mapping,
# verifies Solr collection exists, then bulk loads dataset into both clusters via load_data.py.
#
# Usage:
#   ./scripts/setup-clusters.sh \
#     --solr-url http://localhost:18983 \
#     --opensearch-url http://localhost:19200 \
#     --dataset data/dataset.json \
#     --opensearch-mapping config/opensearch/index-mapping.json

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Defaults
SOLR_URL="http://localhost:8983"
OPENSEARCH_URL="http://localhost:9200"
DATASET="${PROJECT_DIR}/data/dataset.json"
OPENSEARCH_MAPPING="${PROJECT_DIR}/config/opensearch/index-mapping.json"
COLLECTION="testharness"
INDEX="testharness"
HEALTH_TIMEOUT=120
BATCH_SIZE=500

while [[ $# -gt 0 ]]; do
  case "$1" in
    --solr-url)           SOLR_URL="$2"; shift 2 ;;
    --opensearch-url)     OPENSEARCH_URL="$2"; shift 2 ;;
    --dataset)            DATASET="$2"; shift 2 ;;
    --opensearch-mapping) OPENSEARCH_MAPPING="$2"; shift 2 ;;
    --timeout)            HEALTH_TIMEOUT="$2"; shift 2 ;;
    --batch-size)         BATCH_SIZE="$2"; shift 2 ;;
    *) echo "ERROR: Unknown argument: $1" >&2; exit 1 ;;
  esac
done

echo "=== Cluster Setup ==="
echo "Solr:       $SOLR_URL"
echo "OpenSearch: $OPENSEARCH_URL"
echo "Dataset:    $DATASET"
echo ""

if [[ ! -f "$DATASET" ]]; then
  echo "ERROR: Dataset file not found: $DATASET" >&2
  exit 1
fi
if [[ ! -f "$OPENSEARCH_MAPPING" ]]; then
  echo "ERROR: OpenSearch mapping not found: $OPENSEARCH_MAPPING" >&2
  exit 1
fi

# --- Wait for health checks ---
wait_for_service() {
  local name="$1"
  local url="$2"
  local elapsed=0

  echo "Waiting for $name to be healthy..."
  while [[ $elapsed -lt $HEALTH_TIMEOUT ]]; do
    if curl -sf "$url" > /dev/null 2>&1; then
      echo "$name is healthy."
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done

  echo "ERROR: $name did not become healthy within ${HEALTH_TIMEOUT}s" >&2
  exit 1
}

wait_for_service "Solr" "${SOLR_URL}/solr/admin/info/system"
wait_for_service "OpenSearch" "${OPENSEARCH_URL}/_cluster/health"

# --- Create OpenSearch index with mapping ---
echo ""
echo "Creating OpenSearch index '${INDEX}'..."
curl -sf -X DELETE "${OPENSEARCH_URL}/${INDEX}" > /dev/null 2>&1 || true

HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" -X PUT "${OPENSEARCH_URL}/${INDEX}" \
  -H "Content-Type: application/json" \
  -d @"${OPENSEARCH_MAPPING}")

if [[ "$HTTP_CODE" != "200" ]]; then
  echo "ERROR: Failed to create OpenSearch index (HTTP $HTTP_CODE)" >&2
  exit 1
fi
echo "OpenSearch index '${INDEX}' created."

# --- Verify Solr collection exists ---
echo ""
echo "Verifying Solr collection '${COLLECTION}'..."
if ! curl -sf "${SOLR_URL}/solr/${COLLECTION}/admin/ping" > /dev/null 2>&1; then
  echo "ERROR: Solr collection '${COLLECTION}' not available." >&2
  exit 1
fi
echo "Solr collection '${COLLECTION}' is ready."

# --- Bulk load data ---
echo ""
echo "Loading data into both clusters..."
python3 "${SCRIPT_DIR}/load_data.py" \
  --dataset "$DATASET" \
  --solr-url "$SOLR_URL" \
  --opensearch-url "$OPENSEARCH_URL" \
  --collection "$COLLECTION" \
  --index "$INDEX" \
  --batch-size "$BATCH_SIZE"
