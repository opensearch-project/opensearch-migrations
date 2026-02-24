# Pipeline Architecture

The pipeline uses a **ports-and-adapters** (hexagonal) architecture. The core module (`rfs-pipeline`) defines IR types and port interfaces with zero dependencies on Lucene or OpenSearch. Adapters in separate modules implement the ports.

```mermaid
graph TD
    subgraph "Core — rfs-pipeline (zero external deps)"
        IR["IR Types<br/><i>DocumentChange, IndexMetadataSnapshot,<br/>GlobalMetadataSnapshot, ShardId, ProgressCursor</i>"]
        PORTS["Port Interfaces<br/><i>DocumentSource, DocumentSink,<br/>MetadataSource, MetadataSink</i>"]
        PIPES["Pipelines<br/><i>MigrationPipeline, MetadataMigrationPipeline</i>"]
        TD["Test Doubles<br/><i>SyntheticDocumentSource, CollectingDocumentSink,<br/>SyntheticMetadataSource, CollectingMetadataSink</i>"]
    end

    subgraph "Source Adapters — SnapshotReader"
        LSS["LuceneSnapshotSource"]
        SMS["SnapshotMetadataSource"]
        IMC["IndexMetadataConverter"]
    end

    subgraph "Sink Adapters — RFS"
        ODS["OpenSearchDocumentSink"]
        OMS["OpenSearchMetadataSink"]
        OIC["OpenSearchIndexCreator"]
        PDR["PipelineDocumentsRunner"]
    end

    subgraph "Wiring — DocumentsFromSnapshotMigration"
        PR["PipelineRunner"]
        MAIN["RfsMigrateDocuments<br/><i>--use-pipeline flag</i>"]
    end

    LSS --> PORTS
    SMS --> PORTS
    ODS --> PORTS
    OMS --> PORTS
    PDR --> PIPES
    PR --> LSS
    PR --> ODS
    MAIN --> PR

    style IR fill:#fff9c4,stroke:#f9a825
    style PORTS fill:#e1f5fe,stroke:#0288d1
    style PIPES fill:#e1f5fe,stroke:#0288d1
    style TD fill:#e1f5fe,stroke:#0288d1
    style LSS fill:#fff3e0,stroke:#f57c00
    style SMS fill:#fff3e0,stroke:#f57c00
    style ODS fill:#e8f5e9,stroke:#388e3c
    style OMS fill:#e8f5e9,stroke:#388e3c
    style PDR fill:#e8f5e9,stroke:#388e3c
    style PR fill:#f3e5f5,stroke:#7b1fa2
    style MAIN fill:#f3e5f5,stroke:#7b1fa2
```

Key constraint: **RFS does not depend on SnapshotReader** and vice versa. They only share `rfs-pipeline`.

## Module Boundaries

| Module | Responsibility | Dependencies |
|---|---|---|
| `rfs-pipeline` | IR types, port interfaces, pipeline orchestration, test doubles | None (zero external deps) |
| `SnapshotReader` | Source adapters (`LuceneSnapshotSource`, `SnapshotMetadataSource`) | `rfs-pipeline`, Lucene |
| `RFS` | Sink adapters (`OpenSearchDocumentSink`, `OpenSearchMetadataSink`), work coordination | `rfs-pipeline`, OpenSearch client |
| `DocumentsFromSnapshotMigration` | Top-level wiring (`PipelineRunner`, CLI flag) | All of the above |
