package org.opensearch.migrations.bulkload.lucene.version_5;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;

import lombok.AllArgsConstructor;
import lombok.Getter;
import shadow.lucene5.org.apache.lucene.index.DirectoryReader;

@AllArgsConstructor
public class DirectoryReader5 implements LuceneDirectoryReader {

    private final DirectoryReader wrapped;
    @Getter
    private final Path indexDirectoryPath;

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public List<LeafReaderContext5> leaves() {
        return wrapped.leaves()
                .stream()
                .map(LeafReaderContext5::new)
                .toList();
    }

    public void close() throws IOException {
        wrapped.close();
    }
}
