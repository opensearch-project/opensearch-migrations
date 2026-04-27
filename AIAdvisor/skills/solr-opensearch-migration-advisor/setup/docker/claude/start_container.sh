#!/bin/bash

set -eo pipefail

SCRIPT_DIR="$(dirname "$0")"
AGENT_VOLUME_DIR="$SCRIPT_DIR"/CLAUDE_ADVISOR_VOLUME
mkdir -p "$AGENT_VOLUME_DIR"

echo "Starting up containers"
docker compose -f "$SCRIPT_DIR/docker-compose.yml" --env-file "$SCRIPT_DIR/../../../.env" up -d --build

echo "Switching shell into container and starting up claude"
docker exec -it claude-container bash ./entrypoint.sh

