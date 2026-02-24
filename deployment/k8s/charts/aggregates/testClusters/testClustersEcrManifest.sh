#!/bin/sh
# =============================================================================
# testClustersEcrManifest.sh
#
# Version-locked list of container images and helm charts required by the
# testClusters chart. Separate from the production manifest.
# =============================================================================

# testClusters helm charts: "name|version|repository"
TEST_CHARTS="
elasticsearch|8.5.1|https://helm.elastic.co
opensearch|2.32.0|https://opensearch-project.github.io/helm-charts/
"

# testClusters container images
TEST_IMAGES="
docker.elastic.co/elasticsearch/elasticsearch:8.5.1
docker.io/opensearchproject/opensearch:2.19.1
docker.io/library/busybox:latest
"
