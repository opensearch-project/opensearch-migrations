package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface LuceneDirectoryReader extends AutoCloseable {
    int maxDoc();
    List<? extends LuceneLeafReaderContext> leaves();
    Path getIndexDirectoryPath();
    void close() throws IOException;
}
