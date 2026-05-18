#!/usr/bin/env bash
# Build and push an OCI image containing user transform files.
#
# Usage:
#   package-transforms.sh <transforms-dir> <registry-base> [tag]
#
# Example:
#   package-transforms.sh ./my-transforms \
#       123456789012.dkr.ecr.us-east-1.amazonaws.com/opensearch-migrations-transforms
#
# The printed digest-pinned reference is the value to use in transformsSources.

set -euo pipefail

SCRIPT_NAME="$(basename "${BASH_SOURCE[0]}")"

usage() {
  cat >&2 <<USAGE
Usage:
  ${SCRIPT_NAME} <transforms-dir> <registry-base> [tag]

Arguments:
  transforms-dir   Directory containing transform files to copy into the image root.
                   May be a relative or absolute path.
  registry-base    Registry/repository name without a tag.
  tag              Optional image tag. Defaults to latest.

Example:
  ${SCRIPT_NAME} ./my-transforms \\
      123456789012.dkr.ecr.us-east-1.amazonaws.com/opensearch-migrations-transforms
USAGE
}

die_with_usage() {
  echo "Error: $1" >&2
  echo >&2
  usage
  exit 2
}

if [[ $# -lt 2 ]]; then
  die_with_usage "missing required argument(s)."
fi

if [[ $# -gt 3 ]]; then
  die_with_usage "unexpected argument(s): ${*:4}"
fi

TRANSFORMS_DIR_ARG="$1"
REGISTRY_BASE="$2"
TAG="${3:-latest}"

if [[ -z "${TRANSFORMS_DIR_ARG}" ]]; then
  die_with_usage "transforms-dir must not be empty."
fi

if [[ -z "${REGISTRY_BASE}" ]]; then
  die_with_usage "registry-base must not be empty."
fi

if [[ $# -eq 3 && -z "${TAG}" ]]; then
  die_with_usage "tag must not be empty when provided."
fi

if [[ ! -d "${TRANSFORMS_DIR_ARG}" ]]; then
  die_with_usage "transforms-dir does not exist or is not a directory: ${TRANSFORMS_DIR_ARG}"
fi

TRANSFORMS_DIR="$(cd -- "${TRANSFORMS_DIR_ARG}" && pwd -P)"
DOCKERFILE_DIR="$(cd -- "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
IMAGE_REF="${REGISTRY_BASE}:${TAG}"

echo "Building transforms image from: ${TRANSFORMS_DIR}"
docker build -f "${DOCKERFILE_DIR}/Dockerfile.transforms" -t "${IMAGE_REF}" "${TRANSFORMS_DIR}"

echo "Pushing ${IMAGE_REF}..."
PUSH_OUTPUT="$(docker push "${IMAGE_REF}")"
echo "${PUSH_OUTPUT}"

DIGEST="$(echo "${PUSH_OUTPUT}" | grep -oE 'sha256:[a-f0-9]{64}' | head -1 || true)"
if [[ -z "${DIGEST}" ]]; then
  DIGEST="$(docker inspect --format='{{index .RepoDigests 0}}' "${IMAGE_REF}" \
    | grep -oE 'sha256:[a-f0-9]{64}' \
    | head -1 || true)"
fi

if [[ -z "${DIGEST}" ]]; then
  echo "Error: image was pushed but no digest could be determined for ${IMAGE_REF}" >&2
  exit 1
fi

PINNED_REF="${REGISTRY_BASE}@${DIGEST}"

cat <<OUTPUT

Transforms image pushed successfully.

Set this in your transformsSources:
  transformsSources:
    my-transforms:
      image: "${PINNED_REF}"
OUTPUT
