# Elasticsearch Migration Reference

## Migration Mechanisms

### Reindex-From-Snapshot (RFS) — Backfill
- Reads raw Lucene segment files from snapshots stored in S3
- Zero impact on the source cluster during backfill
- Workers scale horizontally on Kubernetes (Fargate or EKS)
- Throughput: ~15 MB/s per 2-vCPU worker (primary shard data rate)

### Capture and Replay — Live Traffic
- Capture Proxy deployed as an isolated fleet in front of the source
- Source cluster is never modified
- Traffic captured to Kafka, replayed against target by Replayer service
- Enables zero-downtime migration: source continues serving reads and writes

### Metadata Migration
- Migrates index templates, component templates, settings, and aliases
- Runs as a step in the workflow before backfill
- Does NOT migrate: data streams, ISM policies, security config, Kibana objects,
  ingest pipelines

## Version-Specific Considerations

### ES 1.x–2.x
- Backfill only (no Capture & Replay)
- Very old mapping formats; expect significant type mapping sanitization
- Multiple types per index must be flattened

### ES 5.x–6.x
- Full migration path supported
- Multiple types per index (ES 5.x) or single type (ES 6.x) — both handled
  by Type Mapping Sanitization Transformer
- Check for deprecated field types

### ES 7.x
- Standard migration path
- Single type per index (default `_doc`)
- Most mappings transfer cleanly

### ES 8.x
- Supported to OS 2.x and 3.x
- Check for ES 8.x-specific features (ESQL, new field types)
- Security model differs significantly (X-Pack vs OpenSearch Security)

## Pre-Migration Checklist

1. Verify source version and target version compatibility
2. Check for deprecated or incompatible field types
3. Identify plugins that need OpenSearch equivalents
4. Plan for security migration (users, roles, permissions)
5. Plan for Kibana → OpenSearch Dashboards migration
6. Estimate data size and migration duration
7. Determine downtime tolerance → choose backfill-only or backfill + C&R
