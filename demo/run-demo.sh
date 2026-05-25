#!/usr/bin/env bash
# Demo: Migrate Elasticsearch 7.10 → OpenSearch 3.6 using Migration Assistant
#
# This demo uses the full Kubernetes workflow orchestration (Argo Workflows).
# It deploys Migration Assistant on a local kind cluster and runs the real
# workflow CLI commands from inside the migration-console pod.
#
# Prerequisites:
#   - Docker
#   - kind, kubectl, helm (installed automatically by the script if missing)
#
# What this script does:
#   1. Creates a kind Kubernetes cluster
#   2. Builds and deploys Migration Assistant (+ test source/target clusters)
#   3. Seeds sample data into the source Elasticsearch 7.10 cluster
#   4. Runs the migration workflow (snapshot → metadata → document backfill)
#   5. Verifies migrated data on the target OpenSearch 3.6 cluster
#
# Usage:
#   bash demo/run-demo.sh
#
# To just access the migration console interactively after setup:
#   kubectl -n ma exec -it migration-console-0 -- /bin/bash

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${DEMO_DIR}/.." && pwd)"

echo "=== OpenSearch Migration Assistant Demo ==="
echo "Migrating Elasticsearch 7.10 → OpenSearch 3.6 on Kubernetes"
echo ""

# --- Step 0: Deploy the full stack on a local kind cluster ---
echo "Deploying Migration Assistant on a local kind cluster..."
echo "(This builds images and deploys the Helm chart — may take 10-20 minutes on first run)"
echo ""
bash "${REPO_ROOT}/deployment/k8s/kindTesting.sh"

# --- Step 1: Create S3 bucket in LocalStack ---
echo ""
echo "Creating S3 bucket for snapshots..."
kubectl -n ma exec migration-console-0 -- /bin/sh -c \
  'AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test aws --endpoint-url=http://localstack:4566 --region us-east-2 s3 mb s3://migration-artifacts'

# --- Step 2: Seed sample data into the source cluster ---
echo ""
echo "Seeding sample data into source Elasticsearch cluster..."
kubectl -n ma exec migration-console-0 -- /bin/sh -c '
curl -sk -u "admin:admin" -X PUT "https://elasticsearch-source:9200/ecommerce" -H "Content-Type: application/json" -d "{
  \"settings\": {\"number_of_shards\": 1, \"number_of_replicas\": 0},
  \"mappings\": {\"properties\": {
    \"product_name\": {\"type\": \"text\"}, \"price\": {\"type\": \"float\"},
    \"category\": {\"type\": \"keyword\"}, \"in_stock\": {\"type\": \"boolean\"},
    \"timestamp\": {\"type\": \"date\"}
  }}
}"'

kubectl -n ma exec migration-console-0 -- /bin/sh -c '
curl -sk -u "admin:admin" -X POST "https://elasticsearch-source:9200/_bulk?refresh=true" -H "Content-Type: application/x-ndjson" -d "
{\"index\":{\"_index\":\"ecommerce\"}}
{\"product_name\":\"Wireless Headphones\",\"price\":79.99,\"category\":\"electronics\",\"in_stock\":true,\"timestamp\":\"2024-01-15T10:30:00Z\"}
{\"index\":{\"_index\":\"ecommerce\"}}
{\"product_name\":\"Running Shoes\",\"price\":129.99,\"category\":\"sports\",\"in_stock\":true,\"timestamp\":\"2024-01-16T14:20:00Z\"}
{\"index\":{\"_index\":\"ecommerce\"}}
{\"product_name\":\"Coffee Maker\",\"price\":49.99,\"category\":\"kitchen\",\"in_stock\":false,\"timestamp\":\"2024-01-17T09:15:00Z\"}
{\"index\":{\"_index\":\"ecommerce\"}}
{\"product_name\":\"Laptop Stand\",\"price\":35.00,\"category\":\"electronics\",\"in_stock\":true,\"timestamp\":\"2024-01-18T11:45:00Z\"}
{\"index\":{\"_index\":\"ecommerce\"}}
{\"product_name\":\"Yoga Mat\",\"price\":25.99,\"category\":\"sports\",\"in_stock\":true,\"timestamp\":\"2024-01-19T08:00:00Z\"}
"'

kubectl -n ma exec migration-console-0 -- /bin/sh -c '
curl -sk -u "admin:admin" -X PUT "https://elasticsearch-source:9200/_template/logs_template" -H "Content-Type: application/json" -d "{
  \"index_patterns\": [\"logs-*\"],
  \"settings\": {\"number_of_shards\": 1, \"number_of_replicas\": 0},
  \"mappings\": {\"properties\": {\"message\": {\"type\": \"text\"}, \"level\": {\"type\": \"keyword\"}, \"timestamp\": {\"type\": \"date\"}}}
}"'

kubectl -n ma exec migration-console-0 -- /bin/sh -c '
curl -sk -u "admin:admin" -X POST "https://elasticsearch-source:9200/_bulk?refresh=true" -H "Content-Type: application/x-ndjson" -d "
{\"index\":{\"_index\":\"logs-2024.01\"}}
{\"message\":\"Application started successfully\",\"level\":\"INFO\",\"timestamp\":\"2024-01-15T00:00:01Z\"}
{\"index\":{\"_index\":\"logs-2024.01\"}}
{\"message\":\"Connection timeout to database\",\"level\":\"ERROR\",\"timestamp\":\"2024-01-15T00:05:23Z\"}
{\"index\":{\"_index\":\"logs-2024.01\"}}
{\"message\":\"Request processed in 150ms\",\"level\":\"INFO\",\"timestamp\":\"2024-01-15T00:10:45Z\"}
"'
echo "Done seeding data."

# --- Step 3: Run the migration using workflow CLI ---
echo ""
echo "=== Starting Migration ==="
echo ""

echo '$ console clusters connection-check'
kubectl -n ma exec migration-console-0 -- console clusters connection-check
echo ""

echo '$ console clusters cat-indices'
kubectl -n ma exec migration-console-0 -- console clusters cat-indices
echo ""

echo '$ workflow configure sample --load'
kubectl -n ma exec migration-console-0 -- workflow configure sample --load
echo ""

echo '$ workflow configure edit --stdin < config.json'
kubectl -n ma exec -i migration-console-0 -- workflow configure edit --stdin <<'EOF'
{
"skipApprovals": true,
"sourceClusters": {
    "source": {
        "endpoint": "https://elasticsearch-source:9200",
        "allowInsecure": true,
        "version": "ES 7.10.2",
        "authConfig": { "basic": { "secretName": "elasticsearch-source-credentials" } },
        "snapshotInfo": {
            "repos": { "s3-repo": {
                "awsRegion": "us-east-2",
                "endpoint": "localstack://localstack:4566",
                "s3RepoPathUri": "s3://migration-artifacts/snapshots"
            }},
            "snapshots": { "snap1": {
                "repoName": "s3-repo",
                "config": { "createSnapshotConfig": {} }
            }}
        }
    }
},
"targetClusters": {
    "target": {
        "endpoint": "https://opensearch-cluster-target:9200",
        "allowInsecure": true,
        "authConfig": { "basic": { "secretName": "target-cluster-credentials" } }
    }
},
"snapshotMigrationConfigs": [{
    "fromSource": "source", "toTarget": "target",
    "perSnapshotConfig": { "snap1": [{
        "metadataMigrationConfig": {},
        "documentBackfillConfig": {}
    }]}
}]
}
EOF
echo ""

echo '$ workflow configure credentials create'
echo "admin:admin" | kubectl -n ma exec -i migration-console-0 -- workflow configure credentials create elasticsearch-source-credentials --stdin
echo "admin:admin" | kubectl -n ma exec -i migration-console-0 -- workflow configure credentials create target-cluster-credentials --stdin
echo ""

echo '$ workflow submit'
kubectl -n ma exec migration-console-0 -- workflow submit
echo ""

echo "Waiting for workflow to complete..."
kubectl -n ma exec migration-console-0 -- workflow submit --wait --timeout 600
echo ""

echo '$ workflow status'
kubectl -n ma exec migration-console-0 -- workflow status
echo ""

# --- Step 4: Verify migration results ---
echo "=== Verification ==="
echo ""

echo '$ console clusters cat-indices --refresh'
kubectl -n ma exec migration-console-0 -- console clusters cat-indices --refresh
echo ""

echo '$ console clusters curl target "/ecommerce/_search?size=2&pretty" ...'
kubectl -n ma exec migration-console-0 -- console clusters curl target "/ecommerce/_search?size=2&pretty" -X POST --json '{"query":{"term":{"category":"electronics"}}}'
echo ""

echo "=== Migration Complete ==="
echo "All indices and documents migrated from ES 7.10 to OpenSearch 3.6."
