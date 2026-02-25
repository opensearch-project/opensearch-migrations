#!/usr/bin/env bash
# benchmark.sh — Compare custom vs public ES images: startup time, memory, image size
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_FILE="${SCRIPT_DIR}/benchmark-results.json"
TIMEOUT=180

# Versions to benchmark (one per major)
declare -A VERSIONS=(
  ["1.7"]="1.7.6"
  ["2.4"]="2.4.6"
  ["5.6"]="5.6.16"
  ["6.8"]="6.8.23"
  ["7.17"]="7.17.29"
  ["8.19"]="8.19.11"
)

# Public image names
public_image() {
  local version="$1"
  local major="${version%%.*}"
  if [ "$major" -le 2 ]; then
    echo "elasticsearch:${version}"
  else
    echo "docker.elastic.co/elasticsearch/elasticsearch:${version}"
  fi
}

# Docker run args for public images (they need manual config)
public_run_args() {
  local version="$1"
  local major="${version%%.*}"
  local args=""
  if [ "$major" -ge 8 ]; then
    args="-e discovery.type=single-node -e xpack.security.enabled=false -e xpack.security.enrollment.enabled=false -e xpack.security.http.ssl.enabled=false -e xpack.security.transport.ssl.enabled=false"
  elif [ "$major" -ge 5 ]; then
    args="-e discovery.type=single-node -e xpack.security.enabled=false"
  fi
  if [ "$major" -le 2 ]; then
    args="${args} -e ES_JAVA_OPTS=-Xms256m\ -Xmx256m"
  else
    args="${args} -e ES_JAVA_OPTS=-Xms512m\ -Xmx512m"
  fi
  echo "$args"
}

# Docker run args for custom images (pre-configured, just need heap)
custom_run_args() {
  local version="$1"
  local major="${version%%.*}"
  if [ "$major" -le 2 ]; then
    echo "-e ES_JAVA_OPTS=-Xms256m\ -Xmx256m"
  else
    echo "-e ES_JAVA_OPTS=-Xms512m\ -Xmx512m"
  fi
}

cleanup() {
  docker rm -f "$1" &>/dev/null || true
}

# Measure startup time and memory for a single image
# Returns: startup_seconds|memory_mb|image_size_mb
benchmark_image() {
  local image="$1"
  local name="$2"
  local port="$3"
  local extra_args="$4"

  cleanup "$name"

  # Image size
  local size_bytes
  size_bytes=$(docker image inspect "$image" --format='{{.Size}}' 2>/dev/null || echo "0")
  local size_mb
  size_mb=$(python3 -c "print(round(${size_bytes}/1048576, 1))")

  # Start container and measure time to healthy
  local start_time
  start_time=$(date +%s%N)

  eval docker run -d --name "$name" -p "${port}:9200" ${extra_args} "$image" >/dev/null 2>&1

  local elapsed=0
  local startup_ms="TIMEOUT"
  while [ $elapsed -lt "$TIMEOUT" ]; do
    if curl -sf "http://localhost:${port}/_cluster/health" &>/dev/null; then
      local end_time
      end_time=$(date +%s%N)
      startup_ms=$(( (end_time - start_time) / 1000000 ))
      break
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done

  # Memory usage (wait a moment for stable reading)
  sleep 2
  local mem_mb="N/A"
  if [ "$startup_ms" != "TIMEOUT" ]; then
    mem_mb=$(docker stats --no-stream --format '{{.MemUsage}}' "$name" 2>/dev/null | awk -F'/' '{gsub(/[^0-9.]/, "", $1); print $1}' || echo "N/A")
    # Handle GiB vs MiB
    local raw
    raw=$(docker stats --no-stream --format '{{.MemUsage}}' "$name" 2>/dev/null | awk -F'/' '{print $1}')
    if echo "$raw" | grep -qi "gib"; then
      mem_mb=$(echo "$raw" | sed 's/[^0-9.]//g' | awk '{printf "%.1f", $1 * 1024}')
    else
      mem_mb=$(echo "$raw" | sed 's/[^0-9.]//g')
    fi
  fi

  cleanup "$name"
  echo "${startup_ms}|${mem_mb}|${size_mb}"
}

# Pull public images first
echo "=== Pulling public images ==="
for minor in $(echo "${!VERSIONS[@]}" | tr ' ' '\n' | sort -t. -k1,1n -k2,2n); do
  version="${VERSIONS[$minor]}"
  img=$(public_image "$version")
  echo "  Pulling ${img}..."
  docker pull "$img" 2>/dev/null || echo "  ⚠️  Failed to pull ${img}"
done
echo ""

# Run benchmarks
echo "=== Running benchmarks ==="
echo ""

# JSON output
echo "[" > "$RESULTS_FILE"
first=true

for minor in $(echo "${!VERSIONS[@]}" | tr ' ' '\n' | sort -t. -k1,1n -k2,2n); do
  version="${VERSIONS[$minor]}"
  major="${version%%.*}"
  echo "--- ES ${version} ---"

  # Unique ports
  base_port=$((10200 + major * 100))

  # Custom image
  custom_img="custom-elasticsearch:${version}"
  echo -n "  Custom (${custom_img})... "
  custom_result=$(benchmark_image "$custom_img" "bench-custom-${version}" "$((base_port))" "$(custom_run_args "$version")")
  echo "done: ${custom_result}"

  # Public image
  pub_img=$(public_image "$version")
  echo -n "  Public (${pub_img})... "
  pub_result=$(benchmark_image "$pub_img" "bench-public-${version}" "$((base_port + 1))" "$(public_run_args "$version")")
  echo "done: ${pub_result}"

  # Parse results
  IFS='|' read -r c_startup c_mem c_size <<< "$custom_result"
  IFS='|' read -r p_startup p_mem p_size <<< "$pub_result"

  if [ "$first" = true ]; then first=false; else echo "," >> "$RESULTS_FILE"; fi
  cat >> "$RESULTS_FILE" <<EOF
  {
    "version": "${version}",
    "major": ${major},
    "custom": { "startup_ms": ${c_startup:-0}, "memory_mb": ${c_mem:-0}, "image_size_mb": ${c_size:-0} },
    "public": { "startup_ms": ${p_startup:-0}, "memory_mb": ${p_mem:-0}, "image_size_mb": ${p_size:-0} }
  }
EOF

  echo ""
done

echo "]" >> "$RESULTS_FILE"
echo "=== Results saved to ${RESULTS_FILE} ==="
