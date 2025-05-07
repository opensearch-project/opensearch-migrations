package org.opensearch.migrations.bulkload.lucene.version_6;

import java.io.IOException;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene6.org.apache.lucene.index.DirectoryReader;
import shadow.lucene6.org.apache.lucene.store.FSDirectory;

@AllArgsConstructor
@Slf4j
public class IndexReader6 implements LuceneIndexReader {

    protected final Path indexDirectoryPath;

    public LuceneDirectoryReader getReader() throws IOException {
        try (var directory = FSDirectory.open(indexDirectoryPath)) {
            var commits = DirectoryReader.listCommits(directory);
            var latestCommit = commits.get(commits.size() - 1);

            var reader = DirectoryReader.open(latestCommit);
            return new DirectoryReader6(reader, indexDirectoryPath);
        }
    }
}
