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
cat opensearch-migrations.wiki/Home.md                      # Overview, version identification, Migration Console orientation
cat opensearch-migrations.wiki/What-Is-A-Migration.md       # Migration concepts, RFS, Capture and Replay, iterative workflows
cat opensearch-migrations.wiki/Architecture.md              # Mental model, error recovery, approval gates
cat opensearch-migrations.wiki/Migration-Paths.md           # Version compatibility, how to determine source version
cat opensearch-migrations.wiki/Deploying-to-EKS.md          # Bootstrap script, STAGE explanation, verification, snapshot config
cat opensearch-migrations.wiki/Deploying-to-Kubernetes.md   # Generic K8s deployment with Helm
cat opensearch-migrations.wiki/Workflow-CLI-Overview.md     # Concepts, command reference, manage TUI, error recovery
cat opensearch-migrations.wiki/Workflow-CLI-Getting-Started.md  # Version check → sample → edit → secrets → submit → monitor
cat opensearch-migrations.wiki/Backfill-Workflow.md         # Mental model, config categories, verification with console clusters curl
cat opensearch-migrations.wiki/Troubleshooting.md           # Connectivity, auth, workflow failures, pods, config, performance
```
