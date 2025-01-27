package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;

public interface InnerDocumentsReader {
    LuceneDirectoryReader getReader() throws IOException;
}
