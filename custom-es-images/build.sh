#!/usr/bin/env bash
# build.sh — Build custom ES Docker images from versions.json
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VERSIONS_FILE="${SCRIPT_DIR}/versions.json"
IMAGE_PREFIX="${IMAGE_PREFIX:-custom-elasticsearch}"

usage() {
  echo "Usage: $0 [OPTIONS] [MINOR_VERSION...]"
  echo ""
  echo "Build custom Elasticsearch Docker images."
  echo ""
  echo "Options:"
  echo "  -l, --list        List all available versions"
  echo "  -a, --all         Build all versions"
  echo "  -m, --major N     Build all minor versions for major version N"
  echo "  -p, --parallel N  Build N images concurrently"
  echo "  -h, --help        Show this help"
  echo ""
  echo "Examples:"
  echo "  $0 7.10 7.17          # Build specific minor versions"
  echo "  $0 --major 6           # Build all 6.x versions"
  echo "  $0 --all -p 4          # Build everything, 4 at a time"
  exit 0
}

PARALLEL=0

list_versions() {
  python3 -c "
import json
with open('${VERSIONS_FILE}') as f:
    data = json.load(f)
for minor in sorted(data.keys(), key=lambda x: [int(p) for p in x.split('.')]):
    info = data[minor]
    print(f\"  {minor:>5} -> {info['version']:>10}  ({info['method']})\")" 
}

build_version() {
  local minor="$1"
  local info
  info=$(python3 -c "
import json, sys
with open('${VERSIONS_FILE}') as f:
    data = json.load(f)
if '${minor}' not in data:
    print('NOT_FOUND', file=sys.stderr); sys.exit(1)
v = data['${minor}']
print(f\"{v['version']}|{v['method']}|{v.get('url', '')}\")")

  local version method url
  IFS='|' read -r version method url <<< "$info"
  local tag="${IMAGE_PREFIX}:${version}"

  echo "=== Building ${tag} (${method}) ==="

  if [ "$method" = "tarball" ]; then
    docker build \
      --build-arg ES_VERSION="${version}" \
      --build-arg TARBALL_URL="${url}" \
      -f "${SCRIPT_DIR}/dockerfiles/Dockerfile.legacy" \
      -t "${tag}" \
      "${SCRIPT_DIR}"
  else
    docker build \
      --build-arg ES_VERSION="${version}" \
      -f "${SCRIPT_DIR}/dockerfiles/Dockerfile.official" \
      -t "${tag}" \
      "${SCRIPT_DIR}"
  fi

  echo "=== Built ${tag} ==="
}

# Parse arguments
VERSIONS_TO_BUILD=()

if [ $# -eq 0 ]; then usage; fi

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage ;;
    -l|--list) echo "Available versions:"; list_versions; exit 0 ;;
    -a|--all)
      mapfile -t VERSIONS_TO_BUILD < <(python3 -c "
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
      VERSIONS_TO_BUILD+=("${majors[@]}")
      shift ;;
    -p|--parallel) shift; PARALLEL="$1"; shift ;;
    *) VERSIONS_TO_BUILD+=("$1"); shift ;;
  esac
done

# Build
TOTAL=${#VERSIONS_TO_BUILD[@]}
echo "Building ${TOTAL} image(s)..."
echo ""

FAILED=()
if [ "$PARALLEL" -gt 0 ]; then
  echo "Parallelism: ${PARALLEL}"
  PIDS=(); VER_FOR_PID=()
  for minor in "${VERSIONS_TO_BUILD[@]}"; do
    ( build_version "$minor" ) > /dev/null 2>&1 &
    PIDS+=($!); VER_FOR_PID+=("$minor")
    while [ "$(jobs -rp | wc -l)" -ge "$PARALLEL" ]; do sleep 1; done
  done
  for i in "${!PIDS[@]}"; do
    if wait "${PIDS[$i]}" 2>/dev/null; then
      echo "  ✅ ${VER_FOR_PID[$i]}"
    else
      FAILED+=("${VER_FOR_PID[$i]}")
      echo "  ❌ ${VER_FOR_PID[$i]}"
    fi
  done
else
  for i in "${!VERSIONS_TO_BUILD[@]}"; do
    minor="${VERSIONS_TO_BUILD[$i]}"
    echo "[$(( i + 1 ))/${TOTAL}] ${minor}"
    if ! build_version "$minor"; then
      FAILED+=("$minor")
      echo "!!! FAILED: ${minor}"
    fi
    echo ""
  done
fi

# Summary
echo "========================================"
echo "Build complete: $(( TOTAL - ${#FAILED[@]} ))/${TOTAL} succeeded"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo "Failed: ${FAILED[*]}"
  exit 1
fi
