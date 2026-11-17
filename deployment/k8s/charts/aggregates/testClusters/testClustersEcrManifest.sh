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

# testClusters container images. The ES source in valuesOss.yaml / valuesEks.yaml uses
# elasticsearch-oss and nginx; its init container also downloads the repository-s3 plugin from
# artifacts.elastic.co, which an isolated VPC must stage separately.
TEST_IMAGES="
docker.elastic.co/elasticsearch/elasticsearch:8.5.1
docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2
public.ecr.aws/nginx/nginx:1.30-alpine
mirror.gcr.io/opensearchproject/opensearch:2.19.1
mirror.gcr.io/library/busybox:latest
"
