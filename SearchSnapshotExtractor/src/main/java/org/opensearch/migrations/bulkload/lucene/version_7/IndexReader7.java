package org.opensearch.migrations.bulkload.lucene.version_7;

import java.io.IOException;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene7.org.apache.lucene.index.DirectoryReader;
import shadow.lucene7.org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import shadow.lucene7.org.apache.lucene.store.FSDirectory;

@AllArgsConstructor
@Slf4j
public class IndexReader7 implements LuceneIndexReader {

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;

    public LuceneDirectoryReader getReader(String segmentsFileName) throws IOException {
        try (var directory = FSDirectory.open(indexDirectoryPath)) {
            var commits = DirectoryReader.listCommits(directory);
            var relevantCommit = commits.stream()
                .filter(commit -> segmentsFileName.equals(commit.getSegmentsFileName()))
                .findAny()
                .orElseThrow(() -> new IOException("No such commit with segments file: " + segmentsFileName));

            var reader = DirectoryReader.open(relevantCommit);
            if (softDeletesPossible) {
                reader = new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
            }
            return new DirectoryReader7(reader, indexDirectoryPath);
        }
    }
}
