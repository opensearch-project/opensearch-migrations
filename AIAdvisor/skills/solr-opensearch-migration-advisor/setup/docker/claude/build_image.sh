#!/bin/bash

set -eo pipefail

SCRIPT_DIR="$(dirname "$0")"
CWD=$(pwd)
# we jump into the solr-opensearch-migration-advisor root to build docker image from there,
# to allow file copy from root within the docker file.
# Docker does not allow file copy to image from parent folders
cd "$SCRIPT_DIR"/../../.. || exit

echo "Building docker image"
docker build . -t claude_image:0.0.1 -f ./setup/docker/claude/Dockerfile

cd "$CWD" || exit