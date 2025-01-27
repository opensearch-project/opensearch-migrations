package org.opensearch.migrations.bulkload.lucene.lucene9;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.Uid;
import org.opensearch.migrations.bulkload.lucene.InnerDocumentsReader;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import shadow.lucene9.org.apache.lucene.document.Document;
import shadow.lucene9.org.apache.lucene.index.DirectoryReader;
import shadow.lucene9.org.apache.lucene.index.IndexReader;
import shadow.lucene9.org.apache.lucene.index.LeafReaderContext;
import shadow.lucene9.org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

@AllArgsConstructor
@Slf4j
public class InnerDocumentsReaderLucene9 implements InnerDocumentsReader {

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;

    public LuceneDirectoryReader getReader() throws IOException {
        try (var directory = FSDirectory.open(indexDirectoryPath)) {
            var commits = DirectoryReader.listCommits(directory);
            var latestCommit = commits.get(commits.size() - 1);
            var reader = DirectoryReader.open(latestCommit, 0, null);
            if (softDeletesPossible) {
                reader = new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
            }

            return new LuceneDirectoryReader(reader);
        }
    }

    @AllArgsConstructor
    private final class Lucene9DirectoryReader implements LuceneDirectoryReader {
        DirectoryReader reader;

        @Override
        public int maxDoc() {
            return reader.maxDoc();
        }

        @Override
        public List<LuceneLeafReaderContext> leaves() {
            return reader.leaves().stream().map();
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }
}
