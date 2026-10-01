# Successive snapshot backfills

Declare a `backfill` with a `repeat` policy to create a baseline snapshot and then
apply changes from successive snapshots to the same target. Declare snapshot and
migration options once; the workflow generates the names and takes each new
snapshot after the preceding backfill and its approval finish.

The [complete example](../orchestrationSpecs/examples/successive-snapshots.yaml)
allows up to three rounds, stopping when snapshot lag is at most two minutes.
Configure its endpoints, snapshot
repository, authentication, and index selection for your deployment, then use the
usual workflow configuration and start commands.

```yaml
snapshotMigrationConfigs:
  - fromSource: source
    toTarget: target
    backfill:
      snapshot:
        repoName: migration
        createSnapshotConfig:
          indexAllowlist: [orders]
      metadataMigrationConfig: {}
      documentBackfillConfig:
        indexAllowlist: [orders]
      repeat:
        maxRuns: 3
        until:
          snapshotLag: 2m
```

`repoName` refers to `sourceClusters.source.snapshotInfo.repos.migration`. No
snapshot names or `perSnapshotConfig` entries are needed.

- `maxRuns` includes the initial full backfill. Later rounds apply deltas.
  Without `until`, the workflow performs exactly that many rounds.
- `until.snapshotLag` accepts a positive duration such as `30s`, `2m`, `1h`, or
  `1d`. The workflow checks it after each complete backfill, never between a
  delta's delete and add phases.
- Snapshot lag is the recorded backfill completion time minus the source's
  recorded snapshot start time. Completion is observed by the backfill monitor,
  so its polling delay is included. Approval delays and subsequent restarts do
  not change the measurement. Snapshots are not transactions across shards;
  this metric does not guarantee that every source write has reached the target.
- If the lag target is met, later snapshots are not created. If `maxRuns` is
  exhausted first, the workflow records `lagTargetNotMet` and fails before
  starting replay. A run limit without a lag target completes normally.
- The workflow records the run number, measured lag, and stop reason on the
  round's `SnapshotMigration` status. Logical labels are `backfill-1`,
  `backfill-2`, and so on; actual snapshot names include the durable
  `DataSnapshot` UID, so a retry uses the same snapshot.
- Increase `maxRuns` to continue an existing policy while preserving completed
  rounds. Decreasing a started budget or changing its lag target requires a
  reset. `maxRuns` is a finite budget between 1 and 1,000; the size of the generated
  plan also limits how many rounds can be deployed.
- A traffic replayer for this target automatically waits for successful policy
  completion. It needs no reference to a generated final snapshot. Once replay
  or other target writes have started, do not extend the backfill policy.

Approval gates use the normal `workflow approve step` commands. Set
`documentBackfillConfig.skipApproval: true` to skip the backfill gate. For
unattended execution, also set `metadataMigrationConfig.skipEvaluateApproval`
and `metadataMigrationConfig.skipMigrateApproval` to `true`, or set the top-level
`skipApprovals: true` to disable approval gates by default.

## Explicit snapshot sequences

Use `snapshotSequence` when choosing specific snapshots, including snapshots
created outside the workflow:

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

Use either `backfill` or `perSnapshotConfig` in a migration block. A `backfill`
policy owns its source's snapshots; use `snapshotSequence` to manage explicit
labels instead. Without either repetition form, snapshot migrations retain their
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

- For repeat policies, the initializer rejects a generated `MigrationRun` larger
  than 1 MiB of serialized JSON before writing manifests or apply scripts. This conservative
  budget leaves room for Kubernetes metadata below storage and request limits.
  The plan includes every possible round, so an `until` condition does not reduce
  its initial size. A `maxRuns` value at or below 1,000 does not guarantee the plan
  fits: reduce `backfill.repeat.maxRuns` or the configuration size if validation
  rejects it.
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
- Use one migration configuration for the sequence's source and target. For an
  explicit `snapshotSequence`, a traffic replayer targeting the same cluster
  must depend on the final snapshot. For a `backfill` policy this ordering is
  automatic. Replay starts after the successful backfill and approval.
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

Jenkins's EKS integration suite includes Test0090. It verifies generated rounds
against real source and target clusters, including inserts, updates, deletes,
nested documents, added/removed/recreated indices, segment merges, target-only
data, and an unchanged snapshot that must not rewrite target documents.
Tests0091 and 0092 verify early completion on a lag target and explicit failure
when the run budget is exhausted before meeting that target.
