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
# Additional manifests can be sourced before running this script, or passed
# via --manifest <file> to mirror images for other charts (testClusters, buildImages).

ECR_HOST="${1:-}"
shift || true
REGION="${AWS_CFN_REGION:-us-east-1}"
while [ $# -gt 0 ]; do
  case "$1" in
    --region) REGION="$2"; shift 2 ;;
    --manifest)
      . "$2"
      # Merge additional charts/images if the manifest defines them
      [ -n "${TEST_CHARTS:-}" ] && CHARTS="$CHARTS
$TEST_CHARTS"
      [ -n "${TEST_IMAGES:-}" ] && IMAGES="$IMAGES
$TEST_IMAGES"
      [ -n "${BUILD_IMAGES:-}" ] && IMAGES="$IMAGES
$BUILD_IMAGES"
      shift 2 ;;
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

# Authenticate with public ECR (needed for public.ecr.aws images)
aws ecr-public get-login-password --region us-east-1 2>/dev/null | \
  crane auth login public.ecr.aws -u AWS --password-stdin 2>/dev/null || true

# --- DockerHub mirrors (tried in order for mirror.gcr.io/* images) ---
# Override: DOCKERHUB_MIRRORS="mirror.gcr.io docker.io public.ecr.aws" ./mirrorToEcr.sh ...
DOCKERHUB_MIRRORS="${DOCKERHUB_MIRRORS:-mirror.gcr.io docker.io public.ecr.aws}"

# Copies a single image to ECR, trying mirror sources for mirror.gcr.io/* images.
copy_image() {
  local image="$1" image_no_tag="${image%%:*}" tag="${image##*:}"
  local ecr_repo="mirrored/${image_no_tag}"
  local dest="${ECR_HOST}/${ecr_repo}:${tag}"

  # Build source candidates — for mirror.gcr.io/*, try each mirror
  local sources="$image"
  case "$image" in mirror.gcr.io/*)
    local path="${image#mirror.gcr.io/}" ; sources=""
    for m in $DOCKERHUB_MIRRORS; do
      # public.ecr.aws hosts official library images under docker/library/
      if [ "$m" = "public.ecr.aws" ]; then
        case "$path" in library/*) sources="${sources:+$sources }${m}/docker/${path}" ;; esac
      else
        sources="${sources:+$sources }${m}/${path}"
      fi
    done
  ;; esac

  aws ecr create-repository --repository-name "$ecr_repo" --region "$REGION" 2>/dev/null || true
  for src in $sources; do
    for attempt in 1 2 3; do
      if crane copy "$src" "$dest" 2>/dev/null; then
        [ "$src" != "$image" ] && echo "  ℹ️  $image (via $src)"
        echo "  ✅ $image"; return 0
      fi
      sleep 5
    done
  done
  echo "  ❌ $image" >&2; return 1
}

# --- mirror container images (parallel) ---
echo ""
echo "=== Mirroring container images ==="
# Write cleaned image list to temp file so we can loop without a pipe subshell
_imglist=$(mktemp)
echo "$IMAGES" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//' | grep -v '^#' | grep -v '^$' > "$_imglist"
while IFS= read -r image; do
  copy_image "$image" &
done < "$_imglist"
rm -f "$_imglist"
wait || { echo "⚠️  Some image copies failed" >&2; }

# --- mirror helm charts as OCI artifacts ---
echo ""
echo "=== Mirroring Helm charts ==="
_chartlist=$(mktemp)
echo "$CHARTS" | grep -v '^[[:space:]]*$' > "$_chartlist"
_chart_fail=0
while IFS='|' read -r name version repo; do
  name="${name#"${name%%[![:space:]]*}"}"; name="${name%"${name##*[![:space:]]}"}"
  version="${version#"${version%%[![:space:]]*}"}"; version="${version%"${version##*[![:space:]]}"}"
  repo="${repo#"${repo%%[![:space:]]*}"}"; repo="${repo%"${repo##*[![:space:]]}"}"
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
  helm push "$tgz" "oci://${ECR_HOST}/charts" 2>&1 || { echo "  ❌ FAILED to push $name" >&2; _chart_fail=1; }
  rm -f "$tgz"
  echo "  ✅ $name $version"
done < "$_chartlist"
rm -f "$_chartlist"
[ "$_chart_fail" -eq 0 ] || echo "⚠️  Some chart copies failed" >&2

echo ""
echo "=== Mirroring complete ==="
