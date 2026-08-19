# Kubernetes Deployment

Audience: This document is meant for **DEVELOPERS** looking to build/maintain a Kubernetes deployment of the Migration
Assistant tools from this (opensearch-migrations) repository to support customers that want to Migrate their
configurations and data from a source cluster to a target cluster. End-users should consult
the [project's wiki](https://github.com/opensearch-project/opensearch-migrations/wiki) for detailed instructions to
deploy and operate a production environment.

This README focuses on the helm installation of the [Migration Assistant](charts/aggregates/migrationAssistantWithArgo)
chart, which will install the migration console and resources required for it to perform migrations (e.g. Argo
Workflows, metrics collectors, etc).

**Notice**: The user is responsible for the cost of any underlying infrastructure required to operate the solution. We
welcome feedback and contributions to optimize costs.

## Scripts

| Script                            | Purpose                                                                                         |
|-----------------------------------|-------------------------------------------------------------------------------------------------|
| buildSolr6Image.sh                | Builds a custom solr image and deploys it to the local image registry                           |
| deployCdcLoadTestConfig.sh        | Brings up a capture-and-replay (CDC) load-test pipeline by submitting a migration config        |
| fillLocalRegistry.sh              | Sets up local registry, builds and pushes images to that local registry                         |
| forwardAllServicePorts.sh         | Port-forwards all services to localhost to make them accessible from the host machine           |
| generateWorkflowSchemaArtifact.sh | Generates the workflow schema artifact                                                          |
| installK6Chart.sh                 | Installs the standalone k6 load-test chart (operator + scenarios + RBAC)                        |
| kindCleanup.sh                    | Cleanups the loal kind deployment                                                               |
| kindTesting.sh                    | (Re)deploys a local kind cluster and installs the migration assistant with a source and target  |
| localTestingCommon.sh             | Contains shared code for kindTesting.sh and kindCleanup.sh                                      |
| package-transoforms.sh            | Builds and pushes an OCI image containing user transform files                                  |
| redeployMigrationConsole.sh       | Removes possible image cache in kind and calls `helm upgrade` to redeploy the migration console |
| update_deps.sh                    | Updates dependencies of helm charts                                                             |
| updateArgoWorkflowTemplate.sh     | Updates `clusterWorkflows.yaml` in case changes were made to it and need update in running argo |

See the [kind instructions](#install-kind) below for more details about specific scripts.

`deployCdcLoadTestConfig.sh` is the one to reach for when you want a running capture proxy and
replayer locally, with k6 ready to drive traffic through them.

What it submits is a migration **config** (`configs/cdcLoadTest.yaml`, or your own with `-f`), not a
workflow: it feeds the config to `workflow configure edit --stdin` + `workflow submit` in the
migration console, and the console's config processor generates the Argo workflow from it. The
script then waits on the resulting CRs and installs the k6 chart. It is a driver over the supported
path rather than an alternate deployment mechanism — the same two console commands you would run by
hand.

Because the topology lives entirely in the config, any CDC shape is expressible without changing the
script: more proxies, more replayers, an S3 traffic source, an external Kafka. Resource names are
read back from the cluster rather than predicted, since the config processor composes them
(`<proxy>-topic`, `<proxy>-<target>-<replayer>`).

Run `kindTesting.sh` first — it expects the control plane and clusters to exist.

```bash
./deployCdcLoadTestConfig.sh up --dry-run   # show the config that would be submitted
./deployCdcLoadTestConfig.sh up             # submit + wait for the CRs to report Ready + install k6
./deployCdcLoadTestConfig.sh up --no-auth   # ... against clusters deployed with valuesNoAuth.yaml
./deployCdcLoadTestConfig.sh status         # CR phases, workflow phase, proxy endpoint
./deployCdcLoadTestConfig.sh down           # delete the migration resources + the k6 chart
```

## Quick Start

### EKS

If you're looking to use EKS as your Kubernetes cluster, follow the
[instructions here](aws/README.md).

### GKE

If you're looking to use Google Kubernetes Engine (GKE) as your Kubernetes cluster,
the [GCP Terraform module](../terraform/gcp/README.md) provisions a GKE cluster, a GCS bucket for snapshots, and the
Google Service Account / Workload Identity bindings required by the migration workflows.

After `terraform apply` succeeds and you've fetched cluster credentials with
`gcloud container clusters get-credentials`, install the Helm chart using the GKE overlay
[valuesGke.yaml](charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml):

```bash
helm install --create-namespace -n ma ma \
  charts/aggregates/migrationAssistantWithArgo \
  --values charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml \
  --set gcp.project=<your-gcp-project>
```

GCS snapshot repositories are configured per-workflow at workflow-submission time (via the `gcs` snapshot type in the
workflow config) rather than at Helm-install time, in the same way S3 repositories are configured for EKS deployments.

## Prerequisites

As a developer, you'll need to install

* Java Development Kit (JDK) 11-17
* [kubectl](https://kubernetes.io/docs/tasks/tools/)
* [helm](https://helm.sh/docs/intro/install/)

### Install docker (for kind)

Follow instructions [here](https://docs.docker.com/engine/install/) to set up Docker.

Docker images are built via the [:buildImages:buildImagesToRegistry](../../buildImages/README.md) project using Jib and
BuildKit (which requires docker's builders).

### Setup a Kubernetes cluster

We test our solution with kind and Amazon EKS. See [below](#install-kind) for more how to install kind
and [here](aws/README.md) for how to deploy an EKS cluster.

## Setup a Local Kubernetes Cluster

Creating a local Kubernetes cluster is useful for testing and developing a given deployment. There are a few different
tools for running a Kubernetes cluster locally. This documentation focuses on using [kind](https://kind.sigs.k8s.io/) to
run the local Kubernetes cluster.

### Install kind

Install `kind` from the upstream release or your package manager of choice. The `kindTesting.sh` script assumes the
cluster is created from
[kindClusterConfig.yaml](kindClusterConfig.yaml), which configures kind to pull project images from the local registry
mirror at `localhost:5001`.

Both local flows use the same docker-hosted backend:

* `kindTesting.sh` source `buildImages/backends/dockerHostedBuildkit.sh`. It shares a single `docker-registry`
  container on the `local-migrations-network` Docker network. Host-side `docker buildx` pushes to `localhost:5001`; pods
  inside the cluster pull by the in-cluster DNS name `docker-registry:5000`. The cluster's nodes are joined to
  `local-migrations-network` so the name resolves via Docker's bridge DNS. Plain HTTP is allowed on the in-cluster
  endpoint in kind via a containerd `hosts.toml`.
* EKS / GKE / AKS use `buildImages/backends/eksKubernetesBuildkit.sh`, which spins up amd64 + arm64 buildkit Pods
  directly via `docker buildx --driver=kubernetes` on the cluster's `build-nodepool`.

Those backend implementations live under [buildImages/backends](../../buildImages/backends), which keeps image-build
orchestration with the build tooling.

Because kind clusters share one `docker-registry` container, so image layers and the buildkit cache are reused across
them.

If you want the kind cluster to run on OrbStack instead of Docker Desktop, switch the active Docker context before
running the script so `docker` and
`kind` target the same backend.

## Deploying

### What Helm Manages (and what it doesn't manage)

The Migration Assistant is a solution that utilizes a number of different tools at different points in a migration -
taking snapshots, migrating metadata, documents, and orchestrating live capture replays - all of which are done by
various containers that are orchestrated together with the help of Argo Workflows. Migrations are performed by running
argo workflows via the migration console. Argo workflows manages deploying the resources for each of the phases of a
migration. Helm manages bootstrapping the Argo Workflows environment into the K8s cluster and configuring the other
resources that are used by those workflows (configmaps, RBAC policies, and the migration console).

Helm installations are unaware of the source and target environments (unlike previous IAC in the MA ECS solution). All
of those are workflow configurations that are used dynamically every time that a workflow is executed. Configuration
options for Helm include features like metrics & log management, test/diagnostic features (localstack, jaeger, etc.),
and low-level configurations for Argo Workflows and other critical resources.

Helm allows users to upgrade their charts - which means updating deployed resources - by supplying new values to
override the old ones. Helm provides a number of tools (optional flags) to understand how values affect the final
resources. However, this solution attempts to minimize what needs to be configured a priori, making volatile
configurations to be managed dynamically by argo rather than by Helm.

Lastly, to minimize the user-involvement in Helm even more, the migrationAssistantWithArgo chart itself has no direct
dependencies, which can be burdensome to update and manage. Instead, the top-level ("umbrella") chart installs dependent
chart itself spins up a job to separately install each of the configured helm charts, followed by configuring its own
resources (workflow templates, configmaps, stateful sets, etc).

### Migration Assistant environment

The [Migration Assistant](charts/aggregates/migrationAssistantWithArgo) helm chart consists of:

* The Migration Console stateful set (a shell for users to run workflow commands to perform migration tasks)
* Argo Workflows (used by the workflow commands to dynamically provision and manage resources that perform a migration)
* Strimzi (to create Kafka clusters)
* Observability services - Prometheus, Jaeger, and Grafana

During startup, the migration console pod runs a `workflow-schema-generator`
init container after the Strimzi operator is available. That init container reads the live Strimzi OpenAPI schema from
the cluster, builds the unified migration workflow schema, and writes the resulting
`workflowMigration.schema.json` and `sample.yaml` into a shared in-pod volume. The main migration console container then
uses those generated files for workflow-config validation.

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

Notice that all resources are deployed within the same namespace as that makes the authorization models easier to
manage.

### Configuration

Helm charts are configured by substituting values into yaml templates to produce K8s manifests (such as pods,
configmaps, etc.). Charts include default values in the chart's values.yaml file. The migrationAssistantWithArgo chart
provides an alternate set of values
([valuesEks.yaml](../k8s/charts/aggregates/migrationAssistantWithArgo/valuesEks.yaml))
that can be specified with files that can be specified with the -f flag to change how resources will be rendered. Check
the helm [documentation](https://helm.sh/docs/intro/using_helm#customizing-the-chart-before-installing) for more details
about configuring charts.

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

The [CloudFormation](#eks) generated and deployed will configure all the interfaces that the Migration Assistant needs.
Here are some examples of how to configure K8s drivers/providers manually.

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
