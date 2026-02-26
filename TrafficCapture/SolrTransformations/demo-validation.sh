#!/usr/bin/env bash
# =============================================================================
# ValidationShim Multi-Mode Demo
#
# Spins up Solr 8, OpenSearch 3.3, and 4 validation shim instances:
#   :8081 — solr-only passthrough
#   :8082 — opensearch-only (with transforms)
#   :8083 — dual-target, solr primary (with validation)
#   :8084 — dual-target, opensearch primary (with validation)
#
# Seeds identical data into both backends, then demonstrates each mode.
#
# Usage:
#   ./demo-validation.sh              # Full demo
#   ./demo-validation.sh --skip-build # Skip gradle/npm build
#   ./demo-validation.sh --no-teardown # Leave services running
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker/docker-compose.validation.yml"
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
  local output headers
  local tmpfile
  tmpfile=$(mktemp)
  output=$(curl -sD "$tmpfile" "$@" 2>/dev/null) || { echo -e "${RED}Request failed${RESET}"; rm -f "$tmpfile"; return 1; }

  # Show validation/shim headers
  local shim_headers
  shim_headers=$(grep -iE '^X-(Shim|Target|Validation)' "$tmpfile" 2>/dev/null || true)
  if [ -n "$shim_headers" ]; then
    echo -e "${CYAN}Headers:${RESET}"
    echo "$shim_headers" | while IFS= read -r line; do
      echo -e "  ${CYAN}${line}${RESET}"
    done
  fi
  rm -f "$tmpfile"

  echo -e "${DIM}Body:${RESET}"
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

step "Starting docker-compose (Solr 8, OpenSearch 3.3, 4 validation shim instances)..."
docker compose -f "$COMPOSE_FILE" up -d

info "Services:"
info "  Solr 8                    → http://localhost:8983"
info "  OpenSearch 3.3            → http://localhost:9200"
info "  Shim: solr-only           → http://localhost:8081"
info "  Shim: opensearch-only     → http://localhost:8082"
info "  Shim: solr-primary        → http://localhost:8083"
info "  Shim: opensearch-primary  → http://localhost:8084"

wait_for "OpenSearch" "http://localhost:9200" 120
wait_for "Solr"       "http://localhost:8983/solr/admin/info/system" 120

# Give shims time to start
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
curl -sf -X POST "http://localhost:9200/${COLLECTION}/_refresh" >/dev/null
info "3 documents indexed in OpenSearch"

# =============================================================================
# 4. DEMO: Mode 1 — Solr-Only Passthrough
# =============================================================================
banner "Mode 1: Solr-Only Passthrough (:8081)"

info "Single target — proxies directly to Solr, no transforms, no validation."
info "Use case: baseline comparison, or when you just want a Solr proxy."
divider

show_curl "Solr query through solr-only shim" \
  curl -s "http://localhost:8081/solr/${COLLECTION}/select?q=*:*&wt=json"

# =============================================================================
# 5. DEMO: Mode 2 — OpenSearch-Only with Transforms
# =============================================================================
banner "Mode 2: OpenSearch-Only with Transforms (:8082)"

info "Single target — transforms Solr request → OpenSearch, transforms response back."
info "Use case: cutover testing — your app talks Solr, backend is OpenSearch."
divider

show_curl "Solr query through opensearch-only shim (backed by OpenSearch)" \
  curl -s "http://localhost:8082/solr/${COLLECTION}/select?q=*:*&wt=json"

# =============================================================================
# 6. DEMO: Mode 3 — Dual-Target, Solr Primary
# =============================================================================
banner "Mode 3: Dual-Target, Solr Primary (:8083)"

info "Sends to BOTH Solr and OpenSearch in parallel."
info "Returns the Solr response to the client."
info "Runs validators and reports results in X-Validation-* headers."
info "Use case: shadow validation — verify OpenSearch matches Solr before cutover."
divider

show_curl "Dual-target query — Solr response returned, validation headers added" \
  curl -s "http://localhost:8083/solr/${COLLECTION}/select?q=*:*&wt=json"

# =============================================================================
# 7. DEMO: Mode 4 — Dual-Target, OpenSearch Primary
# =============================================================================
banner "Mode 4: Dual-Target, OpenSearch Primary (:8084)"

info "Same dual-target setup, but returns the OpenSearch response."
info "Validates against Solr in the background."
info "Use case: cutover with safety net — serve from OpenSearch, validate against Solr."
divider

show_curl "Dual-target query — OpenSearch response returned, validation headers added" \
  curl -s "http://localhost:8084/solr/${COLLECTION}/select?q=*:*&wt=json"

# =============================================================================
# 8. SUMMARY
# =============================================================================
banner "Summary"

echo -e "${BOLD}Architecture:${RESET}"
echo ""
echo "                    ┌─ :8081 solr-only ──────────────────▶ Solr (:8983)"
echo "                    │"
echo "                    ├─ :8082 opensearch-only ─[transform]▶ OpenSearch (:9200)"
echo "  Solr Client ──────┤"
echo "                    ├─ :8083 solr-primary ───┬──────────▶ Solr (:8983) ← primary"
echo "                    │                        └[transform]▶ OpenSearch (:9200)"
echo "                    │"
echo "                    └─ :8084 opensearch-primary ┬────────▶ Solr (:8983)"
echo "                                                └[transform]▶ OpenSearch (:9200) ← primary"
echo ""
echo -e "${BOLD}Ports:${RESET}"
echo "  Solr direct:           http://localhost:8983/solr/${COLLECTION}/select?q=*:*&wt=json"
echo "  OpenSearch direct:     http://localhost:9200/${COLLECTION}/_search"
echo "  Solr-only shim:        http://localhost:8081/solr/${COLLECTION}/select?q=*:*&wt=json"
echo "  OpenSearch-only shim:  http://localhost:8082/solr/${COLLECTION}/select?q=*:*&wt=json"
echo "  Solr-primary shim:     http://localhost:8083/solr/${COLLECTION}/select?q=*:*&wt=json"
echo "  OpenSearch-primary:    http://localhost:8084/solr/${COLLECTION}/select?q=*:*&wt=json"
echo ""
echo -e "${BOLD}Validation headers (dual-target modes):${RESET}"
echo "  X-Shim-Primary:              which target's response you're seeing"
echo "  X-Shim-Targets:              all active targets"
echo "  X-Target-{name}-StatusCode:  per-target HTTP status"
echo "  X-Target-{name}-Latency:     per-target response time (ms)"
echo "  X-Validation-Status:         PASS / FAIL / ERROR"
echo "  X-Validation-Details:        per-validator results"
echo ""

if [ "$NO_TEARDOWN" = true ]; then
  echo -e "${GREEN}Services are still running. Explore with curl!${RESET}"
  echo -e "Stop with: ${DIM}docker compose -f $COMPOSE_FILE down -v${RESET}"
fi
