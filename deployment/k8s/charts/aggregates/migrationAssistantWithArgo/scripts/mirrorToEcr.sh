#!/bin/sh
# =============================================================================
# mirrorToEcr.sh
#
# Copies all required container images and helm charts from public registries
# to a private ECR registry. Run this from a machine with internet access.
#
# Usage: ./mirror-to-ecr.sh <ecr-host> [--region <region>]
# Example: ./mirror-to-ecr.sh 123456789012.dkr.ecr.us-east-2.amazonaws.com
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "$SCRIPT_DIR/privateEcrManifest.sh"

ECR_HOST="${1:-}"
shift || true
REGION="${AWS_CFN_REGION:-us-east-1}"
while [ $# -gt 0 ]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    *) shift ;;
  esac
done

if [ -z "$ECR_HOST" ]; then
  echo "Usage: $0 <ecr-host> [--region <region>]" >&2
  echo "Example: $0 123456789012.dkr.ecr.us-east-2.amazonaws.com" >&2
  exit 1
fi

# --- install crane if missing ---
if ! command -v crane >/dev/null 2>&1; then
  echo "Installing crane..."
  ARCH=$(uname -m)
  case "$ARCH" in
    x86_64|amd64) ARCH="x86_64" ;;
    aarch64|arm64) ARCH="arm64" ;;
  esac
  OS=$(uname -s)
  CRANE_DIR="${HOME}/bin"
  mkdir -p "$CRANE_DIR"
  curl -sL "https://github.com/google/go-containerregistry/releases/latest/download/go-containerregistry_${OS}_${ARCH}.tar.gz" \
    | tar xz -C "$CRANE_DIR" crane
  export PATH="${CRANE_DIR}:${PATH}"
  echo "crane installed to ${CRANE_DIR}/crane"
fi

# --- authenticate ---
echo "Authenticating with ECR ($ECR_HOST)..."
ECR_PASS=$(aws ecr get-login-password --region "$REGION")
echo "$ECR_PASS" | crane auth login "$ECR_HOST" -u AWS --password-stdin
echo "$ECR_PASS" | helm registry login "$ECR_HOST" -u AWS --password-stdin

# --- mirror container images ---
echo ""
echo "=== Mirroring container images ==="
failed_images=""
echo "$IMAGES" | while IFS= read -r image; do
  image=$(echo "$image" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
  [ -z "$image" ] && continue
  echo "$image" | grep -q '^#' && continue

  image_no_tag="${image%%:*}"
  tag="${image##*:}"
  ecr_repo="mirrored/${image_no_tag}"

  echo "  Copying $image → ${ECR_HOST}/${ecr_repo}:${tag}"
  aws ecr create-repository --repository-name "$ecr_repo" --region "$REGION" 2>/dev/null || true
  if crane copy "$image" "${ECR_HOST}/${ecr_repo}:${tag}" 2>&1; then
    echo "  ✅ $image"
  else
    echo "  ❌ FAILED: $image" >&2
    failed_images="$failed_images $image"
  fi
done

# --- mirror helm charts as OCI artifacts ---
echo ""
echo "=== Mirroring Helm charts ==="
echo "$CHARTS" | while IFS='|' read -r name version repo; do
  name=$(echo "$name" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
  version=$(echo "$version" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
  repo=$(echo "$repo" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//')
  [ -z "$name" ] && continue

  echo "  Pulling $name $version from $repo..."
  if echo "$repo" | grep -q '^oci://'; then
    helm pull "$repo/$name" --version "$version" 2>/dev/null
  else
    helm pull "$name" --repo "$repo" --version "$version" 2>/dev/null
  fi

  # Find the downloaded tgz (naming varies by chart)
  tgz=$(ls ${name}-*.tgz 2>/dev/null | head -1)
  if [ -z "$tgz" ]; then
    echo "  ❌ FAILED to download $name $version" >&2
    continue
  fi

  aws ecr create-repository --repository-name "charts/${name}" --region "$REGION" 2>/dev/null || true
  echo "  Pushing $tgz → oci://${ECR_HOST}/charts"
  helm push "$tgz" "oci://${ECR_HOST}/charts" 2>&1 || echo "  ❌ FAILED to push $name" >&2
  rm -f "$tgz"
  echo "  ✅ $name $version"
done

echo ""
echo "=== Mirroring complete ==="
