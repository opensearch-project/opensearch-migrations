#!/bin/sh
# =============================================================================
# updateEcrManifest.sh
#
# Discovers all container images from all helm charts (migrationAssistantWithArgo
# + testClusters), updates privateEcrManifest.sh, and runs verifyNoPublicImages.sh.
#
# Designed to run autonomously after chart version bumps. Can be invoked by an
# agent or developer.
#
# Usage: ./updateEcrManifest.sh [--ecr-host <host>] [--dry-run]
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEST_CHART_DIR="$(cd "$CHART_DIR/../testClusters" && pwd)"
MANIFEST="$SCRIPT_DIR/privateEcrManifest.sh"
ECR_HOST="${ECR_HOST:-123456789012.dkr.ecr.us-east-1.amazonaws.com}"
DRY_RUN=false

while [ $# -gt 0 ]; do
  case "$1" in
    --ecr-host) ECR_HOST="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    *) shift ;;
  esac
done

echo "=== ECR Manifest Update ==="
echo "Plan:"
echo "  1. Extract chart names + versions from values.yaml"
echo "  2. Pull each chart and run helm template to discover images"
echo "  3. Add known runtime images (strimzi kafka, etcd, etc.)"
echo "  4. Compare with current manifest and report changes"
echo "  5. Run verifyNoPublicImages.sh to confirm overrides are complete"
echo ""

TMPDIR=$(mktemp -d)
trap "rm -rf $TMPDIR" EXIT

# --- Step 1: Extract charts from values.yaml ---
echo "Step 1: Reading chart definitions..."
CHARTS_FILE="$TMPDIR/charts.txt"

# Parse charts from both migrationAssistantWithArgo and testClusters
for vf in "$CHART_DIR/values.yaml" "$TEST_CHART_DIR/values.yaml"; do
  [ -f "$vf" ] || continue
  # Extract name|version|repo triples
  awk '
    /^  [a-zA-Z].*:$/ && in_charts { name=$1; gsub(/:$/,"",name) }
    /version:/ && in_charts && name { gsub(/[" ]/,"",$2); ver=$2 }
    /repository:/ && in_charts && name && /http|oci/ { gsub(/[" ]/,"",$2); repo=$2; print name"|"ver"|"repo; name="" }
    /^charts:/ { in_charts=1 }
    /^[^ ]/ && !/^charts:/ { in_charts=0 }
  ' "$vf" >> "$CHARTS_FILE"
done

echo "  Found $(wc -l < "$CHARTS_FILE" | tr -d ' ') charts"

# --- Step 2: Discover images via helm template ---
echo "Step 2: Discovering images from helm template..."
ALL_IMAGES="$TMPDIR/all_images.txt"
touch "$ALL_IMAGES"

while IFS='|' read -r name version repo; do
  [ -z "$name" ] && continue
  echo "  Pulling $name $version..."
  cd "$TMPDIR"
  if echo "$repo" | grep -q '^oci://'; then
    helm pull "$repo/$name" --version "$version" 2>/dev/null || { echo "    WARN: could not pull $name"; continue; }
  else
    helm pull "$name" --repo "$repo" --version "$version" 2>/dev/null || { echo "    WARN: could not pull $name"; continue; }
  fi
  tgz=$(ls ${name}*.tgz 2>/dev/null | head -1)
  [ -z "$tgz" ] && continue
  tar xzf "$tgz" 2>/dev/null
  dir=$(basename "$tgz" .tgz | sed 's/-[0-9].*//')
  # Try exact dir name first, then glob
  [ -d "$dir" ] || dir=$(ls -d ${name}*/ 2>/dev/null | head -1 | tr -d '/')
  if [ -d "$dir" ]; then
    helm template test "$dir/" 2>/dev/null | grep -E '^\s+image:' | \
      sed 's/.*image:\s*//' | tr -d '"' | tr -d "'" | sed 's/^[[:space:]]*//' >> "$ALL_IMAGES"
  fi
  rm -rf "$dir" "$tgz"
done < "$CHARTS_FILE"

# --- Step 3: Add known runtime images ---
echo "Step 3: Adding known runtime images..."
cat >> "$ALL_IMAGES" <<'RUNTIME'
quay.io/strimzi/kafka:0.47.0-kafka-3.9.0
quay.io/strimzi/kafka:0.47.0-kafka-4.0.0
quay.io/strimzi/kafka-bridge:0.32.0
quay.io/strimzi/kaniko-executor:0.47.0
quay.io/strimzi/maven-builder:0.47.0
quay.io/coreos/etcd:v3.5.12
public.ecr.aws/aws-observability/aws-otel-collector:v0.43.3
docker.io/amazon/aws-cli:2.25.11
docker.io/amazon/aws-cli:latest
RUNTIME

# Deduplicate and sort
sort -u "$ALL_IMAGES" | grep -v '^$' > "$TMPDIR/unique_images.txt"
DISCOVERED=$(wc -l < "$TMPDIR/unique_images.txt" | tr -d ' ')
echo "  Discovered $DISCOVERED unique images"

# --- Step 4: Compare with current manifest ---
echo "Step 4: Comparing with current manifest..."
. "$MANIFEST"
CURRENT_IMAGES="$TMPDIR/current_images.txt"
echo "$IMAGES" | sed 's/^[[:space:]]*//' | grep -v '^#' | grep -v '^$' | sort -u > "$CURRENT_IMAGES"

NEW_IMAGES=$(comm -23 "$TMPDIR/unique_images.txt" "$CURRENT_IMAGES")
REMOVED_IMAGES=$(comm -13 "$TMPDIR/unique_images.txt" "$CURRENT_IMAGES")

if [ -n "$NEW_IMAGES" ]; then
  echo "  NEW images (not in manifest):"
  echo "$NEW_IMAGES" | while read -r img; do echo "    + $img"; done
fi
if [ -n "$REMOVED_IMAGES" ]; then
  echo "  REMOVED images (in manifest but not discovered):"
  echo "$REMOVED_IMAGES" | while read -r img; do echo "    - $img"; done
fi
if [ -z "$NEW_IMAGES" ] && [ -z "$REMOVED_IMAGES" ]; then
  echo "  Manifest is up to date."
fi

# --- Step 5: Verify overrides ---
echo "Step 5: Running verifyNoPublicImages.sh..."
"$SCRIPT_DIR/verifyNoPublicImages.sh" "$ECR_HOST" || echo "  ⚠️  Verification found issues — update generatePrivateEcrValues.sh"

echo ""
echo "=== Done ==="
if [ -n "$NEW_IMAGES" ]; then
  echo "Action needed: Add the NEW images above to privateEcrManifest.sh"
  echo "and add corresponding overrides to generatePrivateEcrValues.sh"
fi
