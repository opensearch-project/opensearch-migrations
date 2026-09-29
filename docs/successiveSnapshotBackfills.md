# Successive snapshot backfills

Use `snapshotSequence` to migrate a baseline snapshot and then apply the changes
from successive snapshots to the same target. Each snapshot is created after the
preceding backfill and its approval have finished. The source can continue
accepting writes between snapshots.

The [complete example](../orchestrationSpecs/examples/successive-snapshots.yaml)
declares a baseline and two catch-up rounds. Configure its endpoints, snapshot
repository, authentication, and index selection for your deployment, then use the
usual workflow configuration and start commands.

```yaml
snapshotMigrationConfigs:
  - fromSource: source
    toTarget: target
    snapshotSequence: [baseline, catchup, final]
    perSnapshotConfig:
      baseline:
        - metadataMigrationConfig: {}
          documentBackfillConfig: {}
      catchup:
        - metadataMigrationConfig: {}
          documentBackfillConfig: {}
      final:
        - metadataMigrationConfig: {}
          documentBackfillConfig: {}
```

The labels must also exist in `sourceClusters.source.snapshotInfo.snapshots`.
Each sequence label has exactly one document backfill configuration. All labels
use the same snapshot repository and document `indexAllowlist`. An
`externallyManagedSnapshotName` can replace snapshot creation for any label; the
sequence still controls when its backfill runs. External snapshots must be
complete and immutable.

The workflow performs:

1. Create `baseline`, migrate its metadata, and backfill its documents.
2. Wait for the baseline's document backfill approval.
3. Create `catchup`. Apply **all deletions across all shards**, then migrate its
   metadata and apply additions and updates. Wait for approval.
4. Create `final` and repeat the delete, metadata, add, and approval stages.

Approval gates use the normal `workflow approve step` commands. Setting
`documentBackfillConfig.skipApproval: true` skips the backfill gate for that round.
For unattended execution, also set `metadataMigrationConfig.skipEvaluateApproval`
and `metadataMigrationConfig.skipMigrateApproval` to `true`, or set the top-level
`skipApprovals: true` to disable approval gates by default.
Without `snapshotSequence`, snapshot migrations retain their
independent execution behavior.

## Correctness and restart behavior

The delta reader compares immutable Lucene segment identities and live-document
bitsets. An unchanged segment contributes no writes. Removed segments and newly
deleted live documents contribute deletes; new segments and newly live documents
contribute additions. A merge can therefore delete and then re-add an unchanged
document. Old Lucene segments without immutable IDs are conservatively rewritten.

Delete and add phases have separate migration resources, worker sessions, and
checkpoints. Finishing the entire delete phase before starting the add phase
prevents overlapping bulk requests or shard workers from deleting a replacement
document. Resume offsets count emitted documents, including when Lucene document
IDs contain gaps. An approval completion checksum persists after each round, so
restarting the workflow neither repeats completed work nor bypasses a pending
approval. A backfill completed with document errors cannot become a baseline for
the next round.

The resource dependency graph includes both snapshots and the preceding
migration. The normal workflow reset commands therefore include dependent delta
rounds. Keep completed resources and append new labels to the end of the sequence
when adding rounds; do not reorder an already applied sequence. Changing a
delta's baseline or phase requires resetting dependent resources and rebuilding
the target from the baseline.

## Requirements and limits

- Preserve source document IDs and complete stored `_source`. Sequences reject
  server-generated IDs, source reconstruction, and document transformations.
  Both snapshots are checked for disabled or filtered `_source`.
- Retain both snapshots until their delta finishes, including retries. A missing
  or unreadable baseline fails the migration. Provision worker disk and
  `maxShardSizeBytes` for the **combined** old and new shard sizes.
- The target's migrated documents must reflect the completed preceding
  backfill. Do not modify or delete those documents outside the sequence. This
  assumption is not a remote content comparison. Target-only documents are
  preserved.
- Use one migration configuration for the sequence's source and target. A
  traffic replayer targeting the same cluster must depend on the final snapshot;
  it starts after that snapshot's successful backfill and approval.
- Adding and removing source indices is supported. Removing an index removes
  its migrated documents; it leaves the target index and any target-only
  documents in place. Metadata migration remains optional per round and follows
  its existing mapping/settings compatibility rules.
- Snapshots are not a transaction across shards. Stop source writes before the
  final cutover snapshot, or use the existing capture/replay workflow to account
  for writes after it. Source and target plugins and mappings must remain
  compatible with snapshot migration.

For direct worker use, `--previous-snapshot-name` and `--delta-mode` expose the
same delta reader. Complete `DELETES_ONLY` on every shard before starting
`UPDATES_ONLY`, with a different `--session-name` for each phase. The previous
experimental flag aliases remain accepted. The legacy `UPDATES_AND_DELETES`
option orders batches within one shard; it does not provide the workflow's
barrier across workers.

Jenkins's EKS integration suite includes Test0090. It verifies multiple rounds
against real source and target clusters, including inserts, updates, deletes,
nested documents, added/removed/recreated indices, segment merges, target-only
data, and an unchanged snapshot that must not rewrite target documents.
