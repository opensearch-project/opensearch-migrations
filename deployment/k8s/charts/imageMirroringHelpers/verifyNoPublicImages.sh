#!/bin/sh
# =============================================================================
# verifyNoPublicImages.sh
#
# Verifies that helm template output with ECR values has no references to
# public registries. Run after generating private-ecr-values.yaml.
#
# Usage: ./verify-no-public-images.sh <ecr-host>
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHART_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ECR="${1:-}"

if [ -z "$ECR" ]; then
  echo "Usage: $0 <ecr-host>" >&2
  exit 1
fi

VALUES_FILE=$(mktemp)
"$SCRIPT_DIR/generatePrivateEcrValues.sh" "$ECR" > "$VALUES_FILE"

echo "Rendering helm template with ECR values..."
RENDERED=$(helm template test "$CHART_DIR" \
  -f "$CHART_DIR/values.yaml" \
  -f "$CHART_DIR/valuesEks.yaml" \
  -f "$VALUES_FILE" \
  --set stageName=verify --set aws.region=us-east-1 --set aws.account=123456789012 \
  2>/dev/null)

# Public registries that should NOT appear in image references
PUBLIC_REGISTRIES="docker.io|quay.io|registry.k8s.io|ghcr.io|mirror.gcr.io|gcr.io|cr.fluentbit.io|reg.kyverno.io|public.ecr.aws"

echo ""
echo "Checking for public registry references in image: fields..."
VIOLATIONS=$(echo "$RENDERED" | grep -E '^\s+image:' | grep -Ev "$ECR" | grep -E "$PUBLIC_REGISTRIES" || true)

rm -f "$VALUES_FILE"

if [ -n "$VIOLATIONS" ]; then
  echo ""
  echo "❌ FAIL: Found public registry references:"
  echo "$VIOLATIONS" | sort -u | while read -r line; do
    echo "  $line"
  done
  echo ""
  echo "These images need overrides in generatePrivateEcrValues.sh"
  exit 1
else
  echo "✅ PASS: All image references point to $ECR"
  # Also show a count
  TOTAL=$(echo "$RENDERED" | grep -cE '^\s+image:' || true)
  ECR_COUNT=$(echo "$RENDERED" | grep -E '^\s+image:' | grep -c "$ECR" || true)
  echo "  $ECR_COUNT/$TOTAL image references use private ECR"
fi
