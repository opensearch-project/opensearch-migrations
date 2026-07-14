# Private Networking for GCP Migrations

By default the GCP migration deployment reaches the source cluster, target cluster, and
Cloud Storage over routable (typically public) endpoints. This guide shows how to make
each data path private so that no migration data traverses the public internet.

All connectivity is **optional** and configured per leg. With no configuration, behavior
is unchanged from the standard GCP deployment.

> **Scope:** these mechanisms are GCP-internal. A source or target hosted in AWS or
> on-premises cannot be reached via Private Service Connect or VPC peering — that
> requires HA VPN or Cloud Interconnect, which is not yet supported here.
>
> **Backfill migrations** (snapshot + reindex-from-snapshot) are covered end to end.
> **Live capture-and-replay** adds a capture proxy in front of the source; making that
> proxy's ingress private on GCP is not yet supported (its Service currently emits
> AWS-only internal-load-balancer annotations). Keep live migration to a private network
> path you control until GKE internal-LB support for the capture proxy lands.

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

1. Obtain the producer's **service-attachment URI** and the **hostname** its TLS
   certificate is issued for, and ask them to add your project to the service
   attachment's consumer accept-list (or confirm it auto-accepts).
2. Configure the leg, supplying `dns_name` so the endpoint is reachable by hostname
   with valid TLS:

   ```hcl
   target_connectivity = {
     mode               = "psc_consumer"
     service_attachment = "projects/PRODUCER/regions/REGION/serviceAttachments/NAME"
     dns_name           = "myservice-myproj.example.com"
   }
   ```

   When `dns_name` is set, Terraform creates a **private Cloud DNS zone** that resolves
   that hostname to the PSC endpoint's internal IP, so clients validate TLS against the
   real certificate. Omit `dns_name` only if you intend to connect by IP with relaxed
   TLS verification.
3. Apply, then read the hostname (preferred) or raw IP:

   ```bash
   terraform output target_private_fqdn       # e.g. myservice-myproj.example.com
   terraform output target_private_endpoint   # e.g. 10.0.0.42 (IP fallback)
   ```

4. **Approve the connection on the producer side (if not auto-accepted).** Applying
   creates a PSC endpoint (a forwarding rule) that dials the producer's service
   attachment. If the attachment uses manual acceptance, the connection stays in a
   **pending** state until the producer approves it — often keyed to the consumer
   endpoint's internal IP (`target_private_endpoint` above). Producers whose accept-list
   already includes your project may auto-accept. Confirm the connection reaches an
   **accepted** state before migrating; you can check the consumer side with:

   ```bash
   gcloud compute forwarding-rules describe NAME --region REGION \
     --format='value(pscConnectionStatus)'   # want: ACCEPTED
   ```

5. **Use the endpoint in your migration.** Endpoints are not injected automatically —
   put the **hostname** into your workflow user config's `targetClusters` (or
   `sourceClusters`) entry as the cluster `endpoint`, e.g.
   `https://myservice-myproj.example.com:9200`. Use the **port the producer serves on**
   (managed services often use a non-9200 port). If you omitted `dns_name`, use the IP
   with relaxed TLS instead. The migration workflow reads cluster endpoints from this
   config at submission time.

## Target/Source over VPC peering (`vpc_peering`)

Use this when the cluster lives in another GCP VPC you can peer with.

1. Ensure the peer VPC's subnet ranges do **not** overlap with this deployment's
   `subnet_cidr`, `pods_cidr`, or `services_cidr`.
2. Configure the leg:

   ```hcl
   source_connectivity = {
     mode               = "vpc_peering"
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

> **Source-cluster prerequisite — the `repository-gcs` plugin (Elasticsearch < 8.0).**
> The backfill snapshots the **source** cluster to Cloud Storage, which requires the
> source to support GCS snapshot repositories. Elasticsearch **8.0+** and OpenSearch
> bundle GCS support; **Elasticsearch 7.x does not** — it needs the `repository-gcs`
> plugin installed on every source node, or snapshot-repository registration fails with
> `repository type [gcs] does not exist`. Install it on the source before migrating,
> e.g.:
>
> ```bash
> bin/elasticsearch-plugin install --batch repository-gcs   # each ES 7.x node, then restart
> ```
>
> This is a property of the source cluster and independent of the private-networking
> configuration here.

## Control-plane privacy (operator access)

Set `enable_private_endpoint = true` to remove the public IP from the GKE control plane.
This affects only how **you** (operators/CI) reach the cluster with `kubectl`/Helm — the
in-cluster migration workloads are unaffected. When enabled:

- Narrow `master_authorized_cidrs` from the default `0.0.0.0/0` to your internal ranges.
- Reach the cluster via one of: IAP TCP tunnel (`gcloud container clusters get-credentials`
  then IAP), a bastion host in the VPC, or a VPN. This repo does not provision a bastion.

> **The `terraform apply` host is also affected.** This module's `kubernetes` and `helm`
> providers connect to the cluster's control-plane endpoint to install the Migration
> Assistant Helm release. With a private endpoint, the machine running `terraform apply`
> must itself reach that endpoint (run it from inside the VPC, or over IAP/VPN) — not just
> your interactive `kubectl`. Otherwise the `helm_release` step will hang or fail.

## Firewall

For a private deployment, also narrow `allowed_ingress_cidrs` (default `0.0.0.0/0`) to the
ranges that must reach the migration nodes.
