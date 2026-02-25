# Kubernetes Deployment

Audience: This document is meant for **DEVELOPERS** looking to build/maintain a Kubernetes deployment of the Migration Assistant tools from this (opensearch-migrations) repository to support customers that want to Migrate their configurations and data from a source cluster to a target cluster.  End-users should consult the [project's wiki](https://github.com/opensearch-project/opensearch-migrations/wiki) for detailed instructions to deploy and operate a production environment.

This README focuses on the helm installation of the [Migration Assistant](charts/aggregates/migrationAssistantWithArgo) chart, which will install the migration console and resources required for it to perform migrations (e.g. Argo Workflows, metrics collectors, etc). 


## Quick Start

### EKS

If you're looking to use EKS as your Kubernetes cluster, follow the
[instructions here](aws/README.md).

### Minikube

Install prerequisites (see below).

This script will start minikube, build the images from source, and will install
the Migration Assistant Helm chart alongside a test chart that creates a source
and target cluster.

```bash
echo "Will start minikube, build images, and install the MA helm chart for those images"
$(git rev-parse --show-toplevel)/deployment/k8s/localTesting.sh
```

Run this to open the migration-console terminal so that you can run 
`workflow commands`

```bash
kubectl -n ma exec --stdin --tty migration-console-0 -- /bin/bash
```

To forward deployed services' ports from minikube to your localhost.  
E.g. so that http://localhost:2746/ will load the argo web-ui, etc, run

```bash
$(git rev-parse --show-toplevel)/deployment/k8s/forwardAllServicePorts.sh
```

## Prerequisites 

As a developer, you'll need to install
* Java Development Kit (JDK) 11-17
* [kubectl](https://kubernetes.io/docs/tasks/tools/)
* [helm](https://helm.sh/docs/intro/install/)

### Install docker (for minikube and legacy docker builds)
Follow instructions [here](https://docs.docker.com/engine/install/) to set up Docker. Docker can be used to build Docker images as well as run a local Kubernetes cluster. 

Notice that there are two different gradle tasks to build images:
* [:TrafficCapture:dockerSolution:buildDockerImages](../../TrafficCapture/dockerSolution/README.md) builds all the images for the ECS solution and can also bring up a docker compose environment to demonstrate capture and replay.
* [:buildImagesToRegistry](../../buildImages/README.md) builds the same set of images using jib and buildkit.  These to not require docker and can be run from a container.  In a fully-containerized K8s environment, these are necessary to complete builds.  CI pipelines are beginnning to use this project rather than the dockerSolution one.

### Setup a Kubernetes cluster

We test our solution with minikube and Amazon EKS.  See [below](#install-minikube) for more how to install minikube and [here](aws/README.md) for how to deploy an EKS cluster.

## Setup a Local Kubernetes Cluster
Creating a local Kubernetes cluster is useful for testing and developing a given deployment. There are a few different tools for running a Kubernetes cluster locally. This documentation focuses on using [Minikube](https://github.com/kubernetes/minikube) to run the local Kubernetes cluster.

### Install Minikube

Follow instructions [here](https://minikube.sigs.k8s.io/docs/start/?arch=%2Fmacos%2Fx86-64%2Fstable%2Fbinary+download) to install Minikube

The default number of CPUs and Memory settings for Minikube can sometimes be relatively low compared to your machine's resources. To increase these resources, make sure your Docker environment has enough allocated resources respectively, and execute commands similar to below:
```shell
minikube config set cpus 8
minikube config set memory 12000
```

#### Start/Pause/Delete
A convenience script `minikubeLocal.sh` is located in this directory which wraps the Minikube commands to start/pause/delete Minikube. This is useful for automatically handling items such as mounting the local repo and creating a tunnel to make localhost calls to containers
```shell
./miniKubeLocal.sh --start
./miniKubeLocal.sh --pause
./miniKubeLocal.sh --delete
```

## Deploying

### What Helm Manages (and what it doesn't manage)

The Migration Assistant is a solution that utilizes a number of different tools
at different points in a migration - taking snapshots, migrating metadata,
documents, and orchestrating live capture replays - all of which are done by 
various containers that are orchestrated together with the help of Argo 
Workflows.  Migrations are performed by running argo workflows via the migration
console.  Argo workflows manages deploying the resources for each of the 
phases of a migration.  Helm manages bootstrapping the Argo Workflows 
environment into the K8s cluster and configuring the other resources that are
used by those workflows (configmaps, RBAC policies, and the migration console).

Helm installations are unaware of the source and target environments 
(unlike previous IAC in the MA ECS solution).  All of those are workflow 
configurations that are used dynamically every time that a workflow is executed.
Configuration options for Helm include features like metrics & log management, 
test/diagnostic features (localstack, jaeger, etc.), and low-level
configurations for Argo Workflows and other critical resources.

Helm allows users to upgrade their charts - which means updating deployed 
resources - by supplying new values to override the old ones.  Helm provides 
a number of tools (optional flags) to understand how values affect the final
resources.  However, this solution attempts to minimize what needs to be
configured a priori, making volatile configurations to be managed dynamically 
by argo rather than by Helm.

Lastly, to minimize the user-involvement in Helm even more, the 
migrationAssistantWithArgo chart itself has no direct dependencies, which can
be burdensome to update and manage.  Instead, the top-level ("umbrella") chart
installs dependent chart itself spins up a job to separately install each of
the configured helm charts, followed by configuring its own resources 
(workflow templates, configmaps, stateful sets, etc).

### Migration Assistant environment

The [Migration Assistant](charts/aggregates/migrationAssistantWithArgo) helm 
chart consists of:
* The Migration Console stateful set (a shell for users to run workflow commands to perform migration tasks)
* Argo Workflows (used by the workflow commands to dynamically provision and manage resources that perform a migration)
* Strimzi (to create Kafka clusters)
* Observability services - Prometheus, Jaeger, and Grafana

Run this to install this chart to a new K8s namespace named 'ma'

```bash
helm install --create-namespace -n ma ma charts/aggregates/migrationAssistantWithArgo
```

To see what has been installed, run
```bash
kubectl get all -n ma
```

There's also a utility chart to install source and target test clusters that can be deployed with
```shell
helm install tc -n ma charts/aggregates/testClusters
```

Notice that all resources are deployed within the same namespace as that makes
the authorization models easier to manage.

### Configuration

Helm charts are configured by substituting values into yaml templates to produce
K8s manifests (such as pods, configmaps, etc.).  Charts include default values
in the chart's values.yaml file.  The migrationAssistantWithArgo chart provides 
an alternate set of values 
([valuesEks.yaml](../k8s/charts/aggregates/migrationAssistantWithArgo/valuesEks.yaml)) 
that can be specified with files that can be specified with the -f flag to 
change how resources will be rendered.  Check the helm [documentation](https://helm.sh/docs/intro/using_helm#customizing-the-chart-before-installing) for more 
details about configuring charts. 

## Uninstalling
To show all helm deployments
```shell
helm list
```

To uninstall a particular helm deployment
```shell
helm uninstall <deployment_name>
```

## Manual AWS Add-ons Setup

The [CloudFormation](#deploying-an-eks-cluster) generated and deployed will
configure all the interfaces that the Migration Assistant needs.  Here are
some examples of how to configure K8s drivers/providers manually.

#### Setting up EBS driver to dynamically provision PVs
```shell
# To check if any IAM OIDC provider is configured:
aws iam list-open-id-connect-providers
# If none exist, create one:
eksctl utils associate-iam-oidc-provider --cluster <cluster_name> --approve
# Create IAM role for service account in order to use EBS CSI driver in EKS
# This currently creates a CFN stack and may 
eksctl create iamserviceaccount \
    --name ebs-csi-controller-sa \
    --namespace kube-system \
    --cluster <cluster_name> \
    --role-name AmazonEKS_EBS_CSI_DriverRole \
    --role-only \
    --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy \
    --approve
# Install add-on to EKS cluster using the created IAM role for the service account
eksctl create addon --cluster <cluster_name> --name aws-ebs-csi-driver --version latest --service-account-role-arn <role_arn> --force
# Create StorageClass to dynamically provision persistent volumes (PV)
kubectl apply -f aws/storage-class-ebs.yml
```

#### Setting up EFS driver to dynamically provision PVs
```shell
export cluster_name=<cluster_name>
export role_name=AmazonEKS_EFS_CSI_DriverRole
eksctl create iamserviceaccount \
    --name efs-csi-controller-sa \
    --namespace kube-system \
    --cluster $cluster_name \
    --role-name $role_name \
    --role-only \
    --attach-policy-arn arn:aws:iam::aws:policy/service-role/AmazonEFSCSIDriverPolicy \
    --approve
TRUST_POLICY=$(aws iam get-role --role-name $role_name --query 'Role.AssumeRolePolicyDocument' | \
    sed -e 's/efs-csi-controller-sa/efs-csi-*/' -e 's/StringEquals/StringLike/')
aws iam update-assume-role-policy --role-name $role_name --policy-document "$TRUST_POLICY"
eksctl create addon --cluster $cluster_name --name aws-efs-csi-driver --version latest --service-account-role-arn <role_arn> --force
kubectl apply -f aws/storage-class-efs.yml
```
