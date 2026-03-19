# IR Types and Port Interfaces

The pipeline defines a source-agnostic intermediate representation (IR). All types are Java `record`s or interfaces with compact constructor validation and zero runtime dependencies beyond Reactor. Port interfaces use `Flux` and `Mono` for reactive streaming.

## IR Types (`ir/` package)

All types in this package are source-agnostic — no ES, Lucene, or OpenSearch concepts.

**`Document`** — A single document flowing through the pipeline.
- `id` (String) — document identifier, required
- `source` (byte[]) — document body, null for DELETE operations
- `operation` (Operation) — `UPSERT` or `DELETE`
- `hints` (Map<String, String>) — opaque sink-specific routing hints. ES sources populate `HINT_TYPE` (`_type`) and `HINT_ROUTING` (`routing`). Non-ES sources pass an empty map. The pipeline core never reads these.
- `sourceMetadata` (Map<String, Object>) — opaque source-specific diagnostics. Lucene sources populate `SOURCE_META_LUCENE_DOC_NUMBER`. Flows through for logging/debugging.

**`Partition`** — Interface for any source partitioning scheme.
- `name()` — human-readable identifier for logging
- `collectionName()` — the collection this partition belongs to
- Implementations: `EsShardPartition` (in `adapter/`), future: `S3PrefixPartition`, `SolrShardPartition`

**`CollectionMetadata`** — Metadata for creating a target collection.
- `name` (String) — collection name, required
- `partitionCount` (int) — hint for target, 0 means "use target default"
- `sourceConfig` (Map<String, Object>) — opaque source-specific config. ES sources populate `ES_MAPPINGS`, `ES_SETTINGS`, `ES_ALIASES`, `ES_NUMBER_OF_SHARDS`, `ES_NUMBER_OF_REPLICAS`. Non-ES sources pass an empty map.

**`ProgressCursor`** — Resumability tracking per partition.
- `partition` (Partition) — which partition this progress is for
- `lastDocProcessed` (long) — cumulative document offset
- `docsInBatch` (long) — documents in the most recent batch
- `bytesInBatch` (long) — bytes in the most recent batch

**`BatchResult`** — Batch-local stats returned by the sink.
- `docsInBatch` (long)
- `bytesInBatch` (long)

## ES-Specific Types (`adapter/` package)

These types are ES-specific and live outside the core IR:

- `EsShardPartition` — `Partition` implementation: `snapshotName`, `indexName`, `shardNumber`
- `IndexMetadataSnapshot` — ES index metadata: `indexName`, `numberOfShards`, `numberOfReplicas`, `mappings`, `settings`, `aliases`
- `GlobalMetadataSnapshot` — ES global metadata: `templates`, `indexTemplates`, `componentTemplates`, `indices`

## Port Interfaces

**`DocumentSource`** (`source/`) — generic document source contract.
- `listCollections()` → `List<String>`
- `listPartitions(collectionName)` → `List<Partition>`
- `readCollectionMetadata(collectionName)` → `CollectionMetadata`
- `readDocuments(partition, startingDocOffset)` → `Flux<Document>`

**`DocumentSink`** (`sink/`) — generic document sink contract. The sink never sees source partitioning.
- `createCollection(metadata)` → `Mono<Void>`
- `writeBatch(collectionName, batch)` → `Mono<BatchResult>`

**`GlobalMetadataSource`** (`adapter/`) — optional, ES-specific.
- `readGlobalMetadata()` → `GlobalMetadataSnapshot`
- `readIndexMetadata(indexName)` → `IndexMetadataSnapshot`

**`GlobalMetadataSink`** (`adapter/`) — optional, ES-specific.
- `writeGlobalMetadata(metadata)` → `Mono<Void>`
- `createIndex(metadata)` → `Mono<Void>`

## Implementations

| Interface | Implementation | Module |
|---|---|---|
| `DocumentSource` | `LuceneSnapshotSource` | SnapshotReader |
| `DocumentSource` | `SyntheticDocumentSource` (test fixture) | RfsPipeline testFixtures |
| `DocumentSink` | `OpenSearchDocumentSink` | RFS |
| `GlobalMetadataSource` | `SnapshotMetadataSource` | SnapshotReader |
| `GlobalMetadataSink` | `OpenSearchMetadataSink` | RFS |
