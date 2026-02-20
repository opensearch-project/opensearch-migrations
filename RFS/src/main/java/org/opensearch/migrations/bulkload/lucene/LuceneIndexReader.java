package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;

import org.opensearch.migrations.VersionMatchers;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.lucene.version_5.IndexReader5;
import org.opensearch.migrations.bulkload.lucene.version_6.IndexReader6;
import org.opensearch.migrations.bulkload.lucene.version_7.IndexReader7;
import org.opensearch.migrations.bulkload.lucene.version_9.IndexReader9;
import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import lombok.AllArgsConstructor;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

public interface LuceneIndexReader {
    /**
     * There are a variety of states the documents in our Lucene Index can be in; this method extracts those documents
     * that would be considered "live" from the ElasticSearch/OpenSearch perspective.  The most important thing to know is
     * that Lucene segments are immutable.  For additional context, it is highly recommended to read this section of the
     * Lucene docs for a high level overview of the topics involved:
     *
     * https://lucene.apache.org/core/8_0_0/core/org/apache/lucene/codecs/lucene80/package-summary.html
     *
     * When ElasticSearch/OpenSearch deletes a document, it doesn't actually remove it from the Lucene Index.  Instead, what
     * happens is that the document is marked as "deleted" in the Lucene Index, but it is still present in the Lucene segment
     * on disk.  The next time a merge occurs, that segment will be deleted, and the deleted documents in it are thereby
     * removed from the Lucene Index.  A similar thing happens when a document is updated; the old document is marked as
     * "deleted" in the Lucene segment and the new version of the document is added in a new Lucene segment.  Until a merge
     * occurs, both the old and new versions of the document will exist in the Lucene Index in different segments, though only
     * the new version will be returned in search results.  This means that from an ES/OS perspective, you could have a single
     * document that has been created, deleted, recreated, updated, etc. multiple times at the Elasticsearch/OpenSearch level
     * and only a single version of the doc would exist when you queried the ES/OS Index - but every single iteration of that
     * document might still exist in the Lucene segments on disk, all of which have the same _id (from the ES/OS perspective).
     *
     * Additionally, Elasticsearch 7 introduced a feature called "soft deletes" which allows you to mark a document as
     * "deleted" in the Lucene Index without actually removing it from the Lucene Index.  From what I can gather, soft deletes
     * are an optimization to reduce the likelyhood of needing to re-download full shards when a node drops out of the cluster,
     * loses synchronization, and re-joins.  They make it more likely the cluster can just replay the missed operations.  You
     * can read a bit more about soft deletes here:
     *
     * https://www.elastic.co/guide/en/elasticsearch/reference/7.10/index-modules-history-retention.html
     *
     * Soft deletes works by having the application writing the Lucene Index define a field that is used to mark a document as
     * "soft deleted" or not.  When a document is marked as "soft deleted", it is not returned in search results, but it is
     * still present in the Lucene Index.  The status of whether any given document is "soft deleted" or not is stored in the
     * Lucene Index itself.  By default, Elasticsearch 7+ Indices have soft deletes enabled;  this is an Index-level setting.
     * Just like deleted documents and old versions of updated documents, we don't want to reindex them agaisnt the target
     * cluster.
     *
     * In order to retrieve only those documents that would be considered "live" in ES/OS, we use a few tricks:
     * 1. We make sure we use the latest Lucene commit point on the Lucene Index.  A commit is a Lucene abstraction that
     *     comprises a consistent, point-in-time view of the Segments in the Lucene Index.  By default, a DirectoryReader
     *     will use the latest commit point.
     * 2. We use a concept called "liveDocs" to determine if a document is "live" or not.  The liveDocs are a bitset that
     *    is associated with each Lucene segment that tells us if a document is "live" or not.  If a document is hard-deleted
     *    (i.e. deleted from the ES/OS perspective or an old version of an updated doc), then the liveDocs bit for that
     *    document will be false.
     * 3. We wrap our DirectoryReader in a SoftDeletesDirectoryReaderWrapper if it's possible that soft deletes will be
     *    present in the Lucene Index.  This wrapper will filter out documents that are marked as "soft deleted" in the
     *    Lucene Index.

     */
    default Flux<LuceneDocumentChange> streamDocumentChanges(String segmentsFileName, int startDocIdx) {
        return Flux.using(
            () -> this.getReader(segmentsFileName),
            reader -> LuceneReader.readDocsByLeavesFromStartingPosition(reader, startDocIdx),
            reader -> {
                try {
                    reader.close();
                } catch (IOException e) {
                    throw Lombok.sneakyThrow(e);
                }
        });
    }

    default Flux<LuceneDocumentChange> streamDocumentChanges(String segmentsFileName) {
        return streamDocumentChanges(segmentsFileName, 0);
    }

    LuceneDirectoryReader getReader(String segmentsFileName) throws IOException;

    @Slf4j
    @AllArgsConstructor
    class Factory {
        private final ClusterSnapshotReader snapshotReader;

        public LuceneIndexReader getReader(Path path) {
            if (VersionMatchers.isES_2_X.or(VersionMatchers.isES_1_X).test(snapshotReader.getVersion())) {
                log.atInfo().setMessage("Creating IndexReader5").log();
                return new IndexReader5(
                    path
                );
            } else if (VersionMatchers.isES_5_X.test(snapshotReader.getVersion())) {
                log.atInfo().setMessage("Creating IndexReader6").log();
                return new IndexReader6(
                        path
                );
            } else if (VersionMatchers.isES_6_X.test(snapshotReader.getVersion())) {
                log.atInfo().setMessage("Creating IndexReader7").log();
                return new IndexReader7(
                    path,
                    snapshotReader.getSoftDeletesPossible(),
                    snapshotReader.getSoftDeletesFieldData()
                );
            } else {
                log.atInfo().setMessage("Creating IndexReader9").log();
                return new IndexReader9(
                    path,
                    snapshotReader.getSoftDeletesPossible(),
                    snapshotReader.getSoftDeletesFieldData()
                );
            }
        }
    }
}
