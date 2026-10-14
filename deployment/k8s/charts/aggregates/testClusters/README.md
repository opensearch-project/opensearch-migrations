# ES 7.10/OS 2.11 Developer Clusters

This scrappy chart is only for developer tests - even then, developers may be
annoyed by the lack of durability and redundancy, especially if one needs to
test within and environment where pods may be reassigned aggressively.

## Values files

`values.yaml` holds the defaults: an Elasticsearch 7.10 source on the SearchGuard test image, an
OpenSearch 2.11 target, and snapshots to localstack. Each other file is an overlay passed after it
with `-f`; Helm merges them left to right, so a later file wins.

| Overlay | Layer after | What it changes |
|---|---|---|
| `valuesOss.yaml` | `values.yaml` | kind/local: `elasticsearch-oss` source behind an nginx TLS and basic-auth sidecar |
| `valuesEks.yaml` | `values.yaml` | EKS: the same source, with S3 snapshots through Pod Identity, plus EKS placement |
| `valuesEksNoAuth.yaml` | `valuesEks.yaml` | Plain HTTP and no auth on the source, and no security plugin on the target |
| `valuesNoAuth.yaml` | `values.yaml` | No auth on the SearchGuard source and the target (local only) |
| `valuesSolrSource.yaml` | `values.yaml` | A Solr source in place of Elasticsearch |
| `valuesSolrSourceEks.yaml` | `valuesSolrSource.yaml` | The Solr source on EKS, with S3 snapshots through Pod Identity |
| `values5xMedium.yaml` | any | A 5-node target (8 CPU, 32 GiB, 500 GiB each) on a dedicated Karpenter pool |

The kind flow (`localTestingCommon.sh`) installs `values.yaml` + `valuesOss.yaml`; the EKS quick start
uses `values.yaml` + `valuesEks.yaml`. The sections below cover each overlay in detail.

## Solr Source Cluster

This chart can optionally deploy a SolrCloud source cluster using the
[Apache Solr Operator](https://solr.apache.org/operator/) instead of (or
alongside) the default Elasticsearch source.

### Quick Start (localstack)

```bash
# Deploy Solr 9.7.0 as the source (disables Elasticsearch source)
helm install test-clusters . -f valuesSolrSource.yaml

# Deploy a specific Solr version
helm install test-clusters . -f valuesSolrSource.yaml --set solrSource.image.tag=8.11.4

# Deploy Solr alongside Elasticsearch (both sources)
helm install test-clusters . -f valuesSolrSource.yaml --set conditionalPackageInstalls.source=true
```

The `valuesSolrSource.yaml` overlay:
- Enables the Solr Operator and ZooKeeper Operator
- Creates a single-node SolrCloud with ephemeral storage
- Configures an S3 backup repository pointing at localstack (`http://localstack:4566`)
- Creates a `solr-s3-credentials` Secret with test credentials for localstack

The Solr version is controlled by `solrSource.image.tag` and can be any
published Docker Hub `solr` image tag.

### EKS with S3 via Pod Identity

The Solr Operator's S3 backup repository uses the standard AWS SDK credential
chain, so [Pod Identity](https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html)
credentials are picked up automatically — no keystore sidecar hacks needed
(unlike the Elasticsearch source).

```bash
helm install test-clusters . \
  -f valuesSolrSource.yaml \
  -f valuesSolrSourceEks.yaml \
  --set solrSource.serviceAccount.name=<your-sa-with-s3-access>
```

The `valuesSolrSourceEks.yaml` overlay layers on top of `valuesSolrSource.yaml`:
- Sets `serviceAccount.name` so Solr pods inherit the IAM role
- Removes explicit S3 credentials (SDK auto-discovers from Pod Identity)
- Adds tolerations for the dedicated search node pool

## Open-source Elasticsearch source

`valuesOss.yaml` (local, kind) and `valuesEks.yaml` (EKS) run an Elasticsearch source built only
from published open-source parts: `elasticsearch-oss:7.10.2` (Apache-2.0), the `repository-s3`
plugin installed at startup, and an nginx sidecar for HTTPS (cert-manager, `migrations-ca`) and
basic auth (`admin:admin`). Elasticsearch listens only on loopback; nginx is the only way in.
Clients use `https://elasticsearch-master:9200` with `-u admin:admin`, as with the SearchGuard
image this replaces on Kubernetes (the Docker Compose solution still uses it).

```bash
# Local / kind: snapshots go to the Migration Assistant's localstack
helm upgrade --install tc . -n ma -f values.yaml -f valuesOss.yaml

# EKS: snapshots go to S3 with the pod's Pod Identity credentials
helm upgrade --install tc . -n ma -f values.yaml -f valuesEks.yaml \
  --set conditionalPackageInstalls.target=false

# Check either one end to end: TLS, auth, S3 snapshot and restore
./verifyOssSource.sh --bucket <bucket> --region <region>     # add --skip-s3-listing for localstack
```

On EKS, a sidecar copies the pod's Pod Identity credentials into the Elasticsearch keystore every
minute. The plugin's bundled AWS SDK (1.11.749) can't use Pod Identity itself; the header of
`valuesEks.yaml` explains why the Pod Identity variables don't need to be unset.

## No-Auth Mode

The open-source source can drop TLS and auth so that clients use plain
`curl http://elasticsearch-master:9200/`, with no `-u` and no `-k`:

```bash
# Local / kind
helm upgrade --install tc . -n ma -f values.yaml -f valuesOss.yaml \
  --set nginxFrontend.basicAuth.enabled=false --set nginxFrontend.tls.enabled=false \
  --set source.podAnnotations.testclusters/nginx-frontend=plain

# EKS (layer AFTER valuesEks.yaml; also disables the OS target's security plugin)
helm upgrade --install tc . -n ma -f values.yaml -f valuesEks.yaml -f valuesEksNoAuth.yaml
```

`valuesNoAuth.yaml` is the equivalent for the SearchGuard image (the `values.yaml` default),
disabling SearchGuard on the ES source and the security plugin on the OS target. Configure the
workflow session's `sourceClusters` / `targetClusters` with the matching `http://...` endpoints
and omit `authConfig` entirely.

## S3 Snapshot Support

Each source variant can take S3 snapshots without manual steps:

| Values | Image | S3 | Credentials |
|---|---|---|---|
| `values.yaml` (default) | SearchGuard test image | localstack (`http://localstack:4566`) | Fixed test keys baked into the image's keystore |
| `valuesOss.yaml` (kind) | `elasticsearch-oss` | localstack | Fixed test keys written to the keystore at startup |
| `valuesEks.yaml` (EKS) | `elasticsearch-oss` | the deployment's bucket | A sidecar copies the pod's Pod Identity credentials into the keystore every minute and reloads them |

The S3 plugin bundled with Elasticsearch 7.10 can't use Pod Identity itself, which is why the EKS
overlay uses the keystore sidecar. In an isolated VPC, set the source's `S3_CLIENT_ENDPOINT`
environment variable (`source.extraEnvs`) to the regional endpoint, for example
`s3.us-east-2.amazonaws.com`, so requests route through the VPC's S3 gateway endpoint.

This chart is for testing only: the source is a single node with no durable storage.
