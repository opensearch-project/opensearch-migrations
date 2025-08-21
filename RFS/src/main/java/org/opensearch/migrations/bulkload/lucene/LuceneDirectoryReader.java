package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface LuceneDirectoryReader extends AutoCloseable {

    public int maxDoc();

    public List<? extends LuceneLeafReaderContext> leaves();

    public Path getIndexDirectoryPath();

    public void close() throws IOException;

}
