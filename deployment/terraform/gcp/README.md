# GCP Terraform — Migration Assistant Infrastructure

Provisions GCP infrastructure for the OpenSearch Migration Assistant.

## Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/install) >= 1.6 (native `terraform test` is used for validation)
- [gcloud CLI](https://cloud.google.com/sdk/docs/install) authenticated with `gcloud auth application-default login`
- GCP project with billing enabled
- Required APIs enabled:
  ```
  gcloud services enable container.googleapis.com storage.googleapis.com
  ```

## Usage

```bash
# Plan
terraform plan -var="project=my-project"

# Apply
terraform apply -var="project=my-project" \
  -var="region=us-central1" \
  -var="cluster_type=standard"

# Get kubeconfig (cluster name is dynamically generated)
gcloud container clusters get-credentials $(terraform output -raw cluster_name) \
  --region $(terraform output -raw cluster_location) --project my-project

# Deploy migration assistant Helm chart
helm install migration-assistant \
  ../../k8s/charts/aggregates/migrationAssistantWithArgo \
  --values ../../k8s/charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml \
  --set gcp.project=my-project

# Destroy
terraform destroy -var="project=my-project"
```

## Variables

| Name | Default | Description |
|------|---------|-------------|
| `project` | (required) | GCP project ID |
| `region` | `us-central1` | GCP region |
| `cluster_type` | `standard` | `standard` or `autopilot` |
| `cluster_name` | `migration-cluster` | GKE cluster name |
| `node_machine_type` | `e2-standard-4` | Node machine type (standard only) |
| `node_count` | `2` | Initial nodes per zone (standard only) |
| `bucket_name` | `migration-snapshots-<project>` | Snapshot storage bucket |
| `create_vpc` | `true` | Create new VPC, or use existing (`false`) |
| `existing_vpc_name` | `null` | Existing VPC name (when `create_vpc = false`) |
| `existing_subnet_name` | `null` | Existing subnet name (when `create_vpc = false`) |
| `subnet_cidr` | `10.0.0.0/16` | VPC subnet IP range |
| `pods_cidr` | `10.1.0.0/16` | GKE pods secondary IP range |
| `services_cidr` | `10.2.0.0/20` | GKE services secondary IP range |
| `master_ipv4_cidr_block` | `10.3.0.0/28` | GKE control plane IP range (private clusters) |
| `master_authorized_cidrs` | `["0.0.0.0/0"]` | CIDRs authorized to reach the control plane |
| `max_zones` | `2` | Maximum number of zones for regional GKE node placement |
| `node_locations` | `[]` | Explicit GKE node zones; defaults to the first `max_zones` available zones in the region |
| `node_disk_size` | `50` | Node boot disk size (GB) |
| `node_disk_type` | `pd-standard` | Node boot disk type |
| `node_oauth_scopes` | `["cloud-platform"]` | OAuth scopes for node SA |
| `node_min_count` | `1` | Minimum nodes per zone |
| `node_max_count` | `5` | Maximum nodes per zone |
| `release_channel` | `REGULAR` | GKE release channel |
| `allowed_ingress_ports` | `["443","9200","9300"]` | Allowed ingress TCP ports |
| `allowed_ingress_cidrs` | `["0.0.0.0/0"]` | Allowed ingress source CIDRs |
| `node_iam_roles` | `["roles/storage.admin"]` | IAM roles for the node SA |
| `workload_identity_namespace` | `migration` | Kubernetes namespace containing Migration Assistant service accounts |
| `additional_workload_identity_service_accounts` | `["migration-console-access-role","argo-workflow-executor","argo-workflow-controller","argo-controller"]` | Additional Kubernetes service accounts that can use the GCP migration service account |
| `source_connectivity` | `{mode = "none"}` | Private connectivity for source cluster read traffic; `mode = "none"` (default, public internet), `"psc_consumer"` (Private Service Connect), or `"vpc_peering"` |
| `target_connectivity` | `{mode = "none"}` | Private connectivity for target cluster write traffic; same modes as `source_connectivity` |
| `gcs_connectivity` | `{mode = "private_google_access"}` | Private Google Access for Cloud Storage snapshot traffic; `mode = "private_google_access"` (default, private path) or `"none"` (public internet) |
| `enable_private_endpoint` | `false` | Restrict GKE control plane to private IP only (no public endpoint); requires VPN or bastion for kubectl access |

## Private networking

To run a migration with no public-internet data path (private source/target connectivity,
private Cloud Storage access, and a private control plane), see
[Private Networking for GCP Migrations](../../../docs/gcpPrivateNetworking.md).

## Notes

- Standard clusters use private nodes with Cloud NAT for outbound access.
- Workload Identity is enabled and bound to `migration/migrations-service-account`.
- The node SA gets `roles/storage.admin` for GCS snapshot access.

## Zone Placement

The migration infrastructure can safely run in a single zone (`max_zones = 1`).
The migration is a temporary operation, and the RFS coordinator's lease mechanism
makes any failure recoverable — workers pick up where they left off. A zone
outage means you restart the workflow, not lose data.

Single-zone placement provides better throughput: no cross-zone latency between
workers, coordinator, and Kafka, and no inter-zone network egress charges.

Multi-zone placement is only worth considering if you need the migration to be
continuously available (e.g., a capture-and-replay running for days during a
cutover window where downtime is unacceptable). Even then, the source and target
clusters are the critical HA components — not the migration infra.

## Sizing: `maxShardSizeBytes` and node ephemeral storage

The RFS (Reindex-From-Snapshot) workers request ephemeral storage proportional
to `maxShardSizeBytes` — the upper bound on the size of a single shard the
worker may need to materialize from a snapshot.

The default is **80 GiB**, which is sized for production-scale shards. RFS uses
the formula:

```
ephemeral-storage request = ceil(2.5 * maxShardSizeBytes)
```

So the default produces a **200 GiB** ephemeral-storage request per RFS worker.

On the default `e2-standard-4` node pool with a **50 GB boot disk**, allocatable
ephemeral storage is roughly **17.5 GiB** per node, so an RFS pod with the
default request **will not schedule**. You will see the pod stuck in `Pending`
with `Insufficient ephemeral-storage`.

You have two options for small / dev / e2e clusters:

1. **(Recommended)** Lower `maxShardSizeBytes` in the workflow config to match
   the largest shard you actually expect. For example, for the bundled ES 7.10
   test snapshot, `5368709120` (5 GiB) is safe and produces a ~12.5 GiB
   ephemeral-storage request that fits on the default node.
2. Increase `node_disk_size` (and optionally `node_machine_type`) in the
   terraform variables to provide enough ephemeral storage for the default.

For production workloads, **keep the 80 GiB default** and size the nodes
appropriately — undersizing `maxShardSizeBytes` will cause the migration to
fail mid-shard if a real shard exceeds the configured ceiling.

## Bring Your Own Snapshot (BYOS)

The default Argo workflows create a fresh snapshot in the configured GCS
bucket as the first step of the migration. For testing, demos, or migrating
from an already-snapshotted source, you can instead point the workflow at a
pre-existing snapshot in GCS — Bring Your Own Snapshot.

### Staging a snapshot in GCS

If you have a snapshot directory on local disk (for example, the bundled
`RFS/test-resources/snapshots/ES_7_10_Single/`), copy it to your snapshot
bucket under a path of your choice:

```bash
gsutil -m cp -r \
  RFS/test-resources/snapshots/ES_7_10_Single \
  gs://migration-snapshots-<project>/e2e-test/
```

The path you upload to becomes the `gcsRepoPathUri` you reference from the
workflow. The Migration Assistant's GCP service account must have
`roles/storage.objectAdmin` on the bucket (the default terraform applies
`roles/storage.admin`, which includes this).

### Submitting a BYOS workflow

Use the
[`fullMigrationImportedClusters.yaml`](../../../migrationConsole/lib/integ_test/testWorkflows/fullMigrationImportedClusters.yaml)
sample workflow template, passing `snapshot-configmap: migrations-default-gcs-config`
as a workflow parameter to use the GCS bucket instead of the default S3 one.
To switch it from "create-and-migrate" to BYOS, set:

- `source.snapshotRepo.repoPathUri` to the GCS URI you staged
  (`gs://migration-snapshots-<project>/e2e-test`).
- `snapshotConfig.snapshotNameConfig.externallyManagedSnapshotName` to the
  name of the snapshot inside that repo (for the bundled test snapshot, this
  is `rfs-snapshot`).

When `externallyManagedSnapshotName` is set for Elasticsearch/OpenSearch, the
workflow **skips snapshot creation** and goes directly to metadata +
reindex-from-snapshot using the provided repo URI. Solr external backups use
the Solr `snapshotInfo.backups` shape and run a lightweight prepare/validation
step before metadata + reindex-from-snapshot.

### Caveats

- The staged snapshot's shards count toward the `maxShardSizeBytes` sizing
  decision above. The bundled ES 7.10 test snapshot's largest shard is well
  under 5 GiB, so a lowered `maxShardSizeBytes` of `5368709120` is sufficient.
- The bucket region should match the GKE region for best performance;
  cross-region reads work but are slower and incur egress.
- When the workflow **creates** the snapshot (not BYOS), the **source** cluster must
  support GCS snapshot repositories. Elasticsearch 8.0+ and OpenSearch bundle this;
  Elasticsearch 7.x requires the `repository-gcs` plugin on every source node, or
  snapshot registration fails with `repository type [gcs] does not exist`. See
  [Private Networking for GCP Migrations](../../../docs/gcpPrivateNetworking.md#snapshot-storage-cloud-storage).
