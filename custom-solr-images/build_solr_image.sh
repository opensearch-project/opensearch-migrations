#!/bin/bash

set -eo pipefail

echo "Building solr docker image for version 6.6.0"
docker build \
    --build-arg SOLR_VERSION="6.6.0" \
    --build-arg SOLR_SHA512= \
    --build-arg SOLR_SHA1="ab74116e049cfb603b84226e13d0f7c51de9aa23" \
    --build-arg SOLR_ARCHIVE_BASEPATH="https://artifacts.alfresco.com/nexus/content/repositories/public/org/apache/solr/solr" \
    -f dockerfiles/Dockerfile \
    dockerfiles/ \
    -t localhost:5001/custom-solr:6.6.0

echo "Pushing solr docker image for version 6.6.0"
docker push localhost:5001/custom-solr:6.6.0

echo "Building solr docker image for version 7.7.3"
docker build \
    --build-arg SOLR_VERSION="7.7.3" \
    --build-arg SOLR_SHA512="45461fb86851f8615f02dbc89a942facdd13ab9ca0d984eaf35ec1ed2cef653af738320945749c3130d27d5581a1f0ede34bdaf1ca9afbd4f9a631432d6ada58" \
    --build-arg SOLR_SHA1= \
    --build-arg SOLR_ARCHIVE_BASEPATH="https://archive.apache.org/dist/lucene/solr" \
    -f dockerfiles/Dockerfile \
    dockerfiles/ \
    -t localhost:5001/custom-solr:7.7.3

echo "Pushing solr docker image for version 7.7.3"
docker push localhost:5001/custom-solr:7.7.3