#!/usr/bin/env bash
# =============================================================================
# 04-cdc-demo.sh
#
# Demonstrates live CDC (Change Data Capture) traffic capture and replay.
#
# Sends 5 requests all using the `supplier` keyword field:
#   1. SEARCH   — count Samsung products
#   2. AGGREGATE — top suppliers by product count
#   3. DELETE   — remove one Samsung product via CaptureProxy
#   4. SEARCH   — recount Samsung (should be -1 on both ES and OS)
#   5. AGGREGATE — top suppliers again (Samsung count decreases on both)
#
# Each request is sent TWICE:
#   a) Directly to Elasticsearch (port 9200) — ground truth
#   b) Through the CaptureProxy (https://localhost:9201) — forwarded to ES
#      AND captured to Kafka for replay to OpenSearch
#
# After the DELETE, the script waits for the replayer, then checks OpenSearch
# directly to confirm the delete propagated.
#
# PREREQUISITE: CaptureProxy port-forward must be running in another terminal:
#   kubectl port-forward -n ma svc/capture-proxy 9201:9201
# =============================================================================
set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
ES_URL="http://localhost:9200"
ES_USER="elastic"
ES_PASS="ElasticRocks"

PROXY_URL="https://localhost:9201"   # CaptureProxy (TLS, -sk to skip cert)

OS_URL="https://chorus-opensearch-edition.dev.o19s.com:9200"
OS_USER="admin"
OS_PASS='MyStr0ng!P@ssw0rd2024'

INDEX="ecommerce"
FIELD="supplier"
TERM="Samsung"
REPLAY_WAIT=120  # max seconds to wait for the replayer to propagate changes

# ── Helpers ───────────────────────────────────────────────────────────────────
es()    { curl -s -u "${ES_USER}:${ES_PASS}"     "$@"; }
proxy() { curl -sk -u "${ES_USER}:${ES_PASS}"    "$@"; }  # through CaptureProxy
os()    { curl -sk -u "${OS_USER}:${OS_PASS}"    "$@"; }

section() { echo ""; echo "══════════════════════════════════════════════════════"; echo "  $*"; echo "══════════════════════════════════════════════════════"; }
subsection() { echo ""; echo "  ── $* ──"; }
pretty() { python3 -m json.tool 2>/dev/null || cat; }

# ── Check pre-requisite ───────────────────────────────────────────────────────
echo "Checking CaptureProxy port-forward..."
if ! curl -sk --connect-timeout 3 "${PROXY_URL}" >/dev/null 2>&1; then
  echo ""
  echo "ERROR: Cannot reach CaptureProxy at ${PROXY_URL}"
  echo "Start the port-forward first:"
  echo "  kubectl port-forward -n ma svc/capture-proxy 9201:9201"
  exit 1
fi
echo "CaptureProxy is reachable."

# ── Pick a doc to delete ──────────────────────────────────────────────────────
DELETE_ID=$(es "${ES_URL}/${INDEX}/_search?size=1&q=${FIELD}:${TERM}&_source=false" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['hits']['hits'][0]['_id'])")
DELETE_TITLE=$(es "${ES_URL}/${INDEX}/_doc/${DELETE_ID}?_source=title" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['_source']['title'])")

echo ""
echo "Will delete:  [${DELETE_ID}] ${DELETE_TITLE}"
echo ""

# ── Queries ───────────────────────────────────────────────────────────────────
SEARCH_BODY=$(cat <<JSON
{
  "query": { "term": { "${FIELD}": "${TERM}" } },
  "size": 0,
  "track_total_hits": true
}
JSON
)

AGG_BODY=$(cat <<JSON
{
  "size": 0,
  "aggs": {
    "samsung_count": {
      "filter": { "term": { "${FIELD}": "${TERM}" } },
      "aggs": {
        "product_count": { "value_count": { "field": "${FIELD}" } }
      }
    },
    "top_suppliers": {
      "terms": { "field": "${FIELD}", "size": 5, "order": { "_count": "desc" } }
    }
  }
}
JSON
)

count_from_search() { python3 -c "import sys,json; print('  Count:', json.load(sys.stdin)['hits']['total']['value'], '${TERM} products')"; }
top_suppliers()     { python3 -c "
import sys,json
d=json.load(sys.stdin)
aggs=d['aggregations']
samsung=aggs['samsung_count']['product_count']['value']
print(f'  ${TERM} (filter agg):              {samsung:>6} products  ◀ watch this change')
print()
print('  Top 5 suppliers overall:')
for b in aggs['top_suppliers']['buckets']:
    print(f'    {b[\"key\"]:<30} {b[\"doc_count\"]:>6} products')
"; }

# ==========================================================================
# REQUEST 1: SEARCH — count Samsung products (before delete)
# ==========================================================================
section "REQUEST 1: SEARCH — ${FIELD}:${TERM} (before delete)"

subsection "a) Elasticsearch (direct)"
es -X POST "${ES_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${SEARCH_BODY}" | count_from_search

subsection "b) Via CaptureProxy → ES response + captured for OS replay"
proxy -X POST "${PROXY_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${SEARCH_BODY}" | count_from_search

subsection "c) OpenSearch (direct — replayer will have already processed this)"
os -X POST "${OS_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${SEARCH_BODY}" | count_from_search

# ==========================================================================
# REQUEST 2: AGGREGATION — top suppliers (before delete)
# ==========================================================================
section "REQUEST 2: AGGREGATION — top suppliers (before delete)"

subsection "a) Elasticsearch (direct)"
es -X POST "${ES_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${AGG_BODY}" | top_suppliers

subsection "b) Via CaptureProxy → captured for OS replay"
proxy -X POST "${PROXY_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${AGG_BODY}" | top_suppliers

subsection "c) OpenSearch (direct)"
os -X POST "${OS_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${AGG_BODY}" | top_suppliers

# ==========================================================================
# REQUEST 3: DELETE — remove one Samsung product
# ==========================================================================
section "REQUEST 3: DELETE — removing [${DELETE_ID}]"
echo "  ${DELETE_TITLE}"

subsection "a) Elasticsearch (direct) — baseline delete"
es -X DELETE "${ES_URL}/${INDEX}/_doc/${DELETE_ID}" | pretty

subsection "b) Via CaptureProxy — delete replayed to OpenSearch"
proxy -X DELETE "${PROXY_URL}/${INDEX}/_doc/${DELETE_ID}" | pretty

echo ""
echo "  Waiting for TrafficReplayer to propagate the DELETE to OpenSearch (up to ${REPLAY_WAIT}s)..."
OS_COUNT_BEFORE=$(os -X POST "${OS_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${SEARCH_BODY}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['hits']['total']['value'])")
elapsed=0
while [ "$elapsed" -lt "$REPLAY_WAIT" ]; do
  OS_COUNT_NOW=$(os -X POST "${OS_URL}/${INDEX}/_search" \
    -H "Content-Type: application/json" -d "${SEARCH_BODY}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['hits']['total']['value'])" 2>/dev/null)
  if [ "${OS_COUNT_NOW}" -lt "${OS_COUNT_BEFORE}" ]; then
    echo "  ✓ DELETE propagated to OpenSearch after ${elapsed}s (${OS_COUNT_BEFORE} → ${OS_COUNT_NOW})"
    break
  fi
  printf "  ... %ds elapsed, OpenSearch still at %s %s products\r" "$elapsed" "$OS_COUNT_NOW" "${TERM}"
  sleep 5
  elapsed=$((elapsed + 5))
done
if [ "$elapsed" -ge "$REPLAY_WAIT" ]; then
  echo "  ⚠ Timed out after ${REPLAY_WAIT}s — replayer may still be catching up"
fi
echo ""

# ==========================================================================
# REQUEST 4: SEARCH — recount Samsung (after delete)
# ==========================================================================
section "REQUEST 4: SEARCH — ${FIELD}:${TERM} (after delete, expect -1)"

subsection "a) Elasticsearch (direct)"
es -X POST "${ES_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${SEARCH_BODY}" | count_from_search

subsection "b) Via CaptureProxy → captured for OS replay"
proxy -X POST "${PROXY_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${SEARCH_BODY}" | count_from_search

subsection "c) OpenSearch (direct) — DELETE should have propagated"
os -X POST "${OS_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${SEARCH_BODY}" | count_from_search

# ==========================================================================
# REQUEST 5: AGGREGATION — top suppliers (after delete)
# ==========================================================================
section "REQUEST 5: AGGREGATION — top suppliers (after delete, Samsung -1)"

subsection "a) Elasticsearch (direct)"
es -X POST "${ES_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${AGG_BODY}" | top_suppliers

subsection "b) Via CaptureProxy → captured for OS replay"
proxy -X POST "${PROXY_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${AGG_BODY}" | top_suppliers

subsection "c) OpenSearch (direct)"
os -X POST "${OS_URL}/${INDEX}/_search" \
  -H "Content-Type: application/json" -d "${AGG_BODY}" | top_suppliers

# ==========================================================================
section "SUMMARY"
echo "  All 5 requests used the '${FIELD}' field."
echo "  Requests (b) went through CaptureProxy and were replayed to OpenSearch."
echo "  The DELETE on [${DELETE_ID}] should now be gone from both clusters."
echo ""
