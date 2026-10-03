# 🚀 Quickstart Guide - Migration Assistant

Get started quickly by testing out the Migration Assistant solution in a local Kubernetes cluster while utilizing test
Elasticsearch and OpenSearch clusters and the same Helm charts that can be deployed to the cloud

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

## Build the Docker images

Since we are building from source here, we will need to build the necessary Docker images for the Migration Assistant
that our K8s containers will utilize. The `kindTesting.sh` script handles kind setup, image builds (via BuildKit),
and deployment all-in-one:

```shell
./kindTesting.sh
```

## Deploy the Migration Assistant Helm chart

This will deploy our main Migration Assistant Helm chart which will create the needed resources to perform the Migration
Assistant suite of migration tooling

```shell
helm install ma -n ma charts/aggregates/migrationAssistantWithArgo \
  --create-namespace \
  -f charts/aggregates/migrationAssistantWithArgo/valuesForLocalK8s.yaml
```

To see all helm deployments for this namespace

```shell
helm -n ma list
```

To view the pods that were created and are initializing

```shell
kubectl -n ma get pods
```

## Access the Migration Console

Open a shell to the Migration Console pod

```shell
kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
```

## Cleanup

After exiting the Migration Console

```shell
migration-console (~) -> exit
```

To remove the Migration Assistant Helm deployment (and its installed chart dependencies), as well as any created
volumes:

```shell
helm uninstall -n ma ma
kubectl -n ma delete pvc --all
```

To remove the kind containers (only necessary if no longer using kind):

```shell
./kindCleanup.sh
```
