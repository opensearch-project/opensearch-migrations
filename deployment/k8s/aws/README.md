# Deploying the Migration Assistant on AWS EKS

This guide is for **developers** that are interested in building everything and
deploying those artifacts directly to EKS themselves.

For a more details about Kubernetes, see the [README](../README.md)
for the overall K8s deployment project.

See the [project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki)
for instructions of how to deploy directly from AWS without installing anything.


## Difference from the ECS version

The EKS solution requires less AWS resources and fewer upfront configuration
options than the previous release build upon ECS.  You'll need some permissions,
but the CloudFormation should deploy in < 15 minutes.  Once that succeeds, you
should have all the permissions that you need to deploy and run the
Migration Assistant on Kubernetes.


## Overview

The `aws-bootstrap.sh` script is the single entry point for deploying the
Migration Assistant onto AWS EKS.  The same script can be used by end-users
and developers to bootstrap their Migration Assistant to EKS. It handles:

1. **CloudFormation deployment** — creates the EKS cluster, ECR registry,
   IAM roles, and networking (new VPC or imported VPC).
2. **EKS configuration** — sets up kubectl access, namespaces, and node pools.
3. **Helm chart installation** — installs the Migration Assistant with all
   sub-charts (Argo Workflows, Kafka, Prometheus, etc.).
4. **CloudWatch dashboards** — deploys monitoring dashboards.

By default, all artifacts come from the latest published release.
The script:
- Downloads CloudFormation templates from the Solutions S3 bucket
- Downloads Helm chart and dashboards from the GitHub release
- Uses container images from `public.ecr.aws/opensearchproject`


### Prerequisites

- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [helm](https://helm.sh/docs/intro/install/) (auto-installed if missing)
- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  with valid credentials
- `jq`, `curl`

### Deploy latest with a new VPC

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --stack-name MA \
  --stage prod \
  --region us-east-2
```

### Deploy into an existing VPC

Run without `--vpc-id` first to discover available VPCs and subnets:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-import-vpc-cfn \
  --stack-name MA-Production \
  --stage prod \
  --region us-east-2
```

Then re-run with the IDs:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-import-vpc-cfn \
  --stack-name MA-Production \
  --stage prod \
  --vpc-id vpc-0abc123 \
  --subnet-ids subnet-111,subnet-222 \
  --region us-east-2
```

### Grant EKS access to a CI role or teammate

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --skip-cfn-deploy \
  --eks-access-principal-arn arn:aws:iam::123456789012:role/MyRole \
  --stage dev \
  --region us-east-2 \
  --skip-console-exec
```

Run `./deployment/k8s/aws/aws-bootstrap.sh --help` for the full list of options.

See the [project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki)
for additional deployment guidance.


## Isolated / Air-Gapped Networks

For subnets without internet access, the bootstrap script can mirror all required
container images and helm charts to your private ECR registry, and create the VPC
endpoints needed for EKS to pull from ECR. Add `--push-all-images-to-private-ecr`
and `--create-vpc-endpoints` (unless you're managing those endpoints elsewhere) 
and the script handles the rest.

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-import-vpc-cfn \
  --push-all-images-to-private-ecr \
  --create-vpc-endpoints \
  --stack-name MA-Prod \
  --stage prod \
  --vpc-id vpc-xxx \
  --subnet-ids subnet-aaa,subnet-bbb \
  --region us-east-1 \
  --version 2.6.4
```

The mirroring step runs from your machine (which has internet), copies ~50 images
and 11 helm charts to ECR, then the EKS cluster pulls everything through VPC
endpoints. Seven endpoints are created: ECR API, ECR Docker, S3, CloudWatch Logs,
EFS, STS, and EKS Auth.


## Developer Workflow

### Prerequisites

In addition to the general prerequisites above, if building any of the
artifacts directly, you'll also need to install:
- Java Development Kit (JDK) 11-17
- Docker: for image builds, including builder support for buildkit remote builds
- The opensearch-migrations repo

### Build everything from source

```bash
# From the repo root
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --build-cfn \
  --build-images \
  --build-chart-and-dashboards \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-2
```

### Build only some components

When mixing built and downloaded artifacts, `--version` is required to prevent
version mismatches:

```bash
# Build CFN from source, use released images and chart
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --build-cfn \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-2 \
  --version 2.6.4
```

## Customizing the workloads node pool

The Migration Assistant chart creates a `general-work-pool` NodePool. By
default, both `amd64` and `arm64` architectures are enabled (see
`valuesEks.yaml`). The bootstrap script queries this pool to determine which
architectures to build when using `--build-images`.

To customize, pass `--helm-values` with a file containing:

```yaml
workloadsNodePool:
  architectures: ["arm64"]
  limits:
    cpu: "128"
    memory: "256Gi"
```

Or to use only amd64:

```yaml
workloadsNodePool:
  architectures: ["amd64"]
```

For more details on the K8s deployment, see the [k8s README](../README.md).
To update images, see [buildImages](../../../buildImages/README.md).


## In-K8s Source/Target Test Clusters

Integration tests spin up Elasticsearch/OpenSearch clusters through Argo
workflows. For development, you can deploy source and target clusters
that remain indefinitely.  Notice that these clusters are NOT configured to
use persistent data storage though to make them as lightweight as possible.

```bash
cd $(git rev-parse --show-toplevel)

eval $(aws cloudformation list-exports \
  --query "Exports[?starts_with(Name, \`MigrationsExportString\`)].Value" \
  --output text)

export ELASTIC_SEARCH_IMAGE_TAG=migrations_elasticsearch_searchguard_latest

helm upgrade --install -n ma tc \
  deployment/k8s/charts/aggregates/testClusters \
  -f deployment/k8s/charts/aggregates/testClusters/valuesEks.yaml \
  --set "source.image=$MIGRATIONS_ECR_REGISTRY" \
  --set "source.imageTag=$ELASTIC_SEARCH_IMAGE_TAG" \
  --set "source.extraInitContainers[0].image=$MIGRATIONS_ECR_REGISTRY:$ELASTIC_SEARCH_IMAGE_TAG" \
  --set "source.extraContainers[0].image=$MIGRATIONS_ECR_REGISTRY:$ELASTIC_SEARCH_IMAGE_TAG" \
  --post-renderer deployment/k8s/charts/aggregates/testClusters/patchElasticsearchEntrypointToIgnorePodIdentity.py
```

The `--post-renderer` patches the Elasticsearch StatefulSet entrypoint to work
correctly with EKS Pod Identity.