package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.opensearch.migrations.cluster.ClusterSnapshotReader;

import lombok.Lombok;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentReader;
import org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@RequiredArgsConstructor
@Slf4j
public class LuceneDocumentsReader {

    public static Function<Path, LuceneDocumentsReader> getFactory(ClusterSnapshotReader snapshotReader) {
        return path -> new LuceneDocumentsReader(
            path,
            snapshotReader.getSoftDeletesPossible(),
            snapshotReader.getSoftDeletesFieldData()
        );
    }

    protected final Path indexDirectoryPath;
    protected final boolean softDeletesPossible;
    protected final String softDeletesField;

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
    public Flux<RfsLuceneDocument> readDocuments() {
        return readDocuments(0, 0);
    }

    public Flux<RfsLuceneDocument> readDocuments(int startSegmentIndex, int startDoc) {
        return Flux.using(
            () -> wrapReader(getReader(), softDeletesPossible, softDeletesField),
            reader -> readDocsByLeavesFromStartingPosition(reader, startSegmentIndex, startDoc),
            reader -> {
                try {
                    reader.close();
                } catch (IOException e) {
                    log.atError().setMessage("Failed to close DirectoryReader").setCause(e).log();
                    throw Lombok.sneakyThrow(e);
                }
        });
    }

    /**
     * We need to ensure a stable ordering of segments so we can start reading from a specific segment and document id.
     * To do this, we sort the segments by their name.
     */
    class SegmentNameSorter implements Comparator<LeafReader> {
        @Override
        public int compare(LeafReader leafReader1, LeafReader leafReader2) {
            // If both LeafReaders are SegmentReaders, sort as normal
            if (leafReader1 instanceof SegmentReader && leafReader2 instanceof SegmentReader) {
                SegmentCommitInfo segmentInfo1 = ((SegmentReader) leafReader1).getSegmentInfo();
                SegmentCommitInfo segmentInfo2 = ((SegmentReader) leafReader2).getSegmentInfo();

                String segmentName1 = segmentInfo1.info.name;
                String segmentName2 = segmentInfo2.info.name;

                return segmentName1.compareTo(segmentName2);
            }
            // Otherwise, shift the SegmentReaders to the front
            else if (leafReader1 instanceof SegmentReader && !(leafReader2 instanceof SegmentReader)) {
                log.info("Found non-SegmentReader of type {} in the DirectoryReader", leafReader2.getClass().getName());
                return -1;
            } else if (!(leafReader1 instanceof SegmentReader) && leafReader2 instanceof SegmentReader) {
                log.info("Found non-SegmentReader of type {} in the DirectoryReader", leafReader1.getClass().getName());
                return 1;
            } else {
                log.info("Found non-SegmentReader of type {} in the DirectoryReader", leafReader1.getClass().getName());
                log.info("Found non-SegmentReader of type {} in the DirectoryReader", leafReader2.getClass().getName());
                return 0;
            }
        }
    }

    protected DirectoryReader getReader() throws IOException {// Get the list of commits and pick the latest one
        try (FSDirectory directory = FSDirectory.open(indexDirectoryPath)) {
            List  <IndexCommit> commits = DirectoryReader.listCommits(directory);
            IndexCommit latestCommit = commits.get(commits.size() - 1);

            return DirectoryReader.open(
                latestCommit,
                6, // Minimum supported major version - Elastic 5/Lucene 6
                new SegmentNameSorter()
            );
        }
    }

    /* Start reading docs from a specific segment and document id.
    If the startSegmentIndex is 0, it will start from the first segment.
    If the startDocId is 0, it will start from the first document in the segment.
     */
    Publisher<RfsLuceneDocument> readDocsByLeavesFromStartingPosition(DirectoryReader reader, int startSegmentIndex, int startDocId) {
        var maxDocumentsToReadAtOnce = 100; // Arbitrary value
        log.atInfo().setMessage("{} documents in {} leaves found in the current Lucene index")
            .addArgument(reader::maxDoc)
            .addArgument(() -> reader.leaves().size())
            .log();

        // Create shared scheduler for i/o bound document reading
        var sharedSegmentReaderScheduler = Schedulers.newBoundedElastic(maxDocumentsToReadAtOnce, Integer.MAX_VALUE, "sharedSegmentReader");

        return Flux.fromIterable(reader.leaves())
            .skip(startSegmentIndex)
            .concatMapDelayError(c -> readDocsFromSegment(c,
                // Only use startDocId for the first segment we process
                    c.ord == startSegmentIndex ? startDocId : 0,
                    sharedSegmentReaderScheduler,
                    maxDocumentsToReadAtOnce)
            )
            .subscribeOn(sharedSegmentReaderScheduler) // Scheduler to read documents on
            .doOnTerminate(sharedSegmentReaderScheduler::dispose);
    }

    Flux<RfsLuceneDocument> readDocsFromSegment(LeafReaderContext leafReaderContext, int startDocId, Scheduler scheduler,
                                                int concurrency) {
        var segmentReader = leafReaderContext.reader();
        var liveDocs = segmentReader.getLiveDocs();

        int segmentIndex = leafReaderContext.ord;

        return Flux.range(startDocId, segmentReader.maxDoc() - startDocId)
                .flatMapSequentialDelayError(docIdx -> Mono.defer(() -> {
                    try {
                        if (liveDocs == null || liveDocs.get(docIdx)) {
                            // Get document, returns null to skip malformed docs
                            RfsLuceneDocument document = getDocument(segmentReader, segmentIndex, docIdx, true);
                            return Mono.justOrEmpty(document); // Emit only non-null documents
                        } else {
                            return Mono.empty(); // Skip non-live documents
                        }
                    } catch (Exception e) {
                        // Handle individual document read failures gracefully
                        return Mono.error(new RuntimeException("Error reading document at index: " + docIdx, e));
                    }
                }).subscribeOn(scheduler),
                        concurrency, 1)
                .subscribeOn(Schedulers.boundedElastic());
    }
    protected DirectoryReader wrapReader(DirectoryReader reader, boolean softDeletesEnabled, String softDeletesField) throws IOException {
        if (softDeletesEnabled) {
            return new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
        }
        return reader;
    }

    protected RfsLuceneDocument getDocument(IndexReader reader, int luceneSegIndex, int luceneDocId, boolean isLive) {
        Document document;
        try {
            document = reader.document(luceneDocId);
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to read document at Lucene index location {}")
                .addArgument(luceneDocId).log();
            return null;
        }

        String openSearchDocId = null;
        String type = null;
        BytesRef sourceBytes = null;
        String routing = null;

        try {
            for (var field : document.getFields()) {
                String fieldName = field.name();
                switch (fieldName) {
                    case "_id": {
                        // ES 6+
                        var idBytes = field.binaryValue();
                        openSearchDocId = Uid.decodeId(idBytes.bytes);
                        break;
                    }
                    case "_uid": {
                        // ES <= 6
                        var combinedTypeId = field.stringValue().split("#", 2);
                        type = combinedTypeId[0];
                        openSearchDocId = combinedTypeId[1];
                        break;
                    }
                    case "_source": {
                        // All versions (?)
                        sourceBytes = field.binaryValue();
                        break;
                    }
                    case "_routing": {
                        routing = field.stringValue();
                        break;
                    }
                    default:
                        break;
                }
            }
            if (openSearchDocId == null) {
                log.atError().setMessage("Document with index {} does not have an id. Skipping")
                    .addArgument(luceneDocId).log();
                return null;  // Skip documents with missing id
            }

            if (sourceBytes == null || sourceBytes.bytes.length == 0) {
                log.atWarn().setMessage("Document {} doesn't have the _source field enabled")
                    .addArgument(openSearchDocId).log();
                return null;  // Skip these
            }

            log.atDebug().setMessage("Reading document {}").addArgument(openSearchDocId).log();
        } catch (RuntimeException e) {
            StringBuilder errorMessage = new StringBuilder();
            errorMessage.append("Unable to parse Document id from Document.  The Document's Fields: ");
            document.getFields().forEach(f -> errorMessage.append(f.name()).append(", "));
            log.atError().setCause(e).setMessage("{}").addArgument(errorMessage).log();
            return null; // Skip documents with invalid id
        }

        if (!isLive) {
            log.atDebug().setMessage("Document {} is not live").addArgument(openSearchDocId).log();
            return null; // Skip these
        }

        log.atDebug().setMessage("Document {} read successfully").addArgument(openSearchDocId).log();
        return new RfsLuceneDocument(luceneSegIndex, luceneDocId, openSearchDocId, type, sourceBytes.utf8ToString(), routing);
    }
}
