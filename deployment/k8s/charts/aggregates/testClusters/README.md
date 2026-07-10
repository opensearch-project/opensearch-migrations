# ES 7.10/OS 2.11 Developer Clusters

This scrappy chart is only for developer tests - even then, developers may be
annoyed by the lack of durability and redundancy, especially if one needs to
test within and environment where pods may be reassigned aggressively.

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

## No-Auth Mode

Both `values.yaml` and `valuesEks.yaml` leave HTTPS + basic-auth turned on
(SearchGuard on the ES source, the security plugin on the OS target). For
developer workflows that want to exercise the migration paths against an
anonymous, plain-HTTP pair of clusters, layer on the no-auth presets:

```bash
# Local / kind / in-cluster dev
helm install tc . -f values.yaml -f valuesNoAuth.yaml

# EKS (layer AFTER valuesEks.yaml)
helm install tc . -f values.yaml -f valuesEks.yaml -f valuesEksNoAuth.yaml
```

With these overlays active, the clusters are reachable with `curl
http://elasticsearch-master:9200/` and `curl
http://opensearch-cluster-master:9200/` — no `-u`, no `-k`. Configure the
workflow session's `sourceClusters` / `targetClusters` with the matching
`http://...` endpoints and omit `authConfig` entirely.

## S3 Snapshot Support

The default base image for ES 7.10 that this installation uses constructed by
the opensearch-migrations project for testing.  That image has Search Guard
installed and is configured to use insecure HTTPS for its incoming connections.

The chart itself sets the elasticsearch.yml to include a couple 
`s3.client.default` settings to use `http://localstack:4566` as its S3 endpoint.
That makes it possible (and straightforward) to do E2E testing of the Migration
Assistant tools without needing to do anything that isn't supported for 
production.

### Testing against S3 (not localstack)

If you're interested in using this chart for testing in EKS - be prepared,
you'll probably want to consider other ways to set up a cluster.  As mentioned
above, this chart isn't configured well out-of-the-box to deal with pod 
evictions - so the configuration that isn't in the chart and the data are 
highly volatile.

For this deployment, the S3 plugin for Elasticsearch has a simple model for
credentials management.  Normally, an S3 client might use IMDS credentials from
EC2 to provide credentials.  When running a cluster through pods, that isn't 
possible.  The [EKS CloudFormation templates](../../../aws/README.md) that are 
part of this project use (Pod Identity)[https://docs.aws.amazon.com/eks/latest/userguide/pod-identities.html] 
to map service accounts to IAM identities, vending credentials to the containers
that run within pods.  Those identities are available for the migration console,
but the S3 plugin doesn't take advantage of them.

Therefore, we don't have an option to get short-lived credentials into the 
Elasticsearch container automatically.  Setting up a long term credential
is theoretically possible, but not secure, and there are far better solutions
to run a cluster securely than this INSECURE chart!

However, if you want to do a quick-and-dirty test - you can load credentials
onto the Elasticsearch host through the key store and use those to take a 
snapshot.

First, make sure that you have a role that has access to the S3 bucket that 
the snapshot will be written to.  Get the credentials triple (awscli2 provides
`aws configure export-credentials`) and fill those values into the keystore add
commands.

Copy those credentials into the keystore and refresh.  Run the following
(interactive) commands.

```bash
bin/elasticsearch-keystore add s3.client.default.access_key
bin/elasticsearch-keystore add s3.client.default.secret_key
bin/elasticsearch-keystore add s3.client.default.session_token

curl -X POST "http://localhost:9200/_nodes/reload_secure_settings?pretty"
```

After that, you should be able to run the snapshot migration step once the 
workflow has been configured.
