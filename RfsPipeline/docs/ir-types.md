# IR Types and Port Interfaces

The pipeline defines a source-agnostic intermediate representation (IR). All types are Java `record`s or interfaces with compact constructor validation and zero runtime dependencies beyond Reactor. Port interfaces use `Flux` and `Mono` for reactive streaming.

## IR Types (`model/` package)

All types in this package are source-agnostic — no ES, Lucene, or OpenSearch concepts.

- **`Document`** — A single document flowing through the pipeline. Supports UPSERT and DELETE operations. Carries opaque `hints` for sink-specific routing and `sourceMetadata` for diagnostics.
- **`Partition`** — Interface for any source partitioning scheme (e.g., ES shards, S3 prefixes).
- **`CollectionMetadata`** — Metadata for creating a target collection. Carries opaque `sourceConfig` for source-specific settings.
- **`ProgressCursor`** — Resumability tracking per partition.
- **`BatchResult`** — Batch-local stats returned by the sink.

## ES-Specific Types (`adapter/` package)

These types are ES-specific and live outside the core IR:

- **`EsShardPartition`** — `Partition` implementation for ES snapshot shards.
- **`IndexMetadataSnapshot`** — ES index metadata (mappings, settings, aliases).
- **`GlobalMetadataSnapshot`** — ES global metadata (templates, component templates, indices).

## Port Interfaces

- **`DocumentSource`** (`source/`) — generic document source contract. Lists collections and partitions, reads metadata, streams documents.
- **`DocumentSink`** (`sink/`) — generic document sink contract. Creates collections and writes document batches. The sink never sees source partitioning.
- **`GlobalMetadataSource`** (`adapter/`) — optional, ES-specific. Reads global and index metadata.
- **`GlobalMetadataSink`** (`adapter/`) — optional, ES-specific. Writes global metadata and creates indices.

## Implementations

| Interface | Implementation | Module |
|---|---|---|
| `DocumentSource` | `LuceneSnapshotSource` | SnapshotReader |
| `DocumentSource` | `SyntheticDocumentSource` (test fixture) | RfsPipeline testFixtures |
| `DocumentSink` | `OpenSearchDocumentSink` | RFS |
| `GlobalMetadataSource` | `SnapshotMetadataSource` | SnapshotReader |
| `GlobalMetadataSink` | `OpenSearchMetadataSink` | RFS |
