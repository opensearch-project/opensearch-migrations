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

## Deploy the Migration Assistant Helm chart

This will deploy our main Migration Assistant Helm chart which will create the needed resources to perform the Migration Assistant suite of migration tooling

```shell
helm install ma -n ma charts/aggregates/migrationAssistantWithArgo --create-namespace
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

To remove the Migration Assistant Helm deployment (and its installed chart dependencies), as well as any created volumes:

```shell
helm uninstall -n ma ma
kubectl -n ma delete pvc --all
```

To remove the Minikube container (only necessary if no longer using Minikube):

```shell
./minikubeLocal.sh --delete
```
