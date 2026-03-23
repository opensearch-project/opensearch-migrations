# Document Migration Data Flow

Documents flow from a source through the pipeline core to a target. The IR types (`Document`, `CollectionMetadata`) are the boundary — source adapters produce them, sink adapters consume them.

## Data Flow

```
Source Side                    Pipeline Core                     Sink Side
──────────                     ─────────────                     ─────────

Snapshot                       CollectionMetadata ──────────►    createCollection()
  │                                                                  │
  ▼                                                                  ▼
SnapshotExtractor                                                OpenSearchIndexCreator
  │                                                                  │
  ▼                                                                  ▼
LuceneSnapshotSource           Document ────────────────────►    OpenSearchDocumentSink
  │                                                                  │
  ▼                                                                  ▼
LuceneAdapter                                                    IJsonTransformer (optional)
  │                                                                  │
  ▼                            ProgressCursor ◄──────────────    Bulk API → OpenSearch
Flux<Document>  ──────►  DocumentMigrationPipeline  ──────►  Flux<ProgressCursor>
                         (batching + concurrency)
```

## How It Works

1. `LuceneSnapshotSource` wraps `SnapshotExtractor` to read Lucene segments from a snapshot
2. `LuceneAdapter` converts `LuceneDocumentChange` → `Document`
3. `DocumentMigrationPipeline` batches documents by count and byte size, then calls `writeBatch()` on the sink
4. `OpenSearchDocumentSink` optionally applies `IJsonTransformer`, then sends a bulk request to OpenSearch
5. Each batch returns a `ProgressCursor` for resumability tracking
6. `PipelineProgressMonitor` logs progress on a fixed 30s timer, independent of pipeline throughput

### Key Decoupling

The sink never sees the source's partitioning. `writeBatch(collectionName, batch)` takes a collection name and documents — no `Partition` or shard information leaks to the target side. This enables non-shard-based sources (S3, Solr) to use the same sink.

## Delta Snapshot Support

When configured with a previous snapshot, `LuceneSnapshotSource` uses `DeltaLuceneReader` to diff two snapshots and emit only the changes (deletions first, then additions) as `Document` records with `UPSERT` or `DELETE` operations.
