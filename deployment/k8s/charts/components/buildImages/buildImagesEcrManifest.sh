#!/bin/sh
# =============================================================================
# buildImagesEcrManifest.sh
#
# Images required by the buildImages chart for building MA container images.
# Separate from the production manifest — only needed for --build-images.
# =============================================================================

BUILD_IMAGES="
docker.io/moby/buildkit:v0.22.0
"
