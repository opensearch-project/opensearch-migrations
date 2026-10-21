package org.opensearch.migrations.reindexer.doccount;

import reactor.core.publisher.Mono;

/**
 * Append-only sink for snapshot shard counts used to validate a full backfill.
 *
 * <p>{@link #flush()} must be awaited before the work coordinator marks the corresponding work item
 * complete, so a failed flush leaves the item incomplete and a successor re-emits. Records are
 * therefore at-least-once. Deduplicate by (sessionId, snapshotName, indexName, shardNumber) before summing.
 * Counts describe the snapshot baseline, not successful target writes. Unknown counts are null.
 */
public interface ShardDocCountSink extends AutoCloseable {

    Mono<Void> write(ShardDocCountRecord countRecord);

    Mono<Void> flush();

    String getLocation();

    @Override
    void close();
}
