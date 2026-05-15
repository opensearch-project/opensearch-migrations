#!/bin/bash
# =============================================================================
# mirrorToEcr.sh
#
# Copies all required container images and helm charts from public registries
# to a private ECR registry. Run this from a machine with internet access.
#
# Can be run standalone or sourced by aws-bootstrap.sh (which calls
# mirror_images_to_ecr and mirror_charts_to_ecr directly).
#
# Usage: ./mirrorToEcr.sh <ecr-host> [--region <region>]
# Example: ./mirrorToEcr.sh 123456789012.dkr.ecr.us-east-2.amazonaws.com
# =============================================================================
set -euo pipefail

# --- install crane if missing ---
ensure_crane() {
  command -v crane >/dev/null 2>&1 && return
  echo "Installing crane..."
  local arch os crane_dir
  arch=$(uname -m)
  case "$arch" in x86_64|amd64) arch="x86_64" ;; aarch64|arm64) arch="arm64" ;; esac
  os=$(uname -s)
  crane_dir="${HOME}/bin"
  mkdir -p "$crane_dir"
  curl -sL "https://github.com/google/go-containerregistry/releases/latest/download/go-containerregistry_${os}_${arch}.tar.gz" \
    | tar xz -C "$crane_dir" crane
  export PATH="${crane_dir}:${PATH}"
  echo "crane installed to ${crane_dir}/crane"
}

# Registry hostname → ECR pull-through cache prefix (matches PullThroughCacheHelper.groovy)
# Args: <ptc-endpoint> <image>
ptc_rewrite() {
  local ptc="$1" image="$2"
  [ -z "$ptc" ] && return 1
  local path prefix
  case "$image" in
    docker.io/*)            prefix="docker-hub";          path="${image#docker.io/}" ;;
    registry-1.docker.io/*) prefix="docker-hub";          path="${image#registry-1.docker.io/}" ;;
    mirror.gcr.io/*)        prefix="docker-hub";          path="${image#mirror.gcr.io/}" ;;
    public.ecr.aws/*)       prefix="ecr-public";          path="${image#public.ecr.aws/}" ;;
    ghcr.io/*)              prefix="github-container-registry"; path="${image#ghcr.io/}" ;;
    registry.k8s.io/*)      prefix="k8s";                 path="${image#registry.k8s.io/}" ;;
    quay.io/*)              prefix="quay";                path="${image#quay.io/}" ;;
    *) return 1 ;;
  esac
  echo "${ptc}/${prefix}/${path}"
}

# Copy a single image to ECR, trying pull-through cache then mirror sources.
# Args: <ecr-host> <region> <dockerhub-mirrors> <ptc-endpoint> <image>
copy_image() {
  local ecr_host="$1" region="$2" dockerhub_mirrors="$3" ptc="$4" image="$5"
  local image_no_tag="${image%%:*}" tag="${image##*:}"
  local ecr_repo="mirrored/${image_no_tag}"
  local dest="${ecr_host}/${ecr_repo}:${tag}"

  # Build source candidates — for mirror.gcr.io/*, try each mirror
  local sources="$image"
  case "$image" in mirror.gcr.io/*)
    local path="${image#mirror.gcr.io/}" ; sources=""
    for m in $dockerhub_mirrors; do
      if [ "$m" = "public.ecr.aws" ]; then
        case "$path" in library/*) sources="${sources:+$sources }${m}/docker/${path}" ;; esac
      else
        sources="${sources:+$sources }${m}/${path}"
      fi
    done
  ;; esac

  aws ecr create-repository --repository-name "$ecr_repo" --region "$region" 2>/dev/null || true

  # Prepend pull-through cache source if available (fastest path — same-region ECR to ECR)
  local ptc_src
  ptc_src=$(ptc_rewrite "$ptc" "$image") && sources="$ptc_src ${sources}"

  for src in $sources; do
    for attempt in 1 2 3 4 5; do
      if crane copy "$src" "$dest" 2>/dev/null; then
        [ "$src" != "$image" ] && echo "  ℹ️  $image (via $src)"
        echo "  ✅ $image"; return 0
      fi
      sleep $((5 * 2**(attempt-1)))  # exponential backoff: 5s, 10s, 20s, 40s, 80s
    done
  done
  echo "  ❌ $image" >&2; return 1
}

# Mirror container images to ECR (parallel).
# Args: <ecr-host> <region> <images-string>
mirror_images_to_ecr() {
  local ecr_host="$1" region="$2" images="$3"
  local dockerhub_mirrors="${DOCKERHUB_MIRRORS:-mirror.gcr.io docker.io public.ecr.aws}"

  ensure_crane

  echo "Authenticating with ECR ($ecr_host)..."
  local ecr_pass
  ecr_pass=$(aws ecr get-login-password --region "$region")
  echo "$ecr_pass" | crane auth login "$ecr_host" -u AWS --password-stdin

  aws ecr-public get-login-password --region us-east-1 2>/dev/null | \
    crane auth login public.ecr.aws -u AWS --password-stdin 2>/dev/null || true

  # ECR pull-through cache (optional, from Jenkins host environment)
  local ptc="${ECR_PULL_THROUGH_ENDPOINT:-}"
  if [ -n "$ptc" ]; then
    echo "ECR pull-through cache enabled: $ptc"
    aws ecr get-login-password --region "$region" 2>/dev/null | \
      crane auth login "$ptc" -u AWS --password-stdin 2>/dev/null || true
  fi

  echo ""
  echo "=== Mirroring container images ==="
  local _imglist _max_jobs=4 _failfile
  _imglist=$(mktemp)
  _failfile=$(mktemp)
  echo "$images" | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//' | grep -v '^#' | grep -v '^$' > "$_imglist"
  while IFS= read -r image; do
    # Throttle to $_max_jobs concurrent copies
    while [ "$(jobs -rp | wc -l)" -ge "$_max_jobs" ]; do
      wait -n 2>/dev/null || true
    done
    ( copy_image "$ecr_host" "$region" "$dockerhub_mirrors" "$ptc" "$image" || echo "$image" >> "$_failfile" ) &
  done < "$_imglist"
  rm -f "$_imglist"
  wait
  if [ -s "$_failfile" ]; then
    echo "" >&2
    echo "❌ The following image copies failed:" >&2
    sed 's/^/  - /' "$_failfile" >&2
    echo "" >&2
    echo "If this is unexpected, please open an issue at:" >&2
    echo "  https://github.com/opensearch-project/opensearch-migrations/issues/new" >&2
    rm -f "$_failfile"
    exit 1
  fi
  rm -f "$_failfile"
}

# Mirror helm charts to ECR as OCI artifacts.
# Args: <ecr-host> <region> <charts-string>
mirror_charts_to_ecr() {
  local ecr_host="$1" region="$2" charts="$3"

  ensure_crane

  local ecr_pass
  ecr_pass=$(aws ecr get-login-password --region "$region")
  echo "$ecr_pass" | helm registry login "$ecr_host" -u AWS --password-stdin

  # Log helm into public.ecr.aws so pulls of OCI charts hosted there
  # (e.g. aws-controllers-k8s/acmpca-chart) don't stall on anonymous
  # rate-limits / credential prompts. Best-effort — anonymous pulls
  # usually work, but authenticated pulls are more reliable.
  aws ecr-public get-login-password --region us-east-1 2>/dev/null | \
    helm registry login public.ecr.aws -u AWS --password-stdin 2>/dev/null || true

  echo ""
  echo "=== Mirroring Helm charts ==="
  local _chartlist _chart_fail=0
  _chartlist=$(mktemp)
  echo "$charts" | grep -v '^[[:space:]]*$' > "$_chartlist"
  while IFS='|' read -r name version repo; do
    name="${name#"${name%%[![:space:]]*}"}"; name="${name%"${name##*[![:space:]]}"}"
    version="${version#"${version%%[![:space:]]*}"}"; version="${version%"${version##*[![:space:]]}"}"
    repo="${repo#"${repo%%[![:space:]]*}"}"; repo="${repo%"${repo##*[![:space:]]}"}"
    [ -z "$name" ] && continue

    echo "  Pulling $name $version from $repo..."
    # Redirect stdin from /dev/null so helm can never block on a credential
    # prompt — if auth is missing/expired we want a fast failure, not a hang.
    if echo "$repo" | grep -q '^oci://'; then
      helm pull "$repo/$name" --version "$version" </dev/null 2>/dev/null
    else
      helm pull "$name" --repo "$repo" --version "$version" </dev/null 2>/dev/null
    fi

    local tgz
    tgz=$(ls ${name}-*.tgz 2>/dev/null | head -1)
    if [ -z "$tgz" ]; then
      echo "  ❌ FAILED to download $name $version" >&2
      continue
    fi

    aws ecr create-repository --repository-name "charts/${name}" --region "$region" 2>/dev/null || true
    echo "  Pushing $tgz → oci://${ecr_host}/charts"
    helm push "$tgz" "oci://${ecr_host}/charts" </dev/null 2>&1 || { echo "  ❌ FAILED to push $name" >&2; _chart_fail=1; }
    rm -f "$tgz"
    echo "  ✅ $name $version"
  done < "$_chartlist"
  rm -f "$_chartlist"
  [ "$_chart_fail" -eq 0 ] || echo "⚠️  Some chart copies failed" >&2

  echo ""
  echo "=== Mirroring complete ==="
}

# --- CLI entrypoint (only when run directly, not sourced) ---
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  . "$SCRIPT_DIR/privateEcrManifest.sh"

  ECR_HOST="${1:-}"
  shift || true
  REGION="${AWS_CFN_REGION:-us-east-1}"
  while [ $# -gt 0 ]; do
    case "$1" in
      --region) REGION="$2"; shift 2 ;;
      --manifest)
        . "$2"
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
    exit 1
  fi

  mirror_images_to_ecr "$ECR_HOST" "$REGION" "$IMAGES"
  mirror_charts_to_ecr "$ECR_HOST" "$REGION" "$CHARTS"
fi
