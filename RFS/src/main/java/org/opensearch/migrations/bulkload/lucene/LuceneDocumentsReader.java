package org.opensearch.migrations.bulkload.lucene;

import java.nio.file.Path;

import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

public interface LuceneDocumentsReader {
    Flux<RfsLuceneDocument> readDocuments(int startSegmentIndex, int startDoc);

    @Slf4j
    @AllArgsConstructor
    public static class Factory {
        private final ClusterSnapshotReader snapshotReader;

        public LuceneDocumentsReader getReader(Path path) {
            if (VersionMatchers.isES_5_X.or(VersionMatchers.isES_6_X).test(snapshotReader.getVersion())) {
                log.atInfo().setMessage("Creating LuceneDocumentsReader7").log();
                return new LuceneDocumentsReader7(
                    path,
                    snapshotReader.getSoftDeletesPossible(),
                    snapshotReader.getSoftDeletesFieldData()
                );
            } else {
                log.atInfo().setMessage("Creating LuceneDocumentsReader9").log();
                return new LuceneDocumentsReader9(
                    path,
                    snapshotReader.getSoftDeletesPossible(),
                    snapshotReader.getSoftDeletesFieldData()
                );
            }
        }
    }
}
