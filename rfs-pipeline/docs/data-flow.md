# Document Migration Data Flow

Documents flow from a source through the pipeline core to a target. The IR types (`Document`, `CollectionMetadata`) are the boundary — source adapters produce them, sink adapters consume them.

## Data Flow

```
Source Side                    Pipeline Core                     Sink Side
──────────                     ─────────────                     ─────────

Snapshot                       CollectionMetadata ──────────►    createCollection()
  │                            (name, partitionCount,                │
  ▼                             sourceConfig)                        ▼
SnapshotExtractor                                                OpenSearchIndexCreator
  │                                                                  │
  ▼                                                                  ▼
LuceneSnapshotSource           Document ────────────────────►    OpenSearchDocumentSink
  │                            (id, source, operation,               │
  ▼                             hints, sourceMetadata)               ▼
LuceneAdapter                                                    IJsonTransformer (optional)
  │                                                                  │
  ▼                            ProgressCursor ◄──────────────    Bulk API → OpenSearch
Flux<Document>  ──────►  DocumentMigrationPipeline  ──────►  Flux<ProgressCursor>
                         (batching + concurrency)
```

### Types at the Edges

| Edge | IR Type | Source Produces | Sink Consumes |
|---|---|---|---|
| Collection setup | `CollectionMetadata` | `readCollectionMetadata()` → name, partitionCount, opaque `sourceConfig` | `createCollection()` — reads `sourceConfig` for ES-specific fields if present |
| Document stream | `Document` | `readDocuments()` → id, source bytes, operation, `hints` (ES routing/type), `sourceMetadata` (luceneDocNumber) | `writeBatch()` — reads `hints` for routing, ignores `sourceMetadata` |
| Progress tracking | `ProgressCursor` | — | Pipeline emits one per batch: partition, cumulative offset, batch stats |

## How It Works

1. `LuceneSnapshotSource` wraps `SnapshotExtractor` to read Lucene segments from a snapshot
2. `LuceneAdapter` converts `LuceneDocumentChange` → `Document`, populating `hints` with `_type`/`routing` and `sourceMetadata` with `luceneDocNumber`
3. `DocumentMigrationPipeline` batches documents by count and byte size, then calls `writeBatch()` on the sink
4. `OpenSearchDocumentSink` optionally applies `IJsonTransformer`, then sends a bulk request to OpenSearch
5. Each batch returns a `ProgressCursor` for resumability tracking
6. `PipelineProgressMonitor` logs progress on a fixed 30s timer, independent of pipeline throughput

### Key Decoupling

The sink never sees the source's partitioning. `writeBatch(collectionName, batch)` takes a collection name and documents — no `Partition` or shard information leaks to the target side. This enables non-shard-based sources (S3, Solr) to use the same sink.

## Delta Snapshot Support

When configured with a previous snapshot, `LuceneSnapshotSource` uses `DeltaLuceneReader` to diff two snapshots and emit only the changes (deletions first, then additions) as `Document` records with `UPSERT` or `DELETE` operations.
