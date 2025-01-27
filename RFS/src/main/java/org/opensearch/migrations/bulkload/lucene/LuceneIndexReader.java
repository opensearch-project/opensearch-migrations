package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;

public interface LuceneIndexReader {
    LuceneLiveDocs getLiveDocs();
    int maxDoc();
    LuceneDocument document(int docIdx) throws IOException;
}