# Private Networking for GCP Migrations

By default the GCP migration deployment reaches the source cluster, target cluster, and
Cloud Storage over routable (typically public) endpoints. This guide shows how to make
each data path private so that no migration data traverses the public internet.

All connectivity is **optional** and configured per leg. With no configuration, behavior
is unchanged from the standard GCP deployment.

> **Scope:** these mechanisms are GCP-internal. A source or target hosted in AWS or
> on-premises cannot be reached via Private Service Connect or VPC peering — that
> requires HA VPN or Cloud Interconnect, which is not yet supported here.

## The data legs

| Leg | How to make it private |
|-----|------------------------|
| Target write | `target_connectivity` = `psc_consumer` or `vpc_peering` |
| Source read | `source_connectivity` = `psc_consumer` or `vpc_peering` |
| Snapshot ↔ Cloud Storage | `gcs_connectivity = { mode = "private_google_access" }` (default) |
| Control plane (operator access) | `enable_private_endpoint = true` + narrow `master_authorized_cidrs` |

## Target/Source over Private Service Connect (`psc_consumer`)

Use this when the cluster is published as a GCP PSC service attachment (for example a
managed OpenSearch service that offers a private endpoint).

1. Obtain the producer's **service-attachment URI** and ask them to add your project to
   the service attachment's consumer accept-list (or confirm it auto-accepts).
2. Configure the leg:

   ```hcl
   target_connectivity = {
     mode               = "psc_consumer"
     service_attachment = "projects/PRODUCER/regions/REGION/serviceAttachments/NAME"
   }
   ```

3. Apply, then read the endpoint Terraform provisioned:

   ```bash
   terraform output target_private_endpoint   # e.g. 10.0.0.42
   ```

4. **Use the endpoint in your migration.** Endpoints are not injected automatically —
   put the value into your workflow user config's `targetClusters` (or `sourceClusters`)
   entry as the cluster `endpoint`, e.g. `https://10.0.0.42:9200`. The migration workflow
   reads cluster endpoints from this config at submission time.

## Target/Source over VPC peering (`vpc_peering`)

Use this when the cluster lives in another GCP VPC you can peer with.

1. Ensure the peer VPC's subnet ranges do **not** overlap with this deployment's
   `subnet_cidr`, `pods_cidr`, or `services_cidr`.
2. Configure the leg:

   ```hcl
   source_connectivity = {
     mode               = "vpc_peering"
     peer_project       = "their-project"
     peer_vpc_self_link = "projects/their-project/global/networks/their-vpc"
   }
   ```

3. This creates **our** side of the peering only. Peering is non-transitive, so the peer
   must create the reciprocal peering back to our VPC. Get our VPC self-link from:

   ```bash
   terraform output source_peering_state   # ACTIVE once both sides exist
   ```

   The peer also needs firewall rules permitting our subnet/pod ranges to reach the
   cluster port, and DNS that resolves the cluster hostname over the peered route.
4. The cluster keeps its own endpoint URL — put that URL into your workflow user config
   as usual; traffic now routes privately over the peering.

## Snapshot storage (Cloud Storage)

`gcs_connectivity` defaults to `{ mode = "private_google_access" }`, which sets
`private_ip_google_access` on the migration subnet so nodes reach Cloud Storage over
Google's private network. No action needed for the default (created-VPC) path. Set
`{ mode = "none" }` to opt out.

> **VPC Service Controls:** if your organization enforces a VPC-SC perimeter that denies
> the standard Google API VIPs, route Google API traffic via `restricted.googleapis.com`
> through your perimeter/DNS configuration (owned by your platform team). A dedicated
> `psc_google_apis` mode is planned for a future release.

**If you supply an existing subnet (`create_vpc = false`),** Terraform does not manage
that subnet and cannot set the flag — enable Private Google Access on it yourself:

```bash
gcloud compute networks subnets update YOUR_SUBNET --region REGION --enable-private-ip-google-access
```

## Control-plane privacy (operator access)

Set `enable_private_endpoint = true` to remove the public IP from the GKE control plane.
This affects only how **you** (operators/CI) reach the cluster with `kubectl`/Helm — the
in-cluster migration workloads are unaffected. When enabled:

- Narrow `master_authorized_cidrs` from the default `0.0.0.0/0` to your internal ranges.
- Reach the cluster via one of: IAP TCP tunnel (`gcloud container clusters get-credentials`
  then IAP), a bastion host in the VPC, or a VPN. This repo does not provision a bastion.

## Firewall

For a private deployment, also narrow `allowed_ingress_cidrs` (default `0.0.0.0/0`) to the
ranges that must reach the migration nodes.
