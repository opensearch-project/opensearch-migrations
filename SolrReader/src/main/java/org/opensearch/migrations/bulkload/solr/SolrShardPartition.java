package org.opensearch.migrations.bulkload.solr;

import org.opensearch.migrations.bulkload.pipeline.ir.Partition;

/**
 * Partition representing a Solr shard within a collection.
 */
public record SolrShardPartition(String collection, String shard) implements Partition {

    @Override
    public String name() {
        return collection + "/" + shard;
    }

    @Override
    public String collectionName() {
        return collection;
    }
}
