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

The deploy entry point is `deployment/k8s/aws/cli/bin/migration-assistant`,
a modular bash CLI under [cli/](cli/) with ~170 bats tests + shellcheck CI.
It replaces the previous monolithic `aws-bootstrap.sh` (released versions
of that file remain valid for older releases; the source is gone from
this branch and forward).

The CLI handles:

1. **CloudFormation deployment** — creates the EKS cluster, ECR registry,
   IAM roles, and networking (new VPC or imported VPC).
2. **EKS configuration** — sets up kubectl access, namespaces, and node pools.
3. **Helm chart installation** — installs the Migration Assistant with all
   sub-charts (Argo Workflows, Kafka, Prometheus, etc.). Live-streams the
   pre-install hook Job's logs so you see per-sub-chart progress instead
   of a 15-minute silent wait.
4. **CloudWatch dashboards** — deploys monitoring dashboards.

By default, all artifacts come from the latest published release.

What's new compared to the old single-file `aws-bootstrap.sh`:

- **Resumable**: state lives at `~/.opensearch-migrate/<stage>/state.env`.
  Re-running picks up where the last run left off (post-CFN, post-helm, …).
- **Live diagnostics**: CFN events, helm output, installer-Job pod logs,
  and a per-cycle pod-status snapshot are streamed to stderr AND tee'd
  into `~/.opensearch-migrate/<stage>/log/migrate.log`.
- **Stuck-release recovery**: detects a previous helm release stuck in
  `pending-install`/`pending-upgrade`/`failed` and offers rollback or
  uninstall. Cleans up orphan `<release>-helm-installer` Jobs that
  block re-installs.
- **Non-interactive mode**: `--non-interactive` for Jenkins/CI.

### Prerequisites

- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [helm](https://helm.sh/docs/intro/install/) (auto-installed if missing)
- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  with valid credentials
- `jq`, `curl`

### Deploy latest with a new VPC

```bash
./deployment/k8s/aws/cli/bin/migration-assistant \
  --deploy-create-vpc-cfn \
  --stack-name MA \
  --stage prod \
  --region us-east-2
```

For end-users without a repo checkout, the same flags work via the
release-published curl-pipe shim:

```bash
curl -fsSL https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/aws-bootstrap.sh \
  | bash -s -- --deploy-create-vpc-cfn --stack-name MA --stage prod --region us-east-2
```

(`assemble-bootstrap.sh` produces both the shim and the CLI tarball at
release time. The shim downloads the tarball into `~/.opensearch-migrate/cli/<version>/`
and execs the unpacked binary.)

### Deploy into an existing VPC

Run without `--vpc-id` first to discover available VPCs and subnets:

```bash
./deployment/k8s/aws/cli/bin/migration-assistant \
  --deploy-import-vpc-cfn \
  --stack-name MA-Production \
  --stage prod \
  --region us-east-2
```

Then re-run with the IDs:

```bash
./deployment/k8s/aws/cli/bin/migration-assistant \
  --deploy-import-vpc-cfn \
  --stack-name MA-Production \
  --stage prod \
  --vpc-id vpc-0abc123 \
  --subnet-ids subnet-111,subnet-222 \
  --region us-east-2
```

### Grant EKS access to a CI role or teammate

```bash
./deployment/k8s/aws/cli/bin/migration-assistant \
  --skip-cfn-deploy \
  --eks-access-principal-arn arn:aws:iam::123456789012:role/MyRole \
  --stage dev \
  --region us-east-2 \
  --skip-console-exec
```

Run `./deployment/k8s/aws/cli/bin/migration-assistant help` for the full list of options.

### Jenkins / CI invocation

Non-interactive runs accept the same flags + `--non-interactive`:

```bash
./deployment/k8s/aws/cli/bin/migration-assistant \
  --non-interactive \
  --skip-console-exec \
  --skip-setting-k8s-context \
  --use-public-images \
  --stage ma \
  --region us-east-1
```

See the [project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki)
for additional deployment guidance, and [`cli/README.md`](cli/README.md) for
the CLI's architecture.


## Isolated / Air-Gapped Networks

Since the bootstrap script mirrors all images to private ECR by default, isolated
subnets work out of the box. You just need VPC endpoints for EKS to pull from ECR.
Add `--create-vpc-endpoints` (unless you're managing those endpoints elsewhere)
and the script handles the rest.

```bash
./deployment/k8s/aws/cli/bin/migration-assistant \
  --deploy-import-vpc-cfn \
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
./deployment/k8s/aws/cli/bin/migration-assistant \
  --deploy-create-vpc-cfn \
  --build \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-2
```

## Customizing the workloads node pool

The Migration Assistant chart creates a `general-work-pool` NodePool. By
default, both `amd64` and `arm64` architectures are enabled (see
`valuesEks.yaml`). The bootstrap script queries this pool to determine which
architectures to build when using `--build`.

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