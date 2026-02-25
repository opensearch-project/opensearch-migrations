#!/usr/bin/env bash
# test.sh — Test custom ES Docker images by starting them and checking health
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSIONS_FILE="${SCRIPT_DIR}/versions.json"
IMAGE_PREFIX="${IMAGE_PREFIX:-custom-elasticsearch}"
CONTAINER_PREFIX="es-test"
TIMEOUT="${TIMEOUT:-120}"

usage() {
  echo "Usage: $0 [OPTIONS] [MINOR_VERSION...]"
  echo ""
  echo "Test custom Elasticsearch Docker images."
  echo ""
  echo "Options:"
  echo "  -a, --all      Test all versions"
  echo "  -m, --major N  Test all minor versions for major version N"
  echo "  -h, --help     Show this help"
  exit 0
}

cleanup_container() {
  local name="$1"
  docker rm -f "$name" &>/dev/null || true
}

test_version() {
  local minor="$1"
  local version
  version=$(python3 -c "
import json
with open('${VERSIONS_FILE}') as f:
    data = json.load(f)
print(data['${minor}']['version'])")

  local tag="${IMAGE_PREFIX}:${version}"
  local container="${CONTAINER_PREFIX}-${version}"
  local major
  major=$(echo "$version" | cut -d. -f1)

  echo "--- Testing ${tag} ---"

  # Cleanup any previous test container
  cleanup_container "$container"

  # Determine port (use unique port to allow parallel testing)
  local port
  port=$(python3 -c "
m, n = '${minor}'.split('.')
print(9200 + int(m) * 100 + int(n))")

  # Run container
  local run_args="-d --name ${container} -p ${port}:9200"
  local minor_num
  minor_num=$(echo "$version" | cut -d. -f2)

  # discovery.type=single-node only supported from ES 5.4+
  if [ "$major" -ge 7 ] || { [ "$major" -eq 5 ] && [ "$minor_num" -ge 4 ]; } || [ "$major" -eq 6 ]; then
    run_args="${run_args} -e discovery.type=single-node"
  fi

  # ES 5.x-6.x need xpack disabled or trial license
  if [ "$major" -eq 5 ] || [ "$major" -eq 6 ]; then
    run_args="${run_args} -e xpack.security.enabled=false"
  fi

  # ES 5.0-5.3 have cgroups v2 compatibility issues (tarball builds avoid this)
  if [ "$major" -le 2 ]; then
    run_args="${run_args} -e ES_JAVA_OPTS=-Xms256m\ -Xmx256m"
  else
    run_args="${run_args} -e ES_JAVA_OPTS=-Xms512m\ -Xmx512m"
  fi

  eval docker run ${run_args} "${tag}"

  # Wait for health
  echo -n "  Waiting for ES ${version} on port ${port}..."
  local elapsed=0
  while [ $elapsed -lt "$TIMEOUT" ]; do
    if curl -sf "http://localhost:${port}/_cluster/health" &>/dev/null; then
      echo " UP"

      # Verify version
      local reported
      reported=$(curl -sf "http://localhost:${port}" | python3 -c "import json,sys; print(json.load(sys.stdin)['version']['number'])" 2>/dev/null || echo "unknown")
      if [ "$reported" = "$version" ]; then
        echo "  ✅ Version verified: ${reported}"
      else
        echo "  ⚠️  Expected ${version}, got ${reported}"
      fi

      # Check cluster health
      local health
      health=$(curl -sf "http://localhost:${port}/_cluster/health" | python3 -c "import json,sys; print(json.load(sys.stdin)['status'])" 2>/dev/null || echo "unknown")
      echo "  ✅ Cluster health: ${health}"

      cleanup_container "$container"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
    echo -n "."
  done

  echo " TIMEOUT"
  echo "  ❌ Failed to start within ${TIMEOUT}s"
  echo "  Container logs:"
  docker logs --tail 20 "$container" 2>&1 | sed 's/^/    /'
  cleanup_container "$container"
  return 1
}

# Parse arguments
VERSIONS_TO_TEST=()

if [ $# -eq 0 ]; then usage; fi

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage ;;
    -a|--all)
      mapfile -t VERSIONS_TO_TEST < <(python3 -c "
import json
with open('${VERSIONS_FILE}') as f:
    data = json.load(f)
for k in sorted(data.keys(), key=lambda x: [int(p) for p in x.split('.')]):
    print(k)")
      shift ;;
    -m|--major)
      shift
      MAJOR="$1"
      mapfile -t majors < <(python3 -c "
import json
with open('${VERSIONS_FILE}') as f:
    data = json.load(f)
for k in sorted(data.keys(), key=lambda x: [int(p) for p in x.split('.')]):
    if k.startswith('${MAJOR}.'):
        print(k)")
      VERSIONS_TO_TEST+=("${majors[@]}")
      shift ;;
    *) VERSIONS_TO_TEST+=("$1"); shift ;;
  esac
done

# Test
TOTAL=${#VERSIONS_TO_TEST[@]}
echo "Testing ${TOTAL} image(s)..."
echo ""

PASSED=()
FAILED=()
for minor in "${VERSIONS_TO_TEST[@]}"; do
  if test_version "$minor"; then
    PASSED+=("$minor")
  else
    FAILED+=("$minor")
  fi
  echo ""
done

# Summary
echo "========================================"
echo "Test results: ${#PASSED[@]}/${TOTAL} passed"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "Failed: ${FAILED[*]}"
  exit 1
fi
echo "All tests passed! ✅"
