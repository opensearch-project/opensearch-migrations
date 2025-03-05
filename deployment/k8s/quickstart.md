# 🚀 Quickstart Guide - Migration Assistant

Get started quickly by testing out the Migration Assistant solution in a local Kubernetes cluster while utilizing test Elasticsearch and OpenSearch clusters and the same Helm charts that can be deployed to the cloud



## What you'll need

### 🔹 Install kubectl (Kubernetes CLI)

`kubectl` is the primary command-line tool for managing and interacting with your Kubernetes cluster. Follow the official installation guide [here](https://kubernetes.io/docs/tasks/tools/).

Kubectl autocompletion is also recommended [here](https://kubernetes.io/docs/reference/kubectl/generated/kubectl_completion/)

✅ Verify install

```shell
kubectl version
```


### 🔹 Install Helm (Kubernetes Package Manager)

Helm simplifies application deployment on Kubernetes by managing charts which are essentially pre-configured application definitions. Install Helm by following the instructions [here](https://helm.sh/docs/intro/install/).

Helm autocompletion is also recommended [here](https://helm.sh/docs/helm/helm_completion_bash/)

✅ Verify install

```shell
helm version
```


### 🔹 Install Docker

Docker is essential for building container images and running a local Kubernetes cluster in this setup. Follow the setup guide [here](https://docs.docker.com/engine/install/).

✅ Verify install

```shell
docker version
```


### 🔹 Install Minikube

Minikube will be used as the local Kubernetes cluster for this deployment, follow the official installation instructions [here](https://minikube.sigs.k8s.io/docs/start/?arch=%2Fmacos%2Farm64%2Fstable%2Fbinary+download).

✅ Verify install

```shell
minikube version
```


## Start your local Kubernetes cluster

To get started we will utilize Minikube as our local Kubernetes environment. It will function very similarly to other Kubernetes environments like EKS in AWS.  We should first move to the K8s directory for the remainder of the commands

```shell
cd deployment/k8s
```

The following wrapper script command will start Minikube, which will create a minikube container in Docker within which the Kubernetes environment will live.

```shell
./minikubeLocal.sh --start
```


## Build the Docker images

Since we are building from source here, we will need to build the necessary Docker images for the Migration Assistant that our K8s containers will utilize. An important point to note is that Minikube will use its own Docker registry separate from that of your local machine, this means it will have its own set of images separate from that of your local.

```shell
./buildDockerImagesMini.sh
```

If you are ever curious what images are in your Minikube environment the following command will list the images:

```shell
minikube image ls
```



## Update Helm Chart dependencies

As you can see from the charts/ directory for our various component and aggregate charts, Helm will package and place its dependent charts in this directory. The following helper script will go through each of our charts and perform the helm dependency update  command so that the required dependencies are in place to deploy the desired charts

```shell
./update_deps.sh
```


## Deploy the Migration Assistant Helm chart

This will deploy our main Migration Assistant Helm chart which will create the needed resources to perform the Migration Assistant suite of migration tooling

```shell
helm install ma -n ma charts/aggregates/migrationAssistant --create-namespace
```

To see all helm deployments for this namespace

```shell
helm -n ma list
```

To view the pods that were created and are initializing

```shell
kubectl -n ma get pods
```


## Deploy test clusters with Helm chart

Next to simulate an actual migration environment we should create both a source cluster and target cluster that we will migrate data between . The below chart will create an Elasticsearch 7.10 source cluster and an OpenSearch 2.16 target cluster, but could be supplied different values to customize the source and target versions or settings by modifying the /charts/aggregates/testClusters/values.yaml . We don’t need to wait for the Migration Assistant pods to finish initializing before deploying our test clusters with the below command.

```shell
helm install tc -n ma charts/aggregates/testClusters
```


## Access the Migration Console

Open a shell to the Migration Console pod

```shell
kubectl -n ma exec --stdin --tty ma-migration-console-<pod_id> -- /bin/bash
```

The `<id>` here can easily be replaced with autocomplete or retrieved from the pod name when executing:

```shell
kubectl -n ma get pods
```


## Ingest test data into source cluster

From the Migration Console this could be done with the default OpenSearch Benchmark workloads

```shell
console clusters run-test-benchmarks
```

Or by manually ingesting data

```shell
console clusters curl source_cluster -XPUT /my-test-index/_doc/1 -H "Content-Type: application/json" -d '{"message": "Hello, world!"}'
```

The current indices and documents for both clusters can then be viewed with

```shell
console clusters cat-indices
```

## Create a Snapshot

Before performing a Metadata or Backfill migration, we should first create a snapshot of our source cluster which will be utilized by both migrations and remove any need for these migrations to send traffic to the source cluster

```shell
console snapshot create
```

The status of the snapshot being created can be viewed with

```shell
console snapshot status
```


## Perform Metadata Migration

Often as an initial migration, we can perform a Metadata migration to migrate metadata, such as index settings, to the target cluster

```shell
console metadata migrate
```

The migrated index metadata can then be viewed with

```shell
console clusters cat-indices
```


## Perform Backfill Migration

Once ready, the backfill migration can be triggered to start

```shell
console backfill start
```

As the backfill migration is in progress, we can check the documents being migrated to the target cluster

```shell
console clusters cat-indices --refresh
```

Once the backfill migration is completed, we can stop the backfill process

```shell
console backfill stop
```


## Cleanup

After exiting the Migration Console

```shell
migration-console (~) -> exit
```

To remove both our Migration Assistant and Test Clusters Helm deployments:

```shell
helm uninstall -n ma ma tc
```

To remove the Minikube container:

```shell
./minikubeLocal.sh --delete
```
