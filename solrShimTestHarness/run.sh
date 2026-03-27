#!/usr/bin/env bash
# Solr Shim Test Harness: dataset generation → cluster setup → data loading.
# Optionally executes queries with --run-queries.
#
# Usage:
#   ./run.sh                           # Setup clusters + load data, keep running
#   ./run.sh --run-queries             # Also execute queries, then tear down
#   ./run.sh --run-queries --no-teardown  # Execute queries, keep clusters running

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -d "/Applications/Docker.app/Contents/Resources/bin" ]]; then
  export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
fi

RUN_QUERIES=false
NO_TEARDOWN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-queries) RUN_QUERIES=true; shift ;;
    --no-teardown) NO_TEARDOWN=true; shift ;;
    *) echo "ERROR: Unknown flag: $1" >&2; exit 1 ;;
  esac
done

cleanup() {
  local exit_code=$?
  if [[ "$RUN_QUERIES" == true && "$NO_TEARDOWN" == false ]]; then
    echo ""
    echo "=== Tearing down clusters ==="
    docker compose down -v 2>/dev/null || true
  fi
  exit $exit_code
}
trap cleanup EXIT

echo "=== Pre-flight: cleaning up previous runs ==="
docker compose down -v --remove-orphans 2>/dev/null || true
docker compose rm -f 2>/dev/null || true

echo ""
echo "============================================"
echo "  Solr Shim Test Harness"
echo "============================================"
echo ""

# --- Step 1: Generate dataset ---
if [[ -f "data/dataset.json" ]]; then
  echo "=== Step 1: Dataset already exists, skipping generation ==="
  echo "  Delete data/dataset.json to regenerate."
else
  echo "=== Step 1: Generating dataset ==="
  python3 data/generate_dataset.py --output data/dataset.json
fi

if [[ ! -f "data/dataset.json" ]]; then
  echo "ERROR: data/dataset.json not found." >&2
  exit 1
fi
echo ""

# --- Step 2: Build shim (auto-detect) ---
if docker image inspect migrations/transformation_shim &>/dev/null; then
  echo "=== Step 2: Shim image already exists, skipping build ==="
else
  echo "=== Step 2: Building translation shim ==="
  cd ..
  JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home}" \
    ./gradlew :TrafficCapture:transformationShim:jibDockerBuild 2>&1 | tail -5
  cd "$SCRIPT_DIR"
fi
echo ""

# --- Step 3: Start clusters ---
echo "=== Step 3: Starting Docker Compose environment ==="
docker compose up -d
echo "  Containers started. Health checks will be verified in Step 4."
echo ""

# --- Step 4: Setup clusters + load data ---
echo "=== Step 4: Setting up clusters and loading data ==="
bash scripts/setup-clusters.sh \
  --solr-url http://localhost:18983 \
  --opensearch-url http://localhost:19200 \
  --dataset data/dataset.json \
  --opensearch-mapping config/opensearch/index-mapping.json
echo ""

if [[ "$RUN_QUERIES" == false ]]; then
  echo "=== Clusters ready ==="
  echo "  Solr:       http://localhost:18983"
  echo "  OpenSearch: http://localhost:19200"
  echo "  Shim:       http://localhost:18080"
  echo ""
  echo "  Run queries:  python3 -m src.harness.run_queries --queries queries/queries.json"
  echo "  Tear down:    docker compose down -v"
  exit 0
fi

# --- Step 5: Execute queries ---
echo "=== Step 5: Executing queries ==="
if [[ ! -f "queries/queries.json" ]]; then
  echo "ERROR: queries/queries.json not found." >&2
  exit 1
fi

QUERY_COUNT=$(python3 -c "import json; print(len(json.load(open('queries/queries.json'))))")
echo "  Running $QUERY_COUNT queries against Solr and Translation Shim..."

python3 -m src.harness.run_queries \
  --solr-url http://localhost:18983 \
  --shim-url http://localhost:18080 \
  --queries queries/queries.json

echo ""
echo "=== Done ==="
