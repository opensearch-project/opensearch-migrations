package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.lucene.version_10.IndexReader10;
import org.opensearch.migrations.bulkload.lucene.version_5.IndexReader5;
import org.opensearch.migrations.bulkload.lucene.version_6.IndexReader6;
import org.opensearch.migrations.bulkload.lucene.version_7.IndexReader7;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

public interface LuceneIndexReader {
    LuceneDirectoryReader getReader(String segmentsFileName) throws IOException;

    @Slf4j
    @AllArgsConstructor
    class Factory {
        private final ClusterSnapshotReader snapshotReader;

        public LuceneIndexReader getReader(Path path) {
            var caps = snapshotReader.getCapabilities();
            log.atInfo()
                .setMessage("Creating IndexReader for Lucene version: {}")
                .addArgument(caps.luceneVersion())
                .log();
            return switch (caps.luceneVersion()) {
                case LUCENE_5 -> new IndexReader5(path);
                case LUCENE_6 -> new IndexReader6(path);
                case LUCENE_7 -> new IndexReader7(path, caps.softDeletesPossible(), caps.softDeletesFieldName());
                case LUCENE_9 -> new IndexReader9(path, caps.softDeletesPossible(), caps.softDeletesFieldName());
                case LUCENE_10 -> new IndexReader10(path, caps.softDeletesPossible(), caps.softDeletesFieldName());
            };
        }
    }
}
