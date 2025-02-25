package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface MyDirectoryReader {
    public int maxDoc();

    public List<MyLeafReaderContext> leaves();

    public Path getIndexDirectoryPath();

    public void close() throws IOException;
}
