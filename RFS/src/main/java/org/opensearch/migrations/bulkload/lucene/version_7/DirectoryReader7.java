package org.opensearch.migrations.bulkload.lucene.version_7;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;

import lombok.AllArgsConstructor;
import lombok.Getter;
import shadow.lucene7.org.apache.lucene.index.DirectoryReader;

@AllArgsConstructor
public class DirectoryReader7 implements LuceneDirectoryReader {

    private final DirectoryReader wrapped;
    @Getter
    private final Path indexDirectoryPath;

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public List<LeafReaderContext7> leaves() {
        return wrapped.leaves()
            .stream()
            .map(LeafReaderContext7::new)
            .toList();
    }

    public void close() throws IOException {
        wrapped.close();
    }
}
