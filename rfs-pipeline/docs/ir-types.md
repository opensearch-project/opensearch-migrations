# IR Types and Port Interfaces

The pipeline defines a clean intermediate representation (IR) that is completely Lucene-agnostic. All types are Java `record`s with compact constructor validation and zero runtime dependencies.

## IR Types

```mermaid
classDiagram
    class DocumentChange {
        <<record>>
        +String id
        +String type
        +byte[] source
        +String routing
        +ChangeType operation
    }

    class ChangeType {
        <<enum>>
        INDEX
        DELETE
    }

    class IndexMetadataSnapshot {
        <<record>>
        +String indexName
        +int numberOfShards
        +int numberOfReplicas
        +ObjectNode mappings
        +ObjectNode settings
        +ObjectNode aliases
    }

    class GlobalMetadataSnapshot {
        <<record>>
        +ObjectNode templates
        +ObjectNode indexTemplates
        +ObjectNode componentTemplates
        +List~String~ indices
    }

    class ShardId {
        <<record>>
        +String snapshotName
        +String indexName
        +int shardNumber
        +toString() "snap/index/0"
    }

    class ProgressCursor {
        <<record>>
        +ShardId shardId
        +long lastDocProcessed
        +long docsInBatch
        +long bytesInBatch
    }

    DocumentChange --> ChangeType
    ProgressCursor --> ShardId
```

## Port Interfaces

Four port interfaces define the boundary between the pipeline core and external systems.

```mermaid
classDiagram
    class DocumentSource {
        <<interface>>
        +listIndices() List~String~
        +listShards(indexName) List~ShardId~
        +readIndexMetadata(indexName) IndexMetadataSnapshot
        +readDocuments(shardId, offset) Flux~DocumentChange~
    }

    class DocumentSink {
        <<interface>>
        +createIndex(metadata) Mono~Void~
        +writeBatch(shardId, indexName, batch) Mono~ProgressCursor~
    }

    class MetadataSource {
        <<interface>>
        +readGlobalMetadata() GlobalMetadataSnapshot
        +readIndexMetadata(indexName) IndexMetadataSnapshot
    }

    class MetadataSink {
        <<interface>>
        +writeGlobalMetadata(metadata) Mono~Void~
        +createIndex(metadata) Mono~Void~
    }

    DocumentSource <|.. LuceneSnapshotSource : implements
    DocumentSource <|.. SyntheticDocumentSource : test double
    DocumentSink <|.. OpenSearchDocumentSink : implements
    DocumentSink <|.. CollectingDocumentSink : test double
    MetadataSource <|.. SnapshotMetadataSource : implements
    MetadataSource <|.. SyntheticMetadataSource : test double
    MetadataSink <|.. OpenSearchMetadataSink : implements
    MetadataSink <|.. CollectingMetadataSink : test double

    style DocumentSource fill:#fff3e0,stroke:#f57c00
    style DocumentSink fill:#e8f5e9,stroke:#388e3c
    style MetadataSource fill:#fff3e0,stroke:#f57c00
    style MetadataSink fill:#e8f5e9,stroke:#388e3c
```
