# Work Coordination

The pipeline supports two execution modes: standalone (single process) and coordinated (horizontal scaling across workers).

## Coordinated Mode (Production)

Multiple RFS workers each call `migrateNextShard()` in a loop. A shared `IWorkCoordinator` (backed by OpenSearch or DynamoDB) ensures no two workers process the same shard.

```mermaid
flowchart TD
    MAIN["RfsMigrateDocuments<br/><i>--use-pipeline</i>"] --> PREP["prepareWorkCoordination()"]
    PREP --> SWC["ScopedWorkCoordinator"]
    SWC --> PDR["PipelineDocumentsRunner"]
    PDR -->|"acquire shard"| WC["WorkCoordinator<br/><i>lease-based</i>"]
    PDR -->|"migrate shard"| PR["PipelineRunner.migrateNextShard()"]
    PR --> MP["MigrationPipeline.migrateShard()"]
    MP -->|"progress"| CURSOR["WorkItemCursor<br/><i>successor work items</i>"]
    WC -->|"cancellation on<br/>lease expiry"| PDR

    style MAIN fill:#f3e5f5,stroke:#7b1fa2
    style PDR fill:#e8f5e9,stroke:#388e3c
    style MP fill:#e1f5fe,stroke:#0288d1
```

### How It Works

1. Work items are created per shard as `indexName__shardNumber__startingDocId`
2. `acquireNextWorkItem()` atomically assigns an unassigned shard to the calling worker
3. Worker gets a time-limited lease — must complete or the shard gets reassigned
4. Lease doubling on retry (exponential backoff for stuck shards)
5. Successor work items for mid-shard resume — if a worker's lease expires, a new work item is created with the `startingDocId` where it left off
6. On completion, `completeWorkItem()` marks it done

## Standalone Mode (Testing / Small Migrations)

`migrateAll()` processes everything in a single process with no coordination.

```mermaid
flowchart TD
    MA["migrateAll()"] --> I1["Index A"]
    MA --> I2["Index B"]

    I1 --> S1["Shard 0"]
    I1 --> S2["Shard 1"]
    I1 --> S3["Shard 2"]

    S1 --> B1["Batch 1 → writeBatch()"]
    S1 --> B2["Batch 2 → writeBatch()"]
    S1 --> B3["Batch 3 → writeBatch()"]

    style MA fill:#e1f5fe,stroke:#0288d1
    style I1 fill:#f3e5f5,stroke:#7b1fa2
    style I2 fill:#f3e5f5,stroke:#7b1fa2
```

- Indices: sequential (`concatMap`)
- Shards within an index: configurable concurrency (`flatMap(shardConcurrency)`)
- Batches within a shard: sequential to preserve document ordering
- Batch boundaries: configurable by `maxDocsPerBatch` and `maxBytesPerBatch`

## Comparison

| | `migrateAll()` | `migrateNextShard()` |
|---|---|---|
| Scaling | Single process, parallel shards on one machine | Multiple workers, one shard per worker |
| Coordination | None | Lease-based via `IWorkCoordinator` |
| Resume | No | Yes, via `startingDocId` successor work items |
| Used by | `PipelineRunner.migrateDocuments()` | `runWithPipeline()` in `main()` |
