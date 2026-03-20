package org.opensearch.migrations.bulkload.solr;

import java.nio.file.Path;

import org.opensearch.migrations.bulkload.pipeline.ir.Partition;

/**
 * Partition representing a Solr shard within a collection.
 * For backup-based sources, carries the filesystem path to the shard's Lucene index.
 */
public record SolrShardPartition(String collection, String shard, Path indexPath) implements Partition {

    /** Constructor for API-based sources where no filesystem path is needed. */
    public SolrShardPartition(String collection, String shard) {
        this(collection, shard, null);
    }

    @Override
    public String name() {
        return collection + "/" + shard;
    }

    @Override
    public String collectionName() {
        return collection;
    }
}
