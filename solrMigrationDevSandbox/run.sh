#!/usr/bin/env bash
# Solr Migration Developer Sandbox: dataset generation → cluster setup → data loading.
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

show_help() {
  cat << 'EOF'
Solr Migration Developer Sandbox

Spins up Solr 9.x + OpenSearch 3.3 + Translation Shim via Docker Compose,
generates a 200K synthetic dataset, loads identical data into both clusters,
and optionally executes queries against Solr-direct and the Translation Shim.

USAGE:
  ./run.sh [OPTIONS]

OPTIONS:
  --run-queries     Execute 160 queries (including sequential cursor walks)
                    against both Solr-direct and the Translation Shim.
                    Without this flag, clusters are started and kept running
                    for manual testing.
  --dual-mode       Also run queries against the dual-target shim which sends
                    to both Solr and OpenSearch simultaneously and validates
                    responses match. Only meaningful with --run-queries.
  --dual-primary    Primary target for dual mode: 'opensearch' (default, port
                    18084) or 'solr' (port 18083). Controls which response is
                    returned to the client. Only meaningful with --dual-mode.
  --no-teardown     Keep clusters running after query execution.
                    Only meaningful with --run-queries.
  --help            Show this help message and exit.

EXAMPLES:
  ./run.sh                              Setup clusters + load data, keep running
  ./run.sh --run-queries                Execute queries, then tear down clusters
  ./run.sh --run-queries --dual-mode    Also validate via dual-target shim (OpenSearch primary)
  ./run.sh --run-queries --dual-mode --dual-primary solr  Use Solr as primary
  ./run.sh --run-queries --no-teardown  Execute queries, keep clusters running

ENDPOINTS (after startup):
  Solr:        http://localhost:18983
  OpenSearch:  http://localhost:19200
  Shim:        http://localhost:18080
  Dual Shim:   http://localhost:18084 (OpenSearch primary, default)
               http://localhost:18083 (Solr primary)

MANUAL QUERY EXECUTION:
  python3 -m src.run_queries \
    --solr-url http://localhost:18983 \
    --shim-url http://localhost:18080 \
    --queries queries/queries.json

  # With dual-mode (OpenSearch primary):
  python3 -m src.run_queries \
    --solr-url http://localhost:18983 \
    --shim-url http://localhost:18080 \
    --dual-url http://localhost:18084 \
    --queries queries/queries.json

  # With dual-mode (Solr primary):
  python3 -m src.run_queries \
    --solr-url http://localhost:18983 \
    --shim-url http://localhost:18080 \
    --dual-url http://localhost:18083 \
    --queries queries/queries.json

TEARDOWN:
  docker compose down -v
EOF
  exit 0
}

RUN_QUERIES=false
NO_TEARDOWN=false
DUAL_MODE=false
DUAL_PRIMARY=opensearch

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) show_help ;;
    --run-queries) RUN_QUERIES=true; shift ;;
    --dual-mode) DUAL_MODE=true; shift ;;
    --dual-primary) DUAL_PRIMARY="$2"; shift 2 ;;
    --no-teardown) NO_TEARDOWN=true; shift ;;
    *) echo "ERROR: Unknown flag: $1 (use --help for usage)" >&2; exit 1 ;;
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
echo "  Solr Migration Developer Sandbox"
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
  mkdir -p TrafficCapture/transformationShim/build/versionDir
  # Requires Java 17 — override JAVA_HOME if current version is too old
  SHIM_JAVA_HOME="${JAVA_HOME:-}"
  if [[ -d "/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home" ]]; then
    SHIM_JAVA_HOME="/Library/Java/JavaVirtualMachines/amazon-corretto-17.jdk/Contents/Home"
  elif [[ -d "/usr/lib/jvm/java-17-amazon-corretto" ]]; then
    SHIM_JAVA_HOME="/usr/lib/jvm/java-17-amazon-corretto"
  fi
  set +e
  JAVA_HOME="$SHIM_JAVA_HOME" \
    ./gradlew :TrafficCapture:transformationShim:assemble :TrafficCapture:transformationShim:jibDockerBuild
  set -e
  # Jib tags to localhost:5001/..., re-tag for local use
  docker tag localhost:5001/migrations/transformation_shim:latest migrations/transformation_shim:latest 2>/dev/null || true
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
  echo "  Dual Shim:  http://localhost:18084 (OpenSearch primary)"
  echo "              http://localhost:18083 (Solr primary)"
  echo ""
  echo "  Run queries:  python3 -m src.run_queries --queries queries/queries.json"
  echo "  Dual mode:    python3 -m src.run_queries --queries queries/queries.json --dual-url http://localhost:18084"
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

DUAL_ARG=""
if [[ "$DUAL_MODE" == true ]]; then
  if [[ "$DUAL_PRIMARY" == "solr" ]]; then
    DUAL_PORT=18083
  else
    DUAL_PORT=18084
  fi
  DUAL_ARG="--dual-url http://localhost:${DUAL_PORT}"
  echo "  Dual mode enabled — primary: ${DUAL_PRIMARY}, port: ${DUAL_PORT}"
fi

python3 -m src.run_queries \
  --solr-url http://localhost:18983 \
  --shim-url http://localhost:18080 \
  --queries queries/queries.json \
  $DUAL_ARG

echo ""
echo "=== Done ==="
