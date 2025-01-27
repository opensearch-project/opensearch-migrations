package org.opensearch.migrations.bulkload.lucene;

public interface LuceneLeafReaderContext {
    int ord = 0;
    LuceneIndexReader reader();
}