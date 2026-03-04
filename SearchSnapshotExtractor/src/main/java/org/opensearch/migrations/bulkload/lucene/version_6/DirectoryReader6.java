package org.opensearch.migrations.bulkload.lucene.version_6;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;

import lombok.AllArgsConstructor;
import lombok.Getter;
import shadow.lucene6.org.apache.lucene.index.DirectoryReader;

@AllArgsConstructor
public class DirectoryReader6 implements LuceneDirectoryReader {

    private final DirectoryReader wrapped;
    @Getter
    private final Path indexDirectoryPath;

    public int maxDoc() {
        return wrapped.maxDoc();
    }

    public List<LeafReaderContext6> leaves() {
        return wrapped.leaves()
            .stream()
            .map(LeafReaderContext6::new)
            .toList();
    }

    public void close() throws IOException {
        wrapped.close();
    }
}
