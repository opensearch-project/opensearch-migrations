# AWS Terraform: Migration Assistant Infrastructure

This root module provisions the AWS infrastructure used by the Kubernetes-based
OpenSearch Migration Assistant. It is an additive Terraform alternative to the
existing CDK-generated CloudFormation templates; those templates remain in the
repository and continue to be supported.

The default deployment creates:

- A dual-stack VPC spanning two availability zones, with public and private
  subnets and one NAT gateway per zone
- S3, ECR API, ECR Docker, CloudWatch Logs, and EFS VPC endpoints
- An EKS Auto Mode cluster with the built-in `system` and `general-purpose` node
  pools. When the chart is installed, `use_custom_karpenter_node_pool` (default
  `true`) also configures the chart's own EKS Auto Mode NodePool
- A private ECR repository
- Cluster, node, snapshot, and Migration Assistant workload IAM roles
- EKS Pod Identity associations for the chart's AWS-facing service accounts
- Optionally, the Migration Assistant Helm chart using the EKS overlay

You can instead deploy into an existing VPC and select only the endpoints that
Terraform should add, deploy into an isolated (air-gapped) network with no public
data path and a private Kubernetes API endpoint, and optionally establish private
connectivity to the source and target clusters. Those options are covered in the
sections below.

## Prerequisites

- Terraform or OpenTofu 1.6 or newer
- AWS credentials authorized to manage VPC, EKS, ECR, IAM, and VPC endpoint
  resources
- AWS CLI and `kubectl` for cluster access after provisioning
- Network access to public Helm and container registries if `deploy_helm = true`

EKS Auto Mode must be available in the selected AWS region and for the selected
Kubernetes version. `kubernetes_version` defaults to `1.35`; override it if that
version is not offered in your region.

## Create a new VPC and EKS cluster

From this directory:

```bash
terraform init
terraform plan \
  -var="region=us-east-1" \
  -var="stage=dev"
terraform apply \
  -var="region=us-east-1" \
  -var="stage=dev"

# Configure kubectl using the command emitted by Terraform.
$(terraform output -raw kubeconfig_command)
kubectl get nodes
```

The new VPC uses `10.212.0.0/16` by default to reduce the chance of conflicts
with a default VPC. Override `vpc_cidr` if that range overlaps a source, target,
peered, or transit-connected network.

## Use an existing VPC

Supply at least two subnets in distinct availability zones. Private subnets with
NAT or equivalent egress are recommended.

```hcl
create_vpc          = false
existing_vpc_id     = "vpc-0123456789abcdef0"
existing_subnet_ids = [
  "subnet-0123456789abcdef0",
  "subnet-abcdef01234567890",
]

# Endpoints are opt-in for an existing VPC.
vpc_endpoints = [
  "s3",
  "ecr.api",
  "ecr.dkr",
  "logs",
  "sts",
  "eks-auth",
]
```

When `existing_route_table_ids` is empty, Terraform discovers the route table
associated with each supplied subnet for the S3 gateway endpoint. Set the route
table IDs explicitly if the subnets use an implicit main-route-table association
or if only selected route tables should receive the S3 route.

Valid `vpc_endpoints` values are `s3`, `ecr.api`, `ecr.dkr`, `logs`,
`monitoring`, `elasticfilesystem`, `sts`, and `eks-auth`. For a new VPC, `s3`,
`ecr.api`, `ecr.dkr`, `logs`, and `elasticfilesystem` are always created and
`vpc_endpoints` adds to that set; `sts`, `eks-auth`, and `monitoring` are added
only by `isolated = true` or by naming them here. A non-isolated new VPC reaches
those services over its NAT gateway instead. Isolated subnets need
additional private access to every registry and Helm repository used during
installation; these AWS service endpoints alone do not provide that access.

## Isolated (air-gapped) deployment

Set `isolated = true` (with `create_vpc = true`) to provision a fully private
network with no outbound internet path, for a migration that must not traverse
the public internet:

```bash
terraform apply \
  -var="region=us-east-1" \
  -var="stage=dev" \
  -var="isolated=true"
```

In isolated mode the module creates no NAT gateway and adds no default route from
the private subnets, and it creates the full set of service VPC endpoints the
cluster needs to reach AWS APIs privately: `s3` (gateway) plus interface endpoints
for `ecr.api`, `ecr.dkr`, `logs`, `monitoring`, `elasticfilesystem`, `sts`, and
`eks-auth`. This matches the endpoint set used by the isolated-network deployment
path. `isolated` defaults to `false`, which preserves the standard behavior (NAT
gateway plus the base endpoint set).

Isolated mode also makes the EKS Kubernetes API endpoint private, so the control
plane is not reachable from the internet along with the data path. This follows
from `isolated`: `cluster_endpoint_public_access` defaults to unset, which means
"public endpoint off when `isolated = true`, on otherwise". The private endpoint
(`cluster_endpoint_private_access`) stays enabled, so the API is reachable from
inside the VPC.

Plan for that before you apply, because it changes how you reach the cluster:

- `kubectl` (including the `kubeconfig_command` output) must run from somewhere
  inside the VPC or a network routed to it: a bastion or workload in a private
  subnet, a peered VPC, or a VPN/Direct Connect attachment.
- `deploy_helm = true` requires the same access. Terraform's Helm provider talks
  to the cluster API directly, so `terraform apply` itself has to run from a host
  that can reach the private endpoint.

To keep an isolated data path but reach the API from outside the VPC, set
`cluster_endpoint_public_access` explicitly. An explicit value always wins over
the `isolated`-derived default, and it affects only the control plane: NAT
gateways, routes, and the endpoint set are unchanged. Narrow
`cluster_public_access_cidrs` when you do; it defaults to `0.0.0.0/0`.

```bash
terraform apply \
  -var="region=us-east-1" \
  -var="stage=dev" \
  -var="isolated=true" \
  -var="cluster_endpoint_public_access=true" \
  -var='cluster_public_access_cidrs=["203.0.113.10/32"]'
```

The override works in the other direction too: set
`cluster_endpoint_public_access = false` without `isolated` for a private-only API
endpoint in a standard VPC that keeps its NAT egress.

Container images must be reachable without internet egress. Mirror the Migration
Assistant images and any third-party images the chart pulls into the module's
private ECR repository (`ecr_repository_url`) before workloads start.

## Private connectivity to the source and target

`source_connectivity` and `target_connectivity` establish a private network path
to the source and target clusters so backfill and live migration run without a
public data path. Each leg is independent and defaults to `mode = "none"`, which
creates nothing and leaves a public-path deployment unchanged. The other modes:

- `privatelink`: create a consumer interface VPC endpoint to the cluster
  provider's VPC endpoint service, optionally with a Route 53 private hosted zone
  that resolves a hostname to the endpoint. Requires the cluster provider to publish
  a VPC endpoint service name (`com.amazonaws.vpce.<region>.vpce-svc-...`); obtain it
  from the provider.
- `vpc_peering`: peer the migration VPC with the cluster's VPC and route to its
  CIDR. Fits a cluster in a customer-owned VPC (often the source).

```hcl
# Target reached over PrivateLink, with a private DNS name.
target_connectivity = {
  mode                      = "privatelink"
  vpc_endpoint_service_name = "com.amazonaws.vpce.us-east-1.vpce-svc-0123456789abcdef0"
  dns_name                  = "my-target.example.com"   # optional; use the provider's canonical hostname
}

# Source reached by peering to a customer VPC.
source_connectivity = {
  mode        = "vpc_peering"
  peer_vpc_id = "vpc-0aaaaaaaaaaaaaaaa"
  peer_cidr   = "10.99.0.0/16"          # must not overlap vpc_cidr
}
```

The resolved private endpoint is exposed as `source_private_endpoint` /
`target_private_endpoint`; place it in the workflow cluster configuration. The
cluster endpoint and credentials themselves are supplied as runtime migration
config, not by Terraform.

This module intentionally provisions only the consumer side. The following are
operator responsibilities and are not automated:

- PrivateLink: the provider must allow-list this account and accept the endpoint
  connection if acceptance is required. Until then the endpoint stays in
  `pendingAcceptance` and the hostname does not resolve to a working endpoint. Use
  the provider's canonical hostname for `dns_name` so its TLS certificate
  validates. The endpoint service must be offered in the migration availability
  zones.
- VPC peering: the peer must accept the connection (cross-account or cross-region)
  and add the reciprocal route back to `vpc_cidr`. Peer CIDRs must not overlap the
  migration VPC CIDR.

## Install the Helm chart with Terraform

Helm installation is off by default so infrastructure can be provisioned
independently and images can be mirrored before workloads start. To install the
local chart with published public ECR images:

```bash
terraform apply \
  -var="region=us-east-1" \
  -var="stage=dev" \
  -var="deploy_helm=true" \
  -var="migration_assistant_version=3.3.4"
```

Use an actual published Migration Assistant release tag. Terraform applies
`valuesEks.yaml`, passes the AWS account, region, stage, and snapshot role to the
chart, and waits `helm_timeout_seconds` (1500, or 25 minutes) for the release.

The module creates a private ECR repository for parity with the CloudFormation
deployment and exposes it as `ecr_repository_url`. The optional Terraform Helm
path uses public images; image mirroring or source builds remain a separate step.

If your account requires a permissions boundary on newly created IAM roles (some
organizations deny `iam:CreateRole` unless the request includes a specific
boundary), set `permissions_boundary_arn` and the module applies it to every role
it creates. It defaults to unset.

By default the module creates the IAM role that Amazon OpenSearch Service assumes
to read and write S3 snapshots. Set `create_opensearch_service_snapshot_role =
false` when the migration source is not Amazon OpenSearch Service (for example a
self-managed Elasticsearch or OpenSearch cluster), which registers its snapshot
repository without assuming an AWS role, so the role is unused.

## Variables

| Name | Default | Description |
|---|---|---|
| `region` | `us-east-1` | AWS region for the Migration Assistant infrastructure |
| `stage` | `dev` | Short identifier used in resource names; unique per deployment in a region |
| `kubernetes_version` | `1.35` | EKS Kubernetes control-plane version |
| `tags` | `{}` | Additional tags applied to resources this module manages |
| `create_vpc` | `true` | Create a dual-stack VPC and two private/public subnet pairs, or use an existing VPC (`false`) |
| `vpc_cidr` | `10.212.0.0/16` | IPv4 CIDR for a VPC created by this module |
| `availability_zones` | `[]` | Exactly two AZs for a new VPC; the first two available zones are used when empty |
| `existing_vpc_id` | `null` | Existing VPC ID; required when `create_vpc = false` |
| `existing_subnet_ids` | `[]` | At least two existing subnets in distinct AZs; required when `create_vpc = false` |
| `existing_route_table_ids` | `[]` | Route tables for the S3 gateway endpoint in an existing VPC; discovered per subnet when empty |
| `vpc_endpoints` | `[]` | Endpoints to add. Valid: `s3`, `ecr.api`, `ecr.dkr`, `logs`, `monitoring`, `elasticfilesystem`, `sts`, `eks-auth` |
| `isolated` | `false` | Air-gapped new VPC: no NAT gateway, no default route, full private endpoint set, private Kubernetes API |
| `source_connectivity` | `{mode = "none"}` | Private path to the source. `mode = "none"` \| `"privatelink"` \| `"vpc_peering"` |
| `target_connectivity` | `{mode = "none"}` | Private path to the target; same modes as `source_connectivity` |
| `cluster_endpoint_public_access` | `null` | Public EKS API endpoint. Unset follows `isolated`; an explicit value always wins |
| `cluster_endpoint_private_access` | `true` | Private EKS API endpoint inside the VPC |
| `cluster_public_access_cidrs` | `["0.0.0.0/0"]` | CIDRs permitted to reach the public EKS API endpoint; narrow for production |
| `namespace` | `ma` | Kubernetes namespace for Migration Assistant and its Pod Identity associations |
| `pod_identity_service_accounts` | see `variables.tf` | Service accounts that assume the shared workload role through EKS Pod Identity |
| `permissions_boundary_arn` | `null` | IAM permissions boundary applied to every role this module creates |
| `create_opensearch_service_snapshot_role` | `true` | Create the role Amazon OpenSearch Service assumes for S3 snapshots; `false` for a self-managed source |
| `deploy_helm` | `false` | Install the repository's Migration Assistant Helm chart after the cluster is ready |
| `migration_assistant_version` | `null` | Public ECR image tag used when `deploy_helm = true`, for example `3.3.4` |
| `use_custom_karpenter_node_pool` | `true` | Configure the chart's custom EKS Auto Mode NodePool in addition to the built-in pools |
| `helm_timeout_seconds` | `1500` | Maximum wait for the Helm release |

## Outputs

Important outputs include:

| Output | Purpose |
|---|---|
| `cluster_name` | EKS cluster name |
| `kubeconfig_command` | Ready-to-run AWS CLI command for kubectl access |
| `ecr_repository_url` | Private image repository |
| `snapshot_role_arn` | Role passed when registering OpenSearch S3 snapshot repositories |
| `migration_pod_role_arn` | Shared role used by EKS Pod Identity |
| `source_private_endpoint` | Private source endpoint when `source_connectivity` is set, else null |
| `target_private_endpoint` | Private target endpoint when `target_connectivity` is set, else null |
| `migration_environment` | Shell exports equivalent to the CloudFormation bootstrap export string |
| `cluster_security_group_id` | EKS-managed cluster security group, for rules that must allow the cluster |
| `helm_release_status` | Helm release status, or null when `deploy_helm` is false |

To load the compatibility environment into the current shell:

```bash
eval "$(terraform output -raw migration_environment)"
```

## CloudFormation coexistence and migration

This module does not delete, update, import, or otherwise manage resources in an
existing CloudFormation stack. It uses the same familiar cluster and ECR naming
pattern, so do not deploy Terraform with the same `stage` and region as a live
CloudFormation deployment unless you first plan an explicit state migration.
Use a different stage for side-by-side evaluation.

The Terraform implementation intentionally replaces CloudFormation's stack-level
AppRegistry association with normal AWS resource tags because there is no
CloudFormation stack to associate. Runtime infrastructure remains equivalent:
EKS Auto Mode, networking, ECR, IAM, snapshot access, and Pod Identity.

## Validate

The tests use Terraform mock providers and do not require AWS credentials:

```bash
terraform init -backend=false
terraform fmt -check -recursive
terraform validate
terraform test
```

## Destroy

Review the plan carefully; this removes the EKS environment and any Helm release
managed by this state:

```bash
terraform destroy
```

Terraform does not touch the CloudFormation deployment or its resources.
