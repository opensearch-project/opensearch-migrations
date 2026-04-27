#!/bin/bash

set -eo pipefail

docker network inspect opensearch-migration >/dev/null 2>&1 || docker network create opensearch-migration

# Start the pricing calculator
docker run -d -p 5050 --rm --name opensearch-pricing-calculator --network opensearch-migration opensearch-pricing-calculator:2.0.0

SCRIPT_DIR="$(dirname "$0")"
AGENT_VOLUME_DIR="$SCRIPT_DIR"/CLAUDE_ADVISOR_VOLUME
source "$SCRIPT_DIR"/../../../.env
mkdir -p "$AGENT_VOLUME_DIR"

echo "Starting up claude container"
docker run -d --rm  \
  --name claude-container \
  -v "$AGENT_VOLUME_DIR":/home/user/claude/processing \
  -e CLAUDE_CODE_OAUTH_TOKEN="$CLAUDE_CODE_OAUTH_TOKEN" \
  --network opensearch-migration \
  claude_image:0.0.1

echo "Switching shell into container and starting up claude"
docker exec -it claude-container bash ./entrypoint.sh

