# OpenSearch Migration Assistant

A Kubernetes-native tool for migrating data from Elasticsearch/OpenSearch clusters to OpenSearch.

## Capabilities
- Metadata migration (templates, settings, aliases)
- Document backfill via snapshot-based reindexing (RFS) — reads from S3 snapshots, zero source cluster impact
- Live traffic capture and replay via isolated proxy fleet + Kafka
- Source: Elasticsearch 1.x-8.x, OpenSearch 1.x-2.x, Apache Solr 8.x
- Target: OpenSearch 1.x-3.x, Amazon OpenSearch Serverless

### Solr Migration Path
Solr migrations use a different architecture than Elasticsearch migrations:
- **SolrReader** — Reads Solr backup data (Lucene segments + schema.xml), translates Solr field types to OpenSearch mappings, and bulk-indexes documents
- **Transformation Shim** — A real-time HTTP proxy that translates Solr API requests (`/select`) to OpenSearch `_search` and back, enabling zero-downtime cutover with shadow validation
- Four operating modes: Solr-only passthrough, OpenSearch-only, dual-target Solr-primary (shadow validation), dual-target OpenSearch-primary (cutover with safety net)

Read the wiki for Solr-specific guidance:
```bash
cat opensearch-migrations.wiki/Solr-Migration-Overview.md          # End-to-end Solr migration phases
cat opensearch-migrations.wiki/Running-the-Transform-Proxy.md      # Transformation Shim setup, query translation, operating modes
```

## Not Supported
Data streams, ISM policies, security config, Kibana objects, ingest pipelines

## Key Concepts
- RFS reads raw Lucene segment files from snapshots in S3 — scaling workers has zero impact on the source cluster
- Capture proxy is deployed as an isolated fleet; the source cluster is never modified
- Configuration schema changes between versions — always use `workflow configure sample` for the installed version's schema

## Documentation
The wiki is cloned locally. To get the latest docs:
```bash
cd opensearch-migrations.wiki && git pull
```

Read specific docs with:
```bash
cat opensearch-migrations.wiki/Home.md                          # Overview, reconnection commands, Migration Console orientation
cat opensearch-migrations.wiki/What-Is-A-Migration.md           # Migration concepts, RFS vs _reindex, Capture and Replay, iterative workflows
cat opensearch-migrations.wiki/Architecture.md                  # Mental model, error recovery, approval gates
cat opensearch-migrations.wiki/Migration-Paths.md               # Version compatibility, source version detection, version string format
cat opensearch-migrations.wiki/Deploying-to-EKS.md              # Bootstrap script, STAGE explanation, verification, snapshot config
cat opensearch-migrations.wiki/Deploying-to-Kubernetes.md       # Generic K8s deployment with Helm
cat opensearch-migrations.wiki/Workflow-CLI-Overview.md          # Concepts, command reference, manage TUI, error recovery
cat opensearch-migrations.wiki/Workflow-CLI-Getting-Started.md   # Version check → sample → edit → secrets → submit → monitor
cat opensearch-migrations.wiki/Backfill-Workflow.md              # Backfill mental model, config categories, verification
cat opensearch-migrations.wiki/Capture-and-Replay-Workflow.md    # Capture proxy fleet, Kafka, replayer, zero-downtime migration
cat opensearch-migrations.wiki/Solr-Migration-Overview.md        # End-to-end Solr migration phases, SolrReader, schema translation
cat opensearch-migrations.wiki/Running-the-Transform-Proxy.md    # Transformation Shim setup, query translation, operating modes
cat opensearch-migrations.wiki/Troubleshooting.md                # Connectivity, auth, workflow failures, pods, config, performance
```
