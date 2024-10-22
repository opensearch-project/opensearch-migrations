# Kubernetes Deployment

## Prerequisites 

#### Install kubectl
Follow instructions [here](https://kubernetes.io/docs/tasks/tools/) to install the Kubernetes command-line tool. This will be the go-to tool for interacting with the Kubernetes cluster

#### Install helm
Follow instructions [here](https://helm.sh/docs/intro/install/) to install helm. helm will be used for deploying to the Kubernetes cluster

#### Install docker
Follow instructions [here](https://docs.docker.com/engine/install/) to set up Docker. Docker will be used to build Docker images as well as run a local Kubernetes cluster. Later versions are recommended.


## Local Kubernetes Cluster
Creating a local Kubernetes cluster is useful for testing and developing a given deployment. There are a few different tools for running a Kubernetes cluster locally. This documentation focuses on using [Minikube](https://github.com/kubernetes/minikube) to run the local Kubernetes cluster.

### Install Minikube
Follow instructions [here](https://minikube.sigs.k8s.io/docs/start/?arch=%2Fmacos%2Fx86-64%2Fstable%2Fbinary+download) to install Minikube

### Loading Docker images into Minikube
Since Minikube uses a different Docker registry than the normal host machine, the Docker images shown will differ from that on the host machine. The script `buildDockerImagesMini.sh` in this directory will configure the environment to use the Minikube Docker registry and build the Docker images into Minikube

Show Docker images available to Minikube
```shell
minikube image ls
```
Build Docker images into Minikube
```shell
./buildDockerImagesMini.sh
```

### Start/Pause/Delete
A convenience script `minikubeLocal.sh` is located in this directory which wraps the Minikube commands to start/pause/delete Minikube. This is useful for automatically handling items such as mounting the local repo and creating a tunnel to make localhost calls to containers
```shell
./miniKubeLocal.sh --start
./miniKubeLocal.sh --pause
./miniKubeLocal.sh --delete
```


## Deploying

### Full environment
Guide for deploying a complete environment helm chart comprised of many Migration service helm charts

The full environment helm charts consists of:
* Source cluster
* Target cluster
* Migration services

**Note**: For first-time deployments and deployments after changes have been made to a dependent helm package, such as the `migration-console` chart, the following command is needed to update dependent charts
```shell
helm dependency update environments/full-environment
```

The full environment helm chart can be deployed with the helm command
```shell
helm install local environments/full-environment
```

### Specific services
Guide for deploying an individual Migration service helm chart

Most migration services have a dependency on Persistent Volumes that can be installed to the Kubernetes cluster using the following commands
```shell
helm install shared-logs shared/shared-logs-vol
helm install snapshot-vol shared/snapshot-vol
```

A particular service could then be deployed with a command similar to the below.
```shell
helm install migration-console migration-console
```

## Uninstalling
To show all helm deployments
```shell
helm list
```

To uninstall a particular helm deployment
```shell
helm uninstall <deployment_name>
```
