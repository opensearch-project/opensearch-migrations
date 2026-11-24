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
  # Extract name|version|repo|imageTag, where imageTag is the chart's values.image.tag override
  # (e.g. localstack) so discovery reports the image that is actually deployed. Fields are unquoted
  # from copies, and commented-out entries at column 0 inside charts: do not end the section.
  awk '
    function unquote(s) { gsub(/["'\'' ]/, "", s); return s }
    function flush() {
      if (name != "" && repo != "") print name "|" ver "|" repo "|" tag
      name = ""; ver = ""; repo = ""; tag = ""; in_values = 0; in_image = 0
    }
    /^charts:/ { in_charts = 1; next }
    in_charts && /^[^ #]/ { flush(); in_charts = 0 }
    !in_charts { next }
    /^  [a-zA-Z][^ ]*:[[:space:]]*$/ { flush(); name = $1; sub(/:$/, "", name); next }
    /^    version:/ { ver = unquote($2); next }
    /^    repository:/ && /http|oci/ { repo = unquote($2); next }
    /^    [a-zA-Z]/ { in_values = ($1 == "values:"); in_image = 0; next }
    in_values && /^      [a-zA-Z]/ { in_image = ($1 == "image:"); next }
    in_image && /^        tag:/ { tag = unquote($2); next }
    END { flush() }
  ' "$vf" >> "$CHARTS_FILE"
done

echo "  Found $(wc -l < "$CHARTS_FILE" | tr -d ' ') charts"

# --- Step 2: Discover images via helm template ---
echo "Step 2: Discovering images from helm template..."
ALL_IMAGES="$TMPDIR/all_images.txt"
touch "$ALL_IMAGES"

while IFS='|' read -r name version repo image_tag; do
  [ -z "$name" ] && continue
  echo "  Pulling $name $version${image_tag:+ (image.tag $image_tag)}..."
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
    # Render once into $RENDERED, with the values.yaml image.tag override (if any) so the deployed
    # tag is reported. Some charts (e.g. opentelemetry-operator) reject their own defaults via
    # values.schema.json; keep going so one chart cannot abort discovery, and flag it.
    RENDERED="$TMPDIR/rendered.yaml"
    if ! helm template test "$dir/" ${image_tag:+--set-string "image.tag=$image_tag"} > "$RENDERED" 2>/dev/null; then
      echo "    WARN: helm template failed for $name with default values; check its images manually"
      : > "$RENDERED"
    fi
    { grep -E '^\s+image:' "$RENDERED" || true; } | \
      sed 's/.*image:\s*//' | tr -d '"' | tr -d "'" | sed 's/^[[:space:]]*//' >> "$ALL_IMAGES"
    rm -f "$RENDERED"
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
