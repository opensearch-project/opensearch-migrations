package org.opensearch.migrations.bulkload.lucene.version_9;

public class IgnoreBloomFilter85 extends IgnoreBloomFilter {
    public IgnoreBloomFilter85() {
        super("ES85BloomFilter");
    }
}
