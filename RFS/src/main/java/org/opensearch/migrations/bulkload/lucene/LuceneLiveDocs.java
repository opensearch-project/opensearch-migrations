package org.opensearch.migrations.bulkload.lucene;

public interface LuceneLiveDocs {
    boolean get(int docIdx);
}