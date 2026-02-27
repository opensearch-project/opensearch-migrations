# Document Migration Data Flow

Documents flow from a snapshot through the pipeline core to OpenSearch. The IR (`DocumentChange`) is the boundary — source adapters produce it, sink adapters consume it.

```mermaid
flowchart LR
    subgraph Source["Source Side"]
        SNAP[(Snapshot)]
        SE[SnapshotExtractor]
        LSS[LuceneSnapshotSource]
        LA[LuceneAdapter]
    end

    subgraph Core["Pipeline Core"]
        IR{{DocumentChange<br/><i>id, type, source,<br/>routing, operation</i>}}
        MP[MigrationPipeline<br/><i>batching + concurrency</i>]
        PC{{ProgressCursor<br/><i>shard, offset,<br/>docs, bytes</i>}}
    end

    subgraph Sink["Sink Side"]
        TX[IJsonTransformer]
        ODS[OpenSearchDocumentSink]
        BULK[Bulk API]
        OS[(OpenSearch)]
    end

    SNAP --> SE --> LSS
    LSS --> LA -->|"Flux<DocumentChange>"| IR
    IR --> MP
    MP -->|"writeBatch()"| TX --> ODS --> BULK --> OS
    MP -->|"Flux<ProgressCursor>"| PC

    style IR fill:#fff9c4,stroke:#f9a825
    style PC fill:#fff9c4,stroke:#f9a825
    style MP fill:#e1f5fe,stroke:#0288d1
```

## How It Works

1. `LuceneSnapshotSource` wraps `SnapshotExtractor` to read Lucene segments from a snapshot
2. `LuceneAdapter` converts `LuceneDocumentChange` → `DocumentChange` (stripping Lucene-specific fields)
3. `MigrationPipeline` batches documents by count and byte size, then calls `writeBatch()` on the sink
4. `OpenSearchDocumentSink` optionally applies `IJsonTransformer`, then sends a bulk request to OpenSearch
5. Each batch returns a `ProgressCursor` for resumability tracking

## Delta Snapshot Support

When configured with a previous snapshot, `LuceneSnapshotSource` uses `DeltaLuceneReader` to diff two snapshots and emit only the changes (additions and deletions).

```mermaid
flowchart LR
    SNAP1[(Previous<br/>Snapshot)] --> LSS[LuceneSnapshotSource<br/><i>delta mode</i>]
    SNAP2[(Current<br/>Snapshot)] --> LSS
    LSS -->|"deletions first,<br/>then additions"| IR{{DocumentChange}}
    IR --> MP[MigrationPipeline]

    style IR fill:#fff9c4,stroke:#f9a825
    style LSS fill:#fff3e0,stroke:#f57c00
```
