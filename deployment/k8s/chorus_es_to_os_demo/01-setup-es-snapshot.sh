#!/usr/bin/env bash
# =============================================================================
# 01-setup-es-snapshot.sh
#
# One-time setup: connects the local ES Docker container to the kind network
# so it can reach LocalStack S3, installs AWS credentials into the ES keystore,
# registers an S3 snapshot repository, and takes a snapshot of the ecommerce index.
#
# Run this ONCE before submitting the Argo workflow.
# Safe to re-run — snapshot creation is idempotent.
# =============================================================================
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
ES_URL="http://localhost:9200"
ES_USER="elastic"
ES_PASS="ElasticRocks"
SNAPSHOT_REPO="migrations_repo"
SNAPSHOT_NAME="ecommerce_snapshot"
S3_BUCKET="migrations-default-123456789012-dev-us-east-2"
LOCALSTACK_NODEPORT=31566

# ── Find ES Docker container ──────────────────────────────────────────────────
ES_CONTAINER=$(docker ps --filter "publish=9200" --format "{{.ID}}" | head -1)
if [ -z "$ES_CONTAINER" ]; then
  echo "ERROR: No Docker container found exposing port 9200. Is Elasticsearch running?"
  exit 1
fi
echo "Found ES container: $ES_CONTAINER"

# ── Connect ES container to kind network (if not already) ────────────────────
if docker inspect "$ES_CONTAINER" | python3 -c "import sys,json; nets=json.load(sys.stdin)[0]['NetworkSettings']['Networks']; sys.exit(0 if 'kind' in nets else 1)" 2>/dev/null; then
  echo "ES container already on kind network."
else
  echo "Connecting ES container to kind network..."
  docker network connect kind "$ES_CONTAINER"
  echo "Connected. ES container now has access to LocalStack via kind network."
fi

# Find a kind node IP to reach LocalStack via NodePort
LOCALSTACK_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null)
LOCALSTACK_ENDPOINT="http://${LOCALSTACK_IP}:${LOCALSTACK_NODEPORT}"
echo "LocalStack endpoint for ES: $LOCALSTACK_ENDPOINT"

# ── Install AWS credentials into ES keystore ──────────────────────────────────
# The repository-s3 plugin requires credentials even for LocalStack.
echo "Installing AWS credentials into ES keystore..."
echo "test" | docker exec -i "$ES_CONTAINER" \
  /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin s3.client.default.access_key --force 2>&1 || true
echo "test" | docker exec -i "$ES_CONTAINER" \
  /usr/share/elasticsearch/bin/elasticsearch-keystore add --stdin s3.client.default.secret_key --force 2>&1 || true

echo "Reloading secure settings..."
curl -s -u "$ES_USER:$ES_PASS" -X POST "$ES_URL/_nodes/reload_secure_settings" | python3 -m json.tool 2>/dev/null || true

# ── Register S3 snapshot repository ──────────────────────────────────────────
echo ""
echo "Registering S3 snapshot repository '$SNAPSHOT_REPO'..."
RESULT=$(curl -s -u "$ES_USER:$ES_PASS" -X PUT "$ES_URL/_snapshot/$SNAPSHOT_REPO" \
  -H "Content-Type: application/json" \
  -d "{
    \"type\": \"s3\",
    \"settings\": {
      \"bucket\": \"$S3_BUCKET\",
      \"endpoint\": \"$LOCALSTACK_ENDPOINT\",
      \"path_style_access\": true,
      \"protocol\": \"http\"
    }
  }")
echo "$RESULT"
if echo "$RESULT" | grep -q '"acknowledged":true'; then
  echo "Repository registered successfully."
else
  echo "WARNING: Repository registration may have failed. Check the output above."
fi

# ── Take snapshot ─────────────────────────────────────────────────────────────
echo ""
echo "Taking snapshot '$SNAPSHOT_NAME' of the ecommerce index..."
RESULT=$(curl -s -u "$ES_USER:$ES_PASS" -X PUT "$ES_URL/_snapshot/$SNAPSHOT_REPO/$SNAPSHOT_NAME?wait_for_completion=true" \
  -H "Content-Type: application/json" \
  -d '{"indices": "ecommerce", "include_global_state": false}')
echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

STATE=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('snapshot',{}).get('state','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
if [ "$STATE" = "SUCCESS" ]; then
  echo ""
  echo "✓ Snapshot '$SNAPSHOT_NAME' created successfully."
else
  # Check if snapshot already exists
  CHECK=$(curl -s -u "$ES_USER:$ES_PASS" "$ES_URL/_snapshot/$SNAPSHOT_REPO/$SNAPSHOT_NAME")
  EXISTING_STATE=$(echo "$CHECK" | python3 -c "import sys,json; snaps=json.load(sys.stdin).get('snapshots',[]); print(snaps[0]['state'] if snaps else 'NONE')" 2>/dev/null || echo "NONE")
  if [ "$EXISTING_STATE" = "SUCCESS" ]; then
    echo "✓ Snapshot '$SNAPSHOT_NAME' already exists and is SUCCESS — nothing to do."
  else
    echo "ERROR: Snapshot state is '$STATE'. Check ES logs."
    exit 1
  fi
fi
