#!/bin/bash

set -eo pipefail

# script assumes registry is up and running and listening on port 5001 (see fillLocalRegistry.sh)
CWD=$(pwd)
cd ../custom-solr-images/dockerfiles
docker build . -t localhost:5001/custom-solr:6.6.0
docker push localhost:5001/custom-solr:6.6.0
cd $CWD