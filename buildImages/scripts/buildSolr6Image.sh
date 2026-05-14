#!/bin/bash

set -eo pipefail

# script assumes registry is up and running and listening on port 5001 (see fillLocalRegistry.sh)
docker build . -f ../custom-solr-images/dockerfiles/Dockerfile  -t localhost:5001/custom-solr:6.6.6
docker push localhost:5001/custom-solr:6.6.6