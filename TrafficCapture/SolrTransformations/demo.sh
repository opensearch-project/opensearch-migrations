#!/usr/bin/env bash
# =============================================================================
# TransformationShim E2E Demo
#
# Spins up Solr 8, OpenSearch 3.3, and the Shim proxy via
# docker-compose, seeds identical data into both, then uses curl to show
# how the proxy transparently transforms Solr requests into OpenSearch
# queries and converts the responses back to Solr format.
#
# Usage:
#   ./demo.sh          # Run full demo (build + start + demo + teardown)
#   ./demo.sh --skip-build   # Skip gradle/npm build steps (if already built)
#   ./demo.sh --no-teardown  # Leave services running after demo
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker/docker-compose.yml"
TRANSFORMS_DIR="$SCRIPT_DIR/transforms"

SKIP_BUILD=false
NO_TEARDOWN=false
for arg in "$@"; do
  case "$arg" in
    --skip-build)  SKIP_BUILD=true ;;
    --no-teardown) NO_TEARDOWN=true ;;
  esac
done

# --- Colors & helpers --------------------------------------------------------
BOLD='\033[1m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
DIM='\033[2m'
RESET='\033[0m'

banner()  { echo -e "\n${BOLD}${CYAN}═══════════════════════════════════════════════════════════${RESET}"; echo -e "${BOLD}${CYAN}  $1${RESET}"; echo -e "${BOLD}${CYAN}═══════════════════════════════════════════════════════════${RESET}\n"; }
step()    { echo -e "${BOLD}${GREEN}▶ $1${RESET}"; }
info()    { echo -e "${DIM}  $1${RESET}"; }
warn()    { echo -e "${YELLOW}⚠ $1${RESET}"; }
divider() { echo -e "${DIM}───────────────────────────────────────────────────────────${RESET}"; }

show_curl() {
  local desc="$1"; shift
  echo -e "\n${BOLD}${YELLOW}$ $*${RESET}"
  echo -e "${DIM}# ${desc}${RESET}"
  local output
  output=$("$@" 2>/dev/null) || { echo -e "${RED}Request failed${RESET}"; return 1; }
  echo "$output" | python3 -m json.tool 2>/dev/null || echo "$output"
  echo ""
}

wait_for() {
  local name="$1" url="$2" max_wait="${3:-120}"
  info "Waiting for $name at $url ..."
  local elapsed=0
  while ! curl -sf "$url" >/dev/null 2>&1; do
    sleep 2; elapsed=$((elapsed + 2))
    if [ "$elapsed" -ge "$max_wait" ]; then
      echo -e "${RED}✗ $name did not become healthy after ${max_wait}s${RESET}"; exit 1
    fi
  done
  info "$name is ready (${elapsed}s)"
}

cleanup() {
  if [ "$NO_TEARDOWN" = false ]; then
    banner "Teardown"
    step "Stopping services..."
    docker compose -f "$COMPOSE_FILE" down -v --remove-orphans 2>/dev/null || true
  else
    warn "Services left running. Stop with: docker compose -f $COMPOSE_FILE down -v"
  fi
}
trap cleanup EXIT

# =============================================================================
# 1. BUILD
# =============================================================================
if [ "$SKIP_BUILD" = false ]; then
  banner "Step 1: Build"

  step "Building Shim Docker image (gradle jibDockerBuild)..."
  (cd "$REPO_ROOT" && ./gradlew :TrafficCapture:transformationShim:jibDockerBuild -q)
  info "Image: migrations/transformation_shim"

  step "Building TypeScript transforms (npm install + build)..."
  (cd "$TRANSFORMS_DIR" && npm install --silent && npm run build --silent)
  info "Output: $TRANSFORMS_DIR/dist/"
else
  warn "Skipping build (--skip-build). Make sure image and transforms are already built."
fi

# =============================================================================
# 2. START SERVICES
# =============================================================================
banner "Step 2: Start Services"

step "Starting docker-compose (OpenSearch 3.3, Solr 8, Shim proxy)..."
docker compose -f "$COMPOSE_FILE" up -d

info "Services:"
info "  Solr 8          → http://localhost:8983"
info "  OpenSearch 3.3  → http://localhost:9200"
info "  Shim Proxy      → http://localhost:8080"

wait_for "OpenSearch" "http://localhost:9200" 120
wait_for "Solr"       "http://localhost:8983/solr/admin/info/system" 120

# Give the shim a moment to connect to OpenSearch and load transforms
sleep 5

# =============================================================================
# 3. SEED DATA
# =============================================================================
banner "Step 3: Seed Data"

COLLECTION="demo"

step "Creating Solr collection '$COLLECTION'..."
curl -sf "http://localhost:8983/solr/admin/collections?action=CREATE&name=${COLLECTION}&numShards=1&replicationFactor=1" >/dev/null
info "Solr collection '$COLLECTION' created"

step "Indexing documents into Solr..."
curl -sf -X POST "http://localhost:8983/solr/${COLLECTION}/update/json/docs?commit=true" \
  -H 'Content-Type: application/json' \
  -d '[
    {"id": "1", "title": "Introduction to OpenSearch", "category": "search", "author": "Alice"},
    {"id": "2", "title": "Migrating from Solr",        "category": "migration", "author": "Bob"},
    {"id": "3", "title": "Query DSL Deep Dive",         "category": "search", "author": "Charlie"}
  ]' >/dev/null
info "3 documents indexed in Solr"

step "Indexing same documents into OpenSearch..."
for doc in \
  '{"id":"1","title":"Introduction to OpenSearch","category":"search","author":"Alice"}' \
  '{"id":"2","title":"Migrating from Solr","category":"migration","author":"Bob"}' \
  '{"id":"3","title":"Query DSL Deep Dive","category":"search","author":"Charlie"}'; do
  id=$(echo "$doc" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
  curl -sf -X PUT "http://localhost:9200/${COLLECTION}/_doc/${id}" \
    -H 'Content-Type: application/json' -d "$doc" >/dev/null
done
# Refresh so docs are searchable
curl -sf -X POST "http://localhost:9200/${COLLECTION}/_refresh" >/dev/null
info "3 documents indexed in OpenSearch"

# =============================================================================
# 4. DEMO: Direct Queries
# =============================================================================
banner "Demo 1: Direct Queries — Solr vs OpenSearch"

info "Same data, different APIs. Let's query both directly."
divider

show_curl "Query Solr directly — returns Solr JSON format" \
  curl -s "http://localhost:8983/solr/${COLLECTION}/select?q=*:*&wt=json"

divider

show_curl "Query OpenSearch directly — returns OpenSearch JSON format" \
  curl -s "http://localhost:9200/${COLLECTION}/_search"

divider
info "Notice the completely different response structures:"
info "  Solr:       { responseHeader: {...}, response: { numFound, docs: [...] } }"
info "  OpenSearch: { hits: { total: {...}, hits: [{ _source: {...} }] } }"

# =============================================================================
# 5. DEMO: Proxy Transforms
# =============================================================================
banner "Demo 2: Shim Proxy — Same Solr URL, Backed by OpenSearch"

info "Send the EXACT SAME Solr request to the proxy on port 8080."
info "The proxy rewrites it to an OpenSearch query and converts the response"
info "back to Solr format — your application sees no difference."
divider

show_curl "Query the PROXY with a Solr URL — gets Solr-format response from OpenSearch!" \
  curl -s "http://localhost:8080/solr/${COLLECTION}/select?q=*:*&wt=json"

divider
info "What happened behind the scenes:"
info "  1. Client sent:  GET /solr/demo/select?q=*:*&wt=json  → proxy:8080"
info "  2. Request transform rewrote to:  POST /demo/_search {match_all}  → opensearch:9200"
info "  3. OpenSearch returned hits in OpenSearch format"
info "  4. Response transform converted to Solr format (responseHeader + response.docs[])"
info "  5. Client received a Solr-compatible response ✓"

# =============================================================================
# 6. DEMO: Side-by-Side Comparison
# =============================================================================
banner "Demo 3: Side-by-Side — Solr Direct vs Proxy"

info "Comparing the actual responses (ignoring QTime and internal fields):"
divider

step "Solr direct response:"
SOLR_RESP=$(curl -s "http://localhost:8983/solr/${COLLECTION}/select?q=*:*&wt=json&rows=1")
echo "$SOLR_RESP" | python3 -m json.tool 2>/dev/null || echo "$SOLR_RESP"

echo ""
step "Proxy response (backed by OpenSearch):"
PROXY_RESP=$(curl -s "http://localhost:8080/solr/${COLLECTION}/select?q=*:*&wt=json")
echo "$PROXY_RESP" | python3 -m json.tool 2>/dev/null || echo "$PROXY_RESP"

divider
info "Both return Solr-format JSON. The proxy response comes from OpenSearch data"
info "transformed through the TypeScript request + response transforms."

# =============================================================================
# 7. DEMO: What the proxy does to non-select requests
# =============================================================================
banner "Demo 4: Pass-Through Behavior"

info "Requests that don't match /solr/{collection}/select are passed through"
info "unchanged to the OpenSearch backend."
divider

show_curl "Non-Solr request passes through to OpenSearch" \
  curl -s "http://localhost:8080/${COLLECTION}/_count"

# =============================================================================
# 8. DEMO: Show the transforms are just TypeScript
# =============================================================================
banner "Demo 5: The Transforms Are Just TypeScript"

step "request.transform.ts — rewrites Solr select → OpenSearch _search:"
echo -e "${DIM}"
cat "$TRANSFORMS_DIR/src/solr-to-opensearch/request.transform.ts"
echo -e "${RESET}"

divider

step "response.transform.ts — converts OpenSearch hits → Solr response.docs:"
echo -e "${DIM}"
cat "$TRANSFORMS_DIR/src/solr-to-opensearch/response.transform.ts"
echo -e "${RESET}"

divider
info "Edit these files → the watcher rebuilds → the proxy hot-reloads."
info "No Java changes. No restarts. Just TypeScript."

# =============================================================================
# 9. DEMO: Hot-reload (interactive)
# =============================================================================
banner "Demo 6: Hot-Reload (try it yourself)"

info "The docker-compose setup includes a file watcher that rebuilds transforms"
info "on every change. Try it:"
info ""
info "  1. Edit: $TRANSFORMS_DIR/src/solr-to-opensearch/response.transform.ts"
info "  2. Watch the transform-watcher container rebuild:"
info "     docker compose -f $COMPOSE_FILE logs -f transform-watcher"
info "  3. Send the same curl and see the new behavior:"
info "     curl -s http://localhost:8080/solr/${COLLECTION}/select?q=*:*&wt=json | python3 -m json.tool"

# =============================================================================
# Summary
# =============================================================================
banner "Summary"

echo -e "${BOLD}Architecture:${RESET}"
echo ""
echo "  Solr Client  ──▶  Shim Proxy (:8080)  ──▶  OpenSearch (:9200)"
echo "                         │                    ▲"
echo "                    request.transform.ts  response.transform.ts"
echo ""
echo -e "${BOLD}Key URLs:${RESET}"
echo "  Solr direct:     http://localhost:8983/solr/${COLLECTION}/select?q=*:*&wt=json"
echo "  OpenSearch:       http://localhost:9200/${COLLECTION}/_search"
echo "  Proxy (Solr URL): http://localhost:8080/solr/${COLLECTION}/select?q=*:*&wt=json"
echo ""

if [ "$NO_TEARDOWN" = true ]; then
  echo -e "${GREEN}Services are still running. Explore with curl!${RESET}"
  echo -e "Stop with: ${DIM}docker compose -f $COMPOSE_FILE down -v${RESET}"
fi
