package org.opensearch.migrations.reindexer.doccount;

import reactor.core.publisher.Mono;

/**
 * Snapshot shard counts. Await {@link #flush()} before completing work and deduplicate retries
 * by (sessionId, snapshotName, indexName, shardNumber).
 */
public interface ShardDocCountSink extends AutoCloseable {

    Mono<Void> write(ShardDocCountRecord countRecord);

    Mono<Void> flush();

    String getLocation();

    @Override
    void close();
}
