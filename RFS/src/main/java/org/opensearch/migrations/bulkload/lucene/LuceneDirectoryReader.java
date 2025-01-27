package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.util.List;

public interface LuceneDirectoryReader {
    int maxDoc();
    List<LuceneLeafReaderContext> leaves();
    void close() throws IOException;
}