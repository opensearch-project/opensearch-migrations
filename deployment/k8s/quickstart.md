# 🚀 Quickstart Guide - Migration Assistant

Get started quickly by testing out the Migration Assistant solution in a local Kubernetes cluster while utilizing test
Elasticsearch and OpenSearch clusters and the same Helm charts that can be deployed to the cloud.
For Amazon EKS, see the [AWS quick start](aws/quickstarts/selfManagedEsToAmazonOpenSearch/README.md).

## What you'll need

### 🔹 Install kubectl (Kubernetes CLI)

`kubectl` is the primary command-line tool for managing and interacting with your Kubernetes cluster. Follow the
official installation guide [here](https://kubernetes.io/docs/tasks/tools/).

Kubectl autocompletion is also
recommended [here](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_completion/)

✅ Verify install

```shell
kubectl version
```

### 🔹 Install Helm (Kubernetes Package Manager)

Helm simplifies application deployment on Kubernetes by managing charts which are essentially pre-configured application
definitions. Install Helm by following the instructions [here](https://helm.sh/docs/intro/install/).

Helm autocompletion is also recommended [here](https://helm.sh/docs/helm/helm_completion_bash/)

✅ Verify install

```shell
helm version
```

### 🔹 Install Docker

Docker is essential for building container images and running a local Kubernetes cluster in this setup. Follow the setup
guide [here](https://docs.docker.com/engine/install/).

✅ Verify install

```shell
docker version
```

### 🔹 Install kind

kind will be used as the local Kubernetes cluster for this deployment, follow the official installation
instructions [here](https://kind.sigs.k8s.io/docs/user/quick-start/).

✅ Verify install

```shell
kind --version
```

## Build and deploy

`kindTesting.sh` does everything in one step:

1. Creates the kind cluster from `kindClusterConfig.yaml`.
2. Builds the Migration Assistant images into a local registry.
3. Installs the Migration Assistant chart.
4. Installs the [`testClusters`](charts/aggregates/testClusters) chart:
   - an Elasticsearch 7.10 source (`elasticsearch-oss` behind nginx, HTTPS with `admin:admin`);
   - an OpenSearch target.

```shell
./kindTesting.sh
```

The first build takes 30–60 minutes; later builds reuse the cache.

**Running it beside other kind clusters.** Set these so the run doesn't reuse or replace anything you
already have:

| Variable | Default | What it does |
|---|---|---|
| `KIND_CLUSTER_NAME` | `ma` | Cluster name; the kube context is `kind-<name>` |
| `LOCAL_IMAGE_TAG` | `latest` | Tags and deploys every image with this tag only, so other clusters' `latest` images stay untouched. |
| `KIND_LOGS_DIR` | `~/.opensearch-migrations/kind/<cluster>/logs` | Where every node's logs land on this machine, one directory per node. |
| `SKIP_LOAD_TEST_IMAGES` | `false` | `true` skips the k6 images. Use it if you won't run k6, or if your network blocks `proxy.golang.org`. |

```shell
KIND_CLUSTER_NAME=ma-test LOCAL_IMAGE_TAG=ma-test SKIP_LOAD_TEST_IMAGES=true ./kindTesting.sh
```

Two things to know:
- **Shared registry.** The script shares one registry container, `docker-registry` on port 5001,
  with every local cluster. It creates the container if it's missing and otherwise leaves it running,
  so one run doesn't interrupt other builds or clusters.
- **Let the script create the cluster.** It adds the registry configuration and the log mounts when
  it creates the cluster. If a cluster with that name already exists without them, the script stops
  and asks you to delete it, or pick another name.

**Logs.** Fluent Bit on every node writes the pods' logs to `${KIND_LOGS_DIR}/<node>/` on this
machine, and the migration console sees all of them at `/shared-logs-output`. They stay after the
cluster is deleted.

```shell
kubectl -n ma get pods          # all Running / Completed
```

## Run a migration

[`configs/localQuickStart.yaml`](configs/localQuickStart.yaml) runs the full live-migration shape
against the test clusters:

1. Snapshot the source to the localstack S3 bucket.
2. Migrate metadata and backfill the documents into the target.
3. Capture new traffic through the proxy and replay it.

The AWS
[quick start](aws/quickstarts/selfManagedEsToAmazonOpenSearch/README.md) explains each step in
more detail.

From the repository root, create the credentials the config refers to, then load the config:

```shell
printf 'admin:admin' | kubectl -n ma exec -i migration-console-0 -- workflow configure credentials create --stdin --silent source-creds
printf 'admin:admin' | kubectl -n ma exec -i migration-console-0 -- workflow configure credentials create --stdin --silent target-creds
kubectl -n ma exec -i migration-console-0 -- workflow configure edit --stdin < deployment/k8s/configs/localQuickStart.yaml
kubectl -n ma exec -it migration-console-0 -- bash
```

Inside the console, pre-load the source and then submit. The pre-load uses OpenSearch Benchmark's
test mode: about 1,000 documents in each of 10 indices, in under two minutes.

```shell
console clusters run-test-benchmarks --cluster source
workflow submit
workflow status                  # or: workflow manage
```

The snapshot waits for the capture proxy, and the replayer waits for the backfill. The whole run
takes about 20 minutes. When `Perform Snapshot Migration` has succeeded, compare the two sides:

```shell
console clusters curl target /_refresh -X POST
console clusters curl source /geonames/_count
console clusters curl target /geonames/_count
```

Then send traffic through the proxy, and check that the replayer catches up:

```shell
console clusters generate-data --cluster proxy --index-name qs-test --num-docs 5000
console clusters curl target /qs-test/_refresh -X POST
console clusters curl source /qs-test/_count
console clusters curl target /qs-test/_count
console kafka describe-consumer-group           # LAG 0 when replay has caught up
console tuples show --limit 3 --pretty          # source vs. target for the newest replayed requests
```

## Access the Migration Console

Open a shell to the Migration Console pod

```shell
kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
```

## Cleanup

Remove the migration resources from inside the console:

```shell
workflow reset --all --cascade --include-proxies --delete-storage
```

To remove just your kind cluster and its image builder:

```shell
kind delete cluster --name "${KIND_CLUSTER_NAME:-ma}"
docker buildx rm "builder-kind-${KIND_CLUSTER_NAME:-ma}"
```

`./kindCleanup.sh` (with the same `KIND_CLUSTER_NAME`) does that too, but it also removes the shared
registry container that other local clusters pull from. `KIND_PRUNE_VOLUMES=true` additionally
deletes the image cache. Either way the cluster's logs are kept; `./kindCleanup.sh --delete-logs`
removes them as well, or delete `${KIND_LOGS_DIR}` yourself.
