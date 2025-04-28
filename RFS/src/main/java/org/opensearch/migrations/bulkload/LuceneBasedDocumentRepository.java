package org.opensearch.migrations.bulkload;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;

public abstract class LuceneBasedDocumentRepository {
    public abstract long getShardSizeInBytes(String name, Integer shard);

    public abstract LuceneIndexReader getReader(String index, int shard);

    public abstract Stream<String> getIndexNamesInSnapshot();

    public abstract int getNumShards(String indexName);

    public Duration expectedMaxShardSetupTime() {
        return Duration.ofMinutes(10);
    }
}
