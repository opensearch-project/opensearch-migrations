#!/bin/bash

set -eo pipefail

SCRIPT_DIR="$(dirname "$0")"
AGENT_VOLUME_DIR="$SCRIPT_DIR"/CLAUDE_ADVISOR_VOLUME
source "$SCRIPT_DIR"/../../../.env
mkdir -p "$AGENT_VOLUME_DIR"

echo "Starting up claude container"
docker run -d --rm  \
  --name claude-container \
  -v "$AGENT_VOLUME_DIR":/home/user/claude/processing \
  -e CLAUDE_CODE_OAUTH_TOKEN="$CLAUDE_CODE_OAUTH_TOKEN" \
  claude_image:0.0.1

echo "Switching shell into container and starting up claude"
docker exec -it claude-container bash ./entrypoint.sh





