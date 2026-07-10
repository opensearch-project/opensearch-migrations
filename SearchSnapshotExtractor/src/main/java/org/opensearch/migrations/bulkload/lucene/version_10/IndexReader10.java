package org.opensearch.migrations.bulkload.lucene.version_10;

import java.io.IOException;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneIndexReader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import shadow.lucene10.org.apache.lucene.index.DirectoryReader;
import shadow.lucene10.org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import shadow.lucene10.org.apache.lucene.store.Directory;
import shadow.lucene10.org.apache.lucene.store.FSDirectory;

@AllArgsConstructor
@Slf4j
public class IndexReader10 implements LuceneIndexReader {

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;

    public LuceneDirectoryReader getReader(String segmentsFileName) throws IOException {
        try (var directory = FSDirectory.open(indexDirectoryPath)) {
            return openReader(directory, segmentsFileName);
        }
    }

    /**
     * Opens a reader using a pre-built Directory. Use this with {@link MappedDirectory}
     * to read Solr backups where files have UUID names.
     */
    public LuceneDirectoryReader getReader(Directory directory, String segmentsFileName) throws IOException {
        return openReader(directory, segmentsFileName);
    }

    private LuceneDirectoryReader openReader(Directory directory, String segmentsFileName) throws IOException {
        var commits = DirectoryReader.listCommits(directory);
        var relevantCommit = commits.stream()
            .filter(commit -> segmentsFileName.equals(commit.getSegmentsFileName()))
            .findAny()
            .orElseThrow(() -> new IOException("No such commit with segments file: " + segmentsFileName));
        var reader = DirectoryReader.open(relevantCommit, 0, null);
        if (softDeletesPossible) {
            reader = new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
        }
        return new DirectoryReader10(reader, indexDirectoryPath);
    }
}
