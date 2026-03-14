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
Prefer current repo docs and generated workflow/schema artifacts over wiki copies when they disagree.

Start with:
```bash
cat README.md
cat migrationConsole/README.md
cat docs/MigrationAsAWorkflow.md
cat orchestrationSpecs/README.md
```

For workflow config shape, read the checked-in schema sources or generate the sample/schema from `orchestrationSpecs`.

Use the wiki only as supplemental background or when you need historical operator walkthroughs:
```bash
cd opensearch-migrations.wiki && git pull
cat opensearch-migrations.wiki/Home.md
cat opensearch-migrations.wiki/Architecture.md
cat opensearch-migrations.wiki/Troubleshooting.md
```
