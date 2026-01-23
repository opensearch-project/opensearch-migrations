package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.LoggerFactory;

public interface LuceneDirectoryReader extends AutoCloseable {
    int maxDoc();
    List<? extends LuceneLeafReaderContext> leaves();
    Path getIndexDirectoryPath();
    void close() throws IOException;

    static Runnable getCleanupRunnable(LuceneDirectoryReader... readers) {
        return () -> {
            for (var reader : readers) {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        LoggerFactory.getLogger(LuceneDirectoryReader.class)
                            .atWarn()
                            .setCause(e)
                            .setMessage("{}")
                            .addArgument(() -> "Unable to close reader for " + reader.getIndexDirectoryPath())
                            .log();
                    }
                }
            }
        };
    }
}
