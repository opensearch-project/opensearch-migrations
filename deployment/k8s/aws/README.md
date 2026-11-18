# Migration Assistant on AWS EKS

Deploy the Migration Assistant to a new or existing EKS cluster, then run
your migration from a console pod inside the cluster.

`aws-bootstrap.sh` is the single entry point for the CloudFormation-based AWS
deployment. For a high-level overview of all Kubernetes deployment options,
see the [K8s README](../README.md). For broader Migration Assistant
documentation, see the
[project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki).

To provision equivalent EKS infrastructure without CloudFormation, use the
additive [AWS Terraform module](../../terraform/aws/README.md). The Terraform
module leaves the `aws-bootstrap.sh` workflow intact and can optionally install
the same Helm chart directly.

## What you get

The bootstrap script can create or reuse the Migration Assistant
CloudFormation stack, configure EKS access, mirror the chart's images to a
private ECR registry, install the Helm chart, wait for the deployment, and open
the `migration-console-0` pod.

## Prerequisites

- [AWS CLI](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  with credentials authorized for CloudFormation, EKS, ECR, and IAM
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- `curl`, `jq`, and `tar`
- Helm and `crane`; the bootstrap script installs them when needed
- About 15 minutes for a new CloudFormation stack to come up

## Quick start

From a repository checkout, deploy the latest release into a new VPC:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-2
```

To deploy without a repository checkout, download the released bootstrap
script and invoke it with the same options:

```bash
curl -fsSL \
  https://github.com/opensearch-project/opensearch-migrations/releases/latest/download/aws-bootstrap.sh \
  -o aws-bootstrap.sh
chmod +x aws-bootstrap.sh
./aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-2
```

By default, the script resolves the latest release and mirrors its images to
the deployment's private ECR registry. Use `--version <tag>` to select a
specific release, or `--use-public-images` when the cluster can pull directly
from the public registries.

On subsequent runs, mirroring skips image tags whose source and destination
digests already match, skips Helm chart versions already present in ECR, and
avoids recreating existing repositories. When the script creates or updates the
CloudFormation stack, mirroring starts as soon as its ECR repository exists and
continues while EKS is provisioning and the cluster is configured.

The repository wait is limited to 15 minutes by default so credentials,
permissions, and failed stack operations do not leave the installer polling
forever. Set `MIRROR_ECR_WAIT_TIMEOUT` to a positive number of seconds to
adjust the limit.

Migration Assistant images and third-party images share a work-conserving
queue; Helm chart mirroring reserves at most one additional slot from the same
global limit. The default limit is six concurrent transfers. Set
`MIRROR_CONCURRENCY` to a positive integer to tune the total, including the
chart slot.

The overlap does not use local checkpoints. If the terminal disconnects,
CloudFormation continues in AWS and a rerun attaches to an in-progress stack
operation. Image tags and chart versions that finished before the disconnect
are detected in ECR and skipped; only interrupted transfers are retried.

For an end-to-end walkthrough that builds from source, creates a self-managed
Elasticsearch source and an Amazon OpenSearch Service domain or Serverless
collection target, runs a backfill plus capture and replay, and observes it, see
[Quick start: self-managed Elasticsearch to Amazon OpenSearch](quickstarts/selfManagedEsToAmazonOpenSearch/README.md).

## Deploy into an existing VPC

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-import-vpc-cfn \
  --stack-name MA-Production \
  --stage prod \
  --vpc-id vpc-0abc123 \
  --subnet-ids subnet-111,subnet-222 \
  --region us-east-2
```

Add `--create-vpc-endpoints` when the selected private subnets need the
bootstrap script to create the AWS service endpoints required by the
deployment.

## Use an existing Migration Assistant stack

When the CloudFormation stack was deployed separately, including by Terraform
or external CDK, skip the CloudFormation step:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --skip-cfn-deploy \
  --stage prod \
  --region us-east-2
```

The script resolves the cluster and other resources from the Migration
Assistant CloudFormation exports for the selected stage.

## Build and deploy the current checkout

The repository's `aws-bootstrap.sh` workflow builds the CloudFormation
templates, container images, Helm chart, and CloudWatch dashboards from the
current checkout, pushes the images to the deployment's private ECR registry,
and installs those locally built artifacts on EKS.

In addition to the general prerequisites above, this workflow requires:

- Java 21
- Docker with the Buildx plugin
- Network access from the EKS build nodes to the public base-image registries

To create a development EKS deployment in a new VPC, run this from the
repository root:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --build \
  --base-dir "$(git rev-parse --show-toplevel)" \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-2
```

To rebuild and reinstall the current checkout on an existing Migration
Assistant CloudFormation/EKS deployment, first remove the installed release.
The script only installs unless `--helm-upgrade` is selected. `helm uninstall`
runs the chart's pre-delete hook, which stops and deletes migration workflows
and resources and empties the deployment's S3 bucket, so finish or save
anything you need first.

```bash
helm uninstall ma -n ma
```

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --skip-cfn-deploy \
  --build \
  --base-dir "$(git rev-parse --show-toplevel)" \
  --stage dev \
  --region us-east-2
```

Use the same `--stage` and `--region` values as the existing deployment so the
script resolves the correct CloudFormation exports. `--build` and `--version`
are mutually exclusive.

## Configuring workload NodePools

The EKS deployment's main workload pool, `general-work-pool`, can be tuned by
passing a Helm values override file through `--helm-values`:

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --skip-cfn-deploy \
  --stage dev \
  --region us-east-2 \
  --helm-values ./deployment/k8s/aws/examples/nodepool-overrides.yaml
```

By default, instance family and size selection remain with EKS Auto Mode.
Dedicated RFS, Kafka, proxy and replayer pools require On-Demand capacity
because interruptions affect backfill progress, capture availability and
replay-rate stability. The shared pool and dedicated k6 pool leave capacity
type unconstrained, so Auto Mode may select Spot for them. The sample file at
[`examples/nodepool-overrides.yaml`](./examples/nodepool-overrides.yaml)
requires On-Demand for every pool and narrows selection to c/m/r instances of
generation 5 and newer, up to `4xlarge`. It pins the RFS pool to one exact
benchmark SKU. It demonstrates these fields:

- `workloadsNodePool.mode`
- `workloadsNodePool.instanceTypes`
- `workloadsNodePool.architectures`
- `workloadsNodePool.capacityTypes`
- `workloadsNodePool.instanceCategories`
- `workloadsNodePool.minInstanceGeneration`
- `workloadsNodePool.instanceSizes`
- `workloadsNodePool.limits.cpu`
- `workloadsNodePool.limits.memory`
- `workloadsNodePool.disruption.consolidationPolicy`
- `workloadsNodePool.disruption.consolidateAfter`

`workloadsNodePool.mode` selects the data-plane placement model:

- `shared` keeps RFS workers, Kafka, capture proxies, and replayers together on
  `general-work-pool`.
- `isolated` gives RFS workers, Kafka, capture proxies, and replayers separate
  tainted NodePools.

`isolated` is the default for EKS deployments. Set the mode to `shared` to
consolidate RFS workers, Kafka, capture proxies, and replayers onto
`general-work-pool`.

The optional k6 load generator uses a separate tainted pool in both modes.
Generated pods carry a required node selector as well as the matching
toleration, so a toleration cannot accidentally allow them to fall back onto a
different pool.

Isolation does not require `NodePool.spec.limits`. Limits are optional total
capacity ceilings for cost or quota control. Per-pool limits and instance
requirements can be set below:

- `workloadsNodePool.dedicatedPools.rfs`
- `workloadsNodePool.dedicatedPools.kafka`
- `workloadsNodePool.dedicatedPools.proxy`
- `workloadsNodePool.dedicatedPools.replayer`
- `workloadsNodePool.dedicatedPools.k6`

Dedicated pools inherit the shared instance types, architecture, capacity
type, instance category, minimum generation, instance size, and disruption
settings unless they override them. RFS, Kafka, proxy and replayer override
the default capacity type with `["on-demand"]`; any pool can explicitly select
`["spot"]`, `["on-demand"]`, or another EKS-supported capacity type through
its own `capacityTypes` value. Their `limits` are independent and empty by
default.

Set `instanceTypes` to select exact EC2 SKUs, for example
`instanceTypes: ["c6id.24xlarge"]`. A non-empty `instanceTypes` list replaces
the architecture, category, generation and size filters for that pool. This
lets a dedicated pool inherit general selection defaults without those
defaults excluding its exact SKU.

In `shared` mode, RFS, Kafka, proxies, and replayers necessarily use the
shared pool's single capacity policy. Set `workloadsNodePool.capacityTypes:
["on-demand"]` when using shared mode if backfill, proxy, or replay
interruption is not acceptable.

Values files follow normal Helm merge behavior: map fields merge by key, scalar
conflicts are resolved by the later source, and list fields replace the full
default list. For example, overriding
`workloadsNodePool.disruption.consolidateAfter` alone keeps the default
`consolidationPolicy`, while overriding `instanceSizes` requires supplying the
full desired size list.

The pools have no `limits` by default, so their size is bounded only by the
account's EC2 vCPU quotas. Set `workloadsNodePool.limits.cpu` and/or
`workloadsNodePool.limits.memory` to cap the shared pool, or set `limits` under
an individual dedicated pool to cap that pool.

Disruption defaults to `consolidationPolicy: WhenEmpty`, so running migration
pods are never moved to repack nodes, with `consolidateAfter: 0s`, so empty
nodes are removed right away. EKS requires `consolidateAfter` whenever
`disruption` is set. Setting `workloadsNodePool.disruption: null` leaves the
block out and uses Auto Mode's defaults (`WhenEmptyOrUnderutilized`, `0s`),
which do move running pods.

To check an override file without deploying, run
[`examples/check-nodepool-overrides.sh`](./examples/check-nodepool-overrides.sh)
with it. The script runs `helm lint` and prints the NodePools that would be
rendered. It only needs `helm`. The bootstrap itself checks that each
`--helm-values` file exists before doing anything else. Once the cluster
exists, and before it mirrors or builds images, it also renders the workload
NodePools from those files and validates them against the cluster's NodePool
CRD with a server-side dry run, which catches values EKS would reject. The dry
run is skipped with `--use-general-node-pool`, which does not create the pool.

## Tagging everything the deployment creates

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-create-vpc-cfn \
  --stack-name MA-Dev \
  --stage dev \
  --region us-east-1 \
  --tags CostCenter=1234,Owner=platform-team
```

`--tags` covers two separate mechanisms:

- **CloudFormation stack tags.** CloudFormation copies them onto every
  taggable resource it creates.
- **EKS Auto Mode resources.** Nodes, EBS volumes, ENIs, and load balancers are
  created after the stack, so stack tags never reach them. `aws-bootstrap.sh`
  creates a custom `NodeClass` carrying the tags, disables the built-in
  `system` and `general-purpose` NodePools that use the untaggable default
  `NodeClass`, sets `tagSpecification_N` parameters on the `auto-ebs-sc`
  `StorageClass`, and adds load-balancer tag annotations to Services and
  Ingresses.

This requires the cluster IAM role to permit user-defined tags on Auto Mode
resources. The solution CloudFormation template declares that policy, and
`aws-bootstrap.sh --tags` also ensures it for older, adopted, and hand-built
clusters. The caller needs `iam:PutRolePolicy` on the cluster role.

Two caveats: `--tags` is incompatible with `--use-general-node-pool`, and
StorageClass parameters are immutable, so changing the tag set on an existing
release requires deleting `auto-ebs-sc` first. The script checks both cases and
reports the required correction.

## Diagnostics

If installation fails, `aws-bootstrap.sh` prints Helm status, pods, jobs,
events, logs, and pod descriptions. For an existing deployment, collect the
same first-line diagnostics with:

```bash
helm status ma -n ma
kubectl get pods -n ma -o wide
kubectl get events -n ma --sort-by=.lastTimestamp
```

## Cleanup

Remove the Helm release before deleting the CloudFormation stack:

```bash
helm uninstall ma -n ma
aws cloudformation delete-stack \
  --stack-name MA-Dev \
  --region us-east-2
```

The Helm pre-delete hook stops workflows, deletes dashboards, and empties the
deployment's S3 bucket.

## See also

- Run `./deployment/k8s/aws/aws-bootstrap.sh --help` for the complete option
  list.
- [Project wiki](https://github.com/opensearch-project/opensearch-migrations/wiki)
  for full Migration Assistant documentation.
- [`../README.md`](../README.md) for the overall Kubernetes deployment project.
