# Docker Solution — Local CDC Dev Environment

A lightweight docker-compose stack for testing Capture-and-Replay (CDC) locally without Kubernetes.

## Prerequisites

Docker images must be available in the local Docker daemon before running `composeUp`.
The `dockerHostedBuildkit.sh` script sets up a local Docker registry and BuildKit builder:

```bash
# From the repo root — set up registry + builder, build images, pull into local daemon
export KUBE_CONTEXT="compose-local"
export EXTERNAL_REGISTRY_PORT=5002  # avoid conflict with minikube's port 5001
source buildImages/backends/dockerHostedBuildkit.sh
setup_build_backend

PLATFORM=$(uname -m | sed 's/x86_64/amd64/;s/arm64/arm64/')
./gradlew :buildImages:buildImagesToRegistry_${PLATFORM} \
  -Pbuilder="builder-${KUBE_CONTEXT}" \
  -PregistryEndpoint="localhost:${EXTERNAL_REGISTRY_PORT}" \
  -PlocalContainerRegistryEndpoint="${EXTERNAL_REGISTRY_NAME}:5000" \
  -x test

for image in capture_proxy migration_console traffic_replayer elasticsearch_searchguard reindex_from_snapshot; do
  docker pull "localhost:${EXTERNAL_REGISTRY_PORT}/migrations/${image}:latest"
  docker tag "localhost:${EXTERNAL_REGISTRY_PORT}/migrations/${image}:latest" "migrations/${image}:latest"
done
```

## Running

```bash
# From the repo root
./gradlew :TrafficCapture:dockerSolution:composeUp

# Tear down
./gradlew :TrafficCapture:dockerSolution:composeDown
```

## Services

| Service | Port | Description |
|---------|------|-------------|
| capture-proxy | 9200 | Capture Proxy — send requests here to capture traffic |
| elasticsearch | 19200 | ES 7.10 source cluster (SearchGuard TLS, admin/admin) |
| opensearchtarget | 29200 | OpenSearch 2.15 target cluster (admin/myStrongPassword123!) |
| kafka | 9092 | Kafka broker for captured traffic |
| replayer | — | Traffic Replayer — replays captured traffic to target |
| migration-console | — | Migration Console with CLI tools |

### Interacting

Route calls through the Capture Proxy (traffic is captured and replayed to the target):
```sh
curl --insecure https://localhost:9200/_cat/indices -u 'admin:admin'
```

Bypass the proxy and hit the source cluster directly:
```sh
curl --insecure https://localhost:19200/_cat/indices -u 'admin:admin'
```

Hit the target cluster directly:
```sh
curl --insecure https://localhost:29200/_cat/indices -u 'admin:myStrongPassword123!'
```

Open a shell on the migration console:
```sh
docker exec -it $(docker ps --filter "name=migration-console" -q) bash
```

## Compatibility

The tools in this directory require Java 11-17. The version is specified in `TrafficCapture/build.gradle`
using a Java toolchain.

## Development

### Updating Pipfile

When updating Pipfiles, ensure your local pipenv version matches the version pinned in the Dockerfiles, then regenerate all lock files:
```bash
  EXPECTED_VERSION=$(grep -o 'pipenv==[0-9.]*' src/main/docker/elasticsearchTestConsole/Dockerfile | head -1 | sed 's/pipenv==//') && \
  ACTUAL_VERSION=$(pipenv --version | sed 's/[^0-9.]//g') && \
  if [ "$EXPECTED_VERSION" != "$ACTUAL_VERSION" ]; then echo "ERROR: pipenv version mismatch - expected $EXPECTED_VERSION, got $ACTUAL_VERSION. Run: pip install pipenv==$EXPECTED_VERSION" && exit 1; fi && \
  find . -type f -name Pipfile -execdir bash -c 'PIPENV_IGNORE_VIRTUALENVS=true pipenv lock --python 3.11' \;
```
