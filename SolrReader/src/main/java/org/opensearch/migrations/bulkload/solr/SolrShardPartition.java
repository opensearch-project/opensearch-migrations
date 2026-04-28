package org.opensearch.migrations.bulkload.solr;

import java.nio.file.Path;
import java.util.Map;

import org.opensearch.migrations.bulkload.pipeline.model.Partition;

/**
 * Partition representing a Solr shard within a collection.
 * For backup-based sources, carries the filesystem path to the shard's Lucene index.
 * For SolrCloud backups with UUID-named files, also carries a filename mapping
 * (logical Lucene name → physical UUID on disk).
 */
public record SolrShardPartition(
    String collection,
    String shard,
    Path indexPath,
    Map<String, String> fileNameMapping
) implements Partition {

    /** Constructor for filesystem-based sources (standalone backups). */
    public SolrShardPartition(String collection, String shard, Path indexPath) {
        this(collection, shard, indexPath, null);
    }

    /** Constructor for API-based sources where no filesystem path is needed. */
    public SolrShardPartition(String collection, String shard) {
        this(collection, shard, null, null);
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
