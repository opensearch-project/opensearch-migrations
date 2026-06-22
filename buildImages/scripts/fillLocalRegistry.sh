#!/bin/bash

set -eo pipefail

# NOTE: the below only creates resources if they dont already exist. If you want any of those recreated,
# need to remove first.
# Example for removing builder to rebuild in the below:
# docker buildx rm local-remote-builder 2>/dev/null || true

docker network inspect local-migrations-network >/dev/null 2>&1 || docker network create local-migrations-network

docker ps -q -f name=^docker-registry$ | grep -q . || docker run -d --restart=always \
  --name docker-registry \
  --network local-migrations-network \
  -p 5001:5000 \
  -v registry-data:/var/lib/registry \
  registry:2

docker ps -q -f name=^buildkitd$ | grep -q . || docker run -d --privileged \
  --name buildkitd \
  --dns 8.8.8.8 \
  --network local-migrations-network \
  -p 1234:1234 \
  -v ./buildkitd.toml:/etc/buildkit/buildkitd.toml \
  --restart=always \
  moby/buildkit:latest \
  --addr tcp://0.0.0.0:1234 \
  --config /etc/buildkit/buildkitd.toml

# Wait for buildkitd to accept connections before registering the builder or running builds.
echo "Waiting for buildkitd to be ready..."
timeout 60 sh -c 'until nc -z localhost 1234 2>/dev/null; do sleep 1; done' \
  || { echo "ERROR: buildkitd did not become ready within 60 seconds"; exit 1; }

docker buildx inspect local-remote-builder >/dev/null 2>&1 || docker buildx create --name local-remote-builder --driver remote tcp://localhost:1234

# NOTE: if only testing changes in solr and / or ES, can use flags -PincludeSolr666, -PexcludeESCustomTestImages params respectively
case $(uname -m) in
  aarch64) PLATFORM="arm64" ;;
  x86_64)  PLATFORM="amd64" ;;
  *)       PLATFORM=$(uname -m) ;;
esac

echo "Building general and test images"
../gradlew "buildImagesToRegistry_${PLATFORM}" "buildKitTestAll_${PLATFORM}" -PregistryEndpoint=localhost:5001 -Pbuilder=local-remote-builder -PincludeSolr773TestImage


echo "Registry contents:"
curl http://localhost:5001/v2/_catalog
echo "Custom solr tags:"
curl http://localhost:5001/v2/migrations/custom-solr/tags/list
