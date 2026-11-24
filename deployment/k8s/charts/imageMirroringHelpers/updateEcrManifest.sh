#!/bin/bash
# =============================================================================
# updateEcrManifest.sh
#
# Discovers all container images from all helm charts (migrationAssistantWithArgo
# + testClusters), compares them with private-ecr-manifest.yaml, and runs
# verifyNoPublicImages.sh.
#
# Designed to run autonomously after chart version bumps. Can be invoked by an
# agent or developer.
#
# Usage: ./updateEcrManifest.sh [--ecr-host <host>] [--dry-run]
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/../aggregates/migrationAssistantWithArgo" && pwd)"
TEST_CHART_DIR="$(cd "$SCRIPT_DIR/../aggregates/testClusters" && pwd)"
MANIFEST="$CHART_DIR/infra/mirror/private-ecr-manifest.yaml"
ECR_HOST="${ECR_HOST:-123456789012.dkr.ecr.us-east-1.amazonaws.com}"
DRY_RUN=false

manifest_section() {
  local manifest="$1" section="$2"
  awk -v section="$section" '
    /^[A-Za-z0-9_-]+:[[:space:]]*$/ {
      if (in_section) {
        exit
      }
      in_section = ($1 == section ":")
      next
    }
    in_section {
      original = $0
      sub(/^[[:space:]]*-[[:space:]]*/, "", $0)
      if ($0 == original) {
        next
      }
      sub(/[[:space:]]*#.*$/, "", $0)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", $0)
      if ($0 == "") {
        next
      }
      if ($0 ~ /^".*"$/ || $0 ~ /^'\''.*'\''$/) {
        $0 = substr($0, 2, length($0) - 2)
      }
      print
    }
  ' "$manifest"
}

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
echo "  3. Add known runtime images (strimzi kafka, etc.)"
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
  # Extract name|version|repo triples. Strip quotes from a copy of $2: editing $2 in place rebuilds
  # $0 without its indentation, which then matches the top-level-key rule and ends the charts section.
  awk '
    /^  [a-zA-Z].*:$/ && in_charts { name=$1; gsub(/:$/,"",name) }
    /version:/ && in_charts && name { ver=$2; gsub(/["'\'' ]/,"",ver) }
    /repository:/ && in_charts && name && /http|oci/ { repo=$2; gsub(/["'\'' ]/,"",repo); print name"|"ver"|"repo; name="" }
    /^charts:/ { in_charts=1 }
    /^[^ #]/ && !/^charts:/ { in_charts=0 }
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
  # Untar into a per-chart directory: the archive is named after the chart, which can differ from
  # the values.yaml key (e.g. ack-cloudwatch-controller is the cloudwatch-chart OCI artifact).
  pull_dir="$TMPDIR/pull-$name"
  rm -rf "$pull_dir"
  mkdir -p "$pull_dir"
  if echo "$repo" | grep -q '^oci://'; then
    # OCI repositories in values.yaml are full chart references, passed to helm as-is by installJob.yaml.
    helm pull "$repo" --version "$version" --untar --untardir "$pull_dir" 2>/dev/null || { echo "    WARN: could not pull $name"; continue; }
  else
    helm pull "$name" --repo "$repo" --version "$version" --untar --untardir "$pull_dir" 2>/dev/null || { echo "    WARN: could not pull $name"; continue; }
  fi
  dir=$(find "$pull_dir" -mindepth 1 -maxdepth 1 -type d | head -1)
  if [ -n "$dir" ]; then
    # Some charts (e.g. opentelemetry-operator) reject their own defaults via values.schema.json; keep
    # going so one chart cannot abort discovery under set -e, and flag it for a manual check.
    if ! rendered=$(helm template test "$dir/" 2>/dev/null); then
      echo "    WARN: helm template failed for $name with default values; check its images manually"
      rendered=""
    fi
    printf '%s\n' "$rendered" | grep -E '^\s+image:' | \
      sed 's/.*image:\s*//' | tr -d '"' | tr -d "'" | sed 's/^[[:space:]]*//' >> "$ALL_IMAGES" || true
  fi
  rm -rf "$pull_dir"
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
public.ecr.aws/aws-observability/aws-otel-collector:v0.49.0
mirror.gcr.io/amazon/aws-cli:2.25.11
mirror.gcr.io/amazon/aws-cli:latest
RUNTIME

# Deduplicate and sort
sort -u "$ALL_IMAGES" | grep -v '^$' > "$TMPDIR/unique_images.txt"
DISCOVERED=$(wc -l < "$TMPDIR/unique_images.txt" | tr -d ' ')
echo "  Discovered $DISCOVERED unique images"

# --- Step 4: Compare with current manifest ---
echo "Step 4: Comparing with current manifest..."
CURRENT_IMAGES="$TMPDIR/current_images.txt"
manifest_section "$MANIFEST" images | sort -u > "$CURRENT_IMAGES"

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
  echo "Action needed: Add the NEW images above to private-ecr-manifest.yaml"
  echo "and add corresponding overrides to generatePrivateEcrValues.sh"
fi
