# IR Types and Port Interfaces

The pipeline defines a source-agnostic intermediate representation (IR). All types are Java `record`s or interfaces with compact constructor validation and zero runtime dependencies beyond Reactor. Port interfaces use `Flux` and `Mono` for reactive streaming.

## IR Types (`model/` package)

All types in this package are source-agnostic — no ES, Lucene, or OpenSearch concepts.

- **`Document`** — A single document flowing through the pipeline. Supports UPSERT and DELETE operations. Carries opaque `hints` for sink-specific routing and write options, and `sourceMetadata` for diagnostics and transformations. Lucene sources set a `version` hint for indexed documents with a version and source ID, and retain the original `_version` in source metadata. The OpenSearch sink chooses `external` for a supplied version hint and exposes the source metadata as `source_metadata`, outside the bulk action and document body. Keeping the write hint separate from source metadata lets transformations change the requested version without losing its original value. Both `source_metadata._version` and `operation.version` are decimal strings in transformation input to preserve 64-bit precision; bulk requests serialize the action version as an integer. The `externalVersioning.js` modifier supports `internal`, `external`, and `external_gte`; internal mode removes the action's version, while external modes retain it or select a configured source field.
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
