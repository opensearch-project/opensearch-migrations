# Validate a full backfill with snapshot document counts

When the source keeps ingesting, its current document count no longer describes
the snapshot RFS is migrating. RFS reports each snapshot shard's live Lucene
document count so you can build a stable baseline for the backfill.

## Aggregate shard counts by index

1. Wait for the full backfill to complete and collect counts for every migrated
   shard from that run.
2. Keep one count per `(snapshot, index, shard)`. Retries can report the same
   shard again; repeated counts must agree and must not be added together.
3. Sum those shard counts by index. This is the expected `_cat/indices`
   `docs.count` contributed by the snapshot, including nested documents.
4. Refresh the target indices and compare their `_cat/indices` `docs.count`
   with the corresponding index totals.

For example, shard counts of `8` and `6` give an expected index count of `14`.
That includes nested children: an index containing five root documents and nine
nested children has `docs.count=14` but `_count=5`. The target may use a different
number of shards; compare index totals.

A direct comparison assumes an initially empty target, preserved nested
mappings, and transformations that preserve document counts. Concurrent target
writes or deletes, existing data, filtering, or splitting documents change the
expected target count. The baseline describes the snapshot's contribution,
not the current contents of an independently changing target. Matching counts
alone do not prove matching document contents; also check migration failures.

**Delta backfills are not supported by this validation method.** Missing or
unavailable shard counts mean the baseline is incomplete. A known empty shard
contributes `0`; an unavailable count must never be treated as zero.

## Log output

Each partition's migration statistics include:

```text
Partition migration stats: index=example, shard=0, sourcePartition=snap/example/0,
docsThisGeneration=3, ..., shardLiveLuceneDocs=8
```

`sourcePartition` identifies the snapshot, index, and shard.
`shardLiveLuceneDocs` is the **whole snapshot shard's** count, including when a
worker resumes partway through it. It is captured from the open Lucene reader
without another document scan. Unsupported sources and delta reads log
`unknown`; empty shards log `0`.

Use `shardLiveLuceneDocs` for the baseline. The generation statistics describe
only that worker's processing and can overlap across retries.
