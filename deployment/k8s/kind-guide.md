# kind Local Deployment Guide

Install prerequisites, including `kubectl`, `helm`, and `kind`.

This script creates a `kind` cluster, starts or reuses the shared docker-hosted `docker-registry` container at
`localhost:5001`, starts or reuses an external `buildkitd` container, builds the project images into that registry, and
installs the helm charts.

```bash
echo "Will create/reuse a kind cluster, build images, and install the MA helm chart for those images"
$(git rev-parse --show-toplevel)/deployment/k8s/kindTesting.sh
```

To fully clean up the `kind` test environment created by that workflow, including the docker-hosted registry container,
docker-hosted BuildKit container, and builder state, run:

```bash
$(git rev-parse --show-toplevel)/deployment/k8s/kindCleanup.sh
```

The external `docker-registry` and `buildkitd` containers now use named Docker volumes, so deleting the containers does
not delete their stored images or BuildKit cache. To force a cold reset of those volumes too, run:

```bash
KIND_PRUNE_VOLUMES=true $(git rev-parse --show-toplevel)/deployment/k8s/kindCleanup.sh
```

Run this to open the migration-console terminal so that you can run
`workflow commands`

```bash
kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
```

To forward deployed services' ports from kind to your localhost.  
E.g. so that http://localhost:2746/ will load the argo web-ui, etc, run

```bash
$(git rev-parse --show-toplevel)/deployment/k8s/forwardAllServicePorts.sh
```

## Test Clusters

By default, the `kindTesting.sh` script installs an **Elasticsearch/OpenSearch**
source/target test cluster alongside the Migration Assistant chart. Two flags let you change that:

```bash
# Test against a Solr source cluster instead of Elasticsearch
$(git rev-parse --show-toplevel)/deployment/k8s/kindTesting.sh --source=solr

# Only install the Migration Assistant chart; skip test clusters entirely
$(git rev-parse --show-toplevel)/deployment/k8s/kindTesting.sh --no-test-clusters
```

The same behavior can also be controlled with environment variables (`TEST_CLUSTERS_SOURCE=elasticsearch|solr` and
`INSTALL_TEST_CLUSTERS=true|false`), which is convenient for CI or other scripted invocations.

The Migration Assistant chart and the test clusters chart install in **parallel**. A failure installing one does not
block or fail the other — both results are reported in a summary once both installs finish, and the script only exits
non-zero if at least one of them failed.

You may also install the test clusters with different configurations. For that, see the
[Helm charts test clusters documentation](./charts/aggregates/testClusters/README.md).

## Install kind

Install `kind` from the upstream release or your package manager of choice. The `kindTesting.sh` script assumes the
cluster is created from
[kindClusterConfig.yaml](kindClusterConfig.yaml), which configures kind to pull project images from the local registry
mirror at `localhost:5001`.

If you want the kind cluster to run on OrbStack instead of Docker Desktop, switch the active Docker context before
running the script so `docker` and
`kind` target the same backend.
