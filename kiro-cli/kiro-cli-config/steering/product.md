# OpenSearch Migration Assistant

A Kubernetes-native tool for migrating data from Elasticsearch/OpenSearch clusters to OpenSearch.

## Capabilities
- Metadata migration (templates, settings, aliases)
- Document backfill via snapshot-based reindexing (RFS)
- Source: Elasticsearch 1.x-8.x, OpenSearch 1.x-2.x
- Target: OpenSearch 1.x-3.x

## Not Supported
Data streams, ISM policies, security config, Kibana objects, ingest pipelines

## Documentation
The wiki is cloned locally. To get the latest docs:
```bash
cd opensearch-migrations.wiki && git pull
```

Read specific docs with:
```bash
cat opensearch-migrations.wiki/Home.md
cat opensearch-migrations.wiki/Architecture.md
cat opensearch-migrations.wiki/Migration-Paths.md
cat opensearch-migrations.wiki/Workflow-CLI-Overview.md
cat opensearch-migrations.wiki/Workflow-CLI-Getting-Started.md
cat opensearch-migrations.wiki/Backfill-Workflow.md
cat opensearch-migrations.wiki/Deploying-to-EKS.md
cat opensearch-migrations.wiki/Deploying-to-Kubernetes.md
cat opensearch-migrations.wiki/Troubleshooting.md
```
