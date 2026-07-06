#!/bin/bash
# Test script to reproduce and validate large bulk request handling
# through the capture proxy → Kafka → replayer pipeline.
#
# Usage:
#   1. Build images:  ./gradlew jibDockerBuild (from repo root)
#   2. Start stack:   docker compose -f docker-compose.large-requests.yml up -d
#   3. Wait ~15s for services to be ready
#   4. Run this test: bash test-large-requests.sh
#
# The test generates a ~5MB _bulk request (similar to production 50MB but smaller
# to keep local test fast). It validates:
#   - Capture proxy doesn't drop records (no RecordTooLargeException)
#   - Replayer successfully reassembles fragments and indexes to target
#   - Target cluster contains the expected documents

set -e

PROXY_URL="https://localhost:9200"
TARGET_URL="https://localhost:29200"
SOURCE_URL="https://localhost:19200"
CURL_OPTS="-k -s"
INDEX_NAME="test-large-bulk"
NUM_DOCS=2000  # ~5MB bulk payload (each doc ~2.5KB)

echo "=== Large Request Replay Test ==="
echo ""

# Wait for services
echo "[1/6] Waiting for services to be ready..."
for i in $(seq 1 30); do
    if curl $CURL_OPTS -o /dev/null -w "%{http_code}" "$PROXY_URL" 2>/dev/null | grep -q "401\|200"; then
        break
    fi
    sleep 1
done

if ! curl $CURL_OPTS -o /dev/null "$PROXY_URL" 2>/dev/null; then
    echo "ERROR: Capture proxy not ready after 30s"
    exit 1
fi
echo "  Services ready."

# Generate bulk payload
echo "[2/6] Generating ~5MB bulk request ($NUM_DOCS documents)..."
BULK_FILE="/tmp/test-bulk-payload.ndjson"
rm -f "$BULK_FILE"

for i in $(seq 1 $NUM_DOCS); do
    echo "{\"index\":{\"_index\":\"$INDEX_NAME\",\"_id\":\"doc-$i\"}}" >> "$BULK_FILE"
    # Each doc ~2.5KB with a large text field
    python3 -c "
import json
doc = {
    'title': f'Document {$i}',
    'description': 'x' * 2000,
    'timestamp': '2026-06-28T00:00:00Z',
    'category': 'large-bulk-test',
    'sequence': $i
}
print(json.dumps(doc))
" >> "$BULK_FILE"
done

PAYLOAD_SIZE=$(wc -c < "$BULK_FILE" | tr -d ' ')
echo "  Payload size: ${PAYLOAD_SIZE} bytes (~$((PAYLOAD_SIZE / 1024 / 1024))MB)"

# Send bulk request through capture proxy
echo "[3/6] Sending bulk request through capture proxy..."
RESPONSE=$(curl $CURL_OPTS -u admin:admin -X POST \
    "$PROXY_URL/_bulk" \
    -H "Content-Type: application/x-ndjson" \
    --data-binary "@$BULK_FILE" \
    -w "\n%{http_code}" 2>&1)

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')

if [ "$HTTP_CODE" = "200" ]; then
    ERRORS=$(echo "$BODY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('errors', 'unknown'))" 2>/dev/null || echo "parse_error")
    echo "  Source response: HTTP $HTTP_CODE, errors=$ERRORS"
else
    echo "  ERROR: Source returned HTTP $HTTP_CODE"
    echo "  Body: $(echo "$BODY" | head -5)"
    exit 1
fi

# Verify fragments are co-located on same partition
echo "[4/7] Verifying fragment partition locality..."
PARTITION_CHECK=$(docker compose -f docker-compose.large-requests.yml exec -T kafka \
    /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic logging-traffic-topic \
    --from-beginning \
    --timeout-ms 5000 \
    --property print.key=true \
    --property print.partition=true \
    --property print.value=false 2>/dev/null | head -100)

if [ -n "$PARTITION_CHECK" ]; then
    # Check if same key always maps to same partition
    UNIQUE_KEY_PARTITIONS=$(echo "$PARTITION_CHECK" | awk -F'\t' '{print $2 ":" $1}' | sort -u | awk -F: '{print $1}' | sort | uniq -c | sort -rn | head -5)
    echo "  Top keys by record count (key should map to single partition):"
    echo "$UNIQUE_KEY_PARTITIONS" | head -5 | while read count key; do
        PARTITIONS=$(echo "$PARTITION_CHECK" | awk -F'\t' -v k="$key" '$2==k {print $1}' | sort -u | tr '\n' ',')
        echo "    Key=$key: $count records -> partitions: $PARTITIONS"
    done

    # Verify: each unique key should map to exactly 1 partition
    MULTI_PARTITION_KEYS=$(echo "$PARTITION_CHECK" | awk -F'\t' '{print $2 "\t" $1}' | sort -u | awk -F'\t' '{print $1}' | sort | uniq -c | awk '$1 > 1 {print $2}')
    if [ -z "$MULTI_PARTITION_KEYS" ]; then
        echo "  PASS: All record keys map to a single partition (fragments co-located)"
    else
        echo "  FAIL: Some keys span multiple partitions:"
        echo "$MULTI_PARTITION_KEYS" | head -5
    fi
else
    echo "  SKIP: Could not read topic records (consumer timeout)"
fi

# Verify source has the documents
echo "[5/7] Verifying source cluster has documents..."
sleep 2
SOURCE_COUNT=$(curl $CURL_OPTS -u admin:admin "$SOURCE_URL/$INDEX_NAME/_count" | python3 -c "import json,sys; print(json.load(sys.stdin)['count'])" 2>/dev/null || echo "0")
echo "  Source document count: $SOURCE_COUNT"

# Wait for replayer to process
echo "[6/7] Waiting for replayer to process (up to 60s)..."
TARGET_COUNT=0
for i in $(seq 1 60); do
    TARGET_COUNT=$(curl $CURL_OPTS -u admin:myStrongPassword123! "$TARGET_URL/$INDEX_NAME/_count" 2>/dev/null | python3 -c "import json,sys; print(json.load(sys.stdin)['count'])" 2>/dev/null || echo "0")
    if [ "$TARGET_COUNT" = "$NUM_DOCS" ]; then
        break
    fi
    sleep 1
done

# Results
echo "[7/7] Results:"
echo "  Source documents: $SOURCE_COUNT"
echo "  Target documents: $TARGET_COUNT"
echo "  Expected:         $NUM_DOCS"
echo ""

if [ "$TARGET_COUNT" = "$NUM_DOCS" ]; then
    echo "SUCCESS: All $NUM_DOCS documents replayed to target!"
    echo ""
    echo "This confirms:"
    echo "  - Capture proxy: max.request.size=8MB prevents RecordTooLargeException"
    echo "  - Kafka broker: message.max.bytes=8MB accepts large records"
    echo "  - Replayer: max.partition.fetch.bytes=8MB fetches large records"
    echo "  - Accumulator: reassembles fragments correctly"
    exit 0
elif [ "$TARGET_COUNT" -gt "0" ]; then
    echo "PARTIAL: Only $TARGET_COUNT/$NUM_DOCS documents made it to target."
    echo "  Some records were likely dropped (RecordTooLargeException) or"
    echo "  the replayer is still processing. Check replayer logs:"
    echo "  docker compose -f docker-compose.large-requests.yml logs replayer | tail -50"
    exit 1
else
    echo "FAILURE: No documents reached the target."
    echo "  Check replayer logs:"
    echo "  docker compose -f docker-compose.large-requests.yml logs replayer | tail -50"
    echo "  Check proxy logs for RecordTooLargeException:"
    echo "  docker compose -f docker-compose.large-requests.yml logs capture-proxy | grep -i 'RecordTooLarge\|ERROR'"
    exit 1
fi
