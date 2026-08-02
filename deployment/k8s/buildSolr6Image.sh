#!/bin/bash

set -eo pipefail

# script assumes registry is up and running and listening on port 5001 (see fillLocalRegistry.sh)
MIGRATIONS_REPO_ROOT_DIR="$(git rev-parse --show-toplevel)"
docker build "$MIGRATIONS_REPO_ROOT_DIR/custom-solr-images/dockerfiles" -t localhost:5001/custom-solr:6.6.0
docker push localhost:5001/custom-solr:6.6.0
