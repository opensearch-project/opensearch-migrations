package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.common.Uid;

import lombok.Lombok;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import shadow.lucene7.org.apache.lucene.document.Document;
import shadow.lucene7.org.apache.lucene.index.DirectoryReader;
import shadow.lucene7.org.apache.lucene.index.IndexReader;
import shadow.lucene7.org.apache.lucene.index.LeafReader;
import shadow.lucene7.org.apache.lucene.index.LeafReaderContext;
import shadow.lucene7.org.apache.lucene.index.SegmentCommitInfo;
import shadow.lucene7.org.apache.lucene.index.SegmentReader;
import shadow.lucene7.org.apache.lucene.index.SoftDeletesDirectoryReaderWrapper;
import shadow.lucene7.org.apache.lucene.store.FSDirectory;
import shadow.lucene7.org.apache.lucene.util.BytesRef;

@RequiredArgsConstructor
@Slf4j
public class LuceneDocumentsReader7 implements LuceneDocumentsReader {

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
    public Flux<RfsLuceneDocument> readDocuments(int startDocIdx) {
        return Flux.using(
            () -> wrapReader(getReader(), softDeletesPossible, softDeletesField),
            reader -> readDocsByLeavesFromStartingPosition(reader, startDocIdx),
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
     * To do this, we sort the segments by their ID or name.
     */
    static class SegmentNameSorter implements Comparator<LeafReader> {
        static final SegmentNameSorter INSTANCE = new SegmentNameSorter();
        @Override
        public int compare(LeafReader leafReader1, LeafReader leafReader2) {
            var compareResponse = compareIfSegmentReader(leafReader1, leafReader2);
            if (compareResponse == 0) {
                Function<LeafReader, String> getLeafReaderDebugInfo = leafReader -> {
                    var leafDetails = new StringBuilder();
                    leafDetails.append("Class: ").append(leafReader.getClass().getName()).append("\n");
                    leafDetails.append("Context: ").append(leafReader.getContext()).append("\n");
                    if (leafReader instanceof SegmentReader) {
                        SegmentCommitInfo segmentInfo = ((SegmentReader) leafReader).getSegmentInfo();
                        leafDetails.append("SegmentInfo: ").append(segmentInfo).append("\n");
                    }
                    return leafDetails.toString();
                };
                log.atWarn().setMessage("Unexpected equality during leafReader sorting, expected sort to yield no equality " +
                        "to ensure consistent segment ordering. This may cause missing documents if both segments" +
                        "contains docs. LeafReader1DebugInfo: {} \nLeafReader2DebugInfo: {}")
                        .addArgument(getLeafReaderDebugInfo.apply(leafReader1))
                        .addArgument(getLeafReaderDebugInfo.apply(leafReader2))
                        .log();
                assert false: "Expected unique segmentName sorting for stable sorting.";
            }
            return compareResponse;
        }

        private int compareIfSegmentReader(LeafReader leafReader1, LeafReader leafReader2) {
            // If both LeafReaders are SegmentReaders, sort on segment info name.
            // Name is the "Unique segment name in the directory" which is always present on a SegmentInfo
            if (leafReader1 instanceof SegmentReader && leafReader2 instanceof SegmentReader) {
                var segmentName1 = ((SegmentReader) leafReader1).getSegmentName();
                var segmentName2 = ((SegmentReader) leafReader2).getSegmentName();
                return segmentName1.compareTo(segmentName2);
            }
            // Otherwise, keep initial sort
            return 0;
        }
    }

    protected DirectoryReader getReader() throws IOException {
        try (var directory = FSDirectory.open(indexDirectoryPath)) {
            var commits = DirectoryReader.listCommits(directory);
            var latestCommit = commits.get(commits.size() - 1);

            return DirectoryReader.open(latestCommit);
        }
    }

    @Value
    public static class ReaderAndBase {
        LeafReader reader;
        int docBaseInParent;
    }

    /**
     * Retrieves, sorts, and processes document segments, returning a {@link Flux} of segments
     * starting from the first segment where the cumulative document base is less than or equal
     * to the specified start document ID.
     *
     * @param originalLeaves A list of {@link LeafReaderContext} representing the document segments.
     * @param startDocId The document ID from which to begin processing.
     * @return A {@link Flux} emitting the sorted segments starting from the identified segment,
     *         wrapped in {@link ReaderAndBase}.
     */
    public static Flux<ReaderAndBase> getSegmentsFromStartingSegment(List<LeafReaderContext> originalLeaves, int startDocId) {
        if (originalLeaves.isEmpty()) {
            return Flux.empty();
        }

        // Step 1: Sort the segments by name
        var sortedLeaves = originalLeaves.stream()
            .map(LeafReaderContext::reader)
            .sorted(SegmentNameSorter.INSTANCE)
            .collect(Collectors.toList());

        // Step 2: Build the list of ReaderAndBase objects with cumulative doc base
        var sortedReaderAndBase = new ArrayList<ReaderAndBase>();
        int cumulativeDocBase = 0;
        for (var segment : sortedLeaves) {
            sortedReaderAndBase.add(new ReaderAndBase(segment, cumulativeDocBase));
            cumulativeDocBase += segment.maxDoc();
        }

        // Step 3: Use binary search to find the insertion point of startDocId in list of docBaseInParent
        var segmentStartingDocIds = sortedReaderAndBase.stream().map(ReaderAndBase::getDocBaseInParent).toArray();
        int index = Arrays.binarySearch(segmentStartingDocIds, startDocId);

        // Step 4: If an exact match is found (binarySearch returns non-negative value)
        //         then use this index to start on.
        //         If an exact match is not found, binarySearch returns `-(insertionPoint) - 1`
        //         where `insertion_point` is the first position where docBaseInParent > startDocId.
        if (index < 0) {
            var insertionPoint = -(index + 1);
            // index = Last segment index with docBaseInParent < startDocId
            index = insertionPoint - 1;
            assert index >= 0;
        }

        // Step 5: Return the sublist starting from the first valid segment
        return Flux.fromIterable(sortedReaderAndBase.subList(index, sortedReaderAndBase.size()));
    }


    /* Start reading docs from a specific segment and document id.
    If the startSegmentIndex is 0, it will start from the first segment.
    If the startDocId is 0, it will start from the first document in the segment.
     */
    Publisher<RfsLuceneDocument> readDocsByLeavesFromStartingPosition(DirectoryReader reader, int startDocId) {
        var maxDocumentsToReadAtOnce = 100; // Arbitrary value
        log.atInfo().setMessage("{} documents in {} leaves found in the current Lucene index")
            .addArgument(reader::maxDoc)
            .addArgument(() -> reader.leaves().size())
            .log();

        // Create shared scheduler for i/o bound document reading
        var sharedSegmentReaderScheduler = Schedulers.newBoundedElastic(maxDocumentsToReadAtOnce, Integer.MAX_VALUE, "sharedSegmentReader");
        return getSegmentsFromStartingSegment(reader.leaves(), startDocId)
            .concatMapDelayError(c -> readDocsFromSegment(c,
                    startDocId,
                    sharedSegmentReaderScheduler,
                    maxDocumentsToReadAtOnce)
            )
            .subscribeOn(sharedSegmentReaderScheduler) // Scheduler to read documents on
            .doFinally(s -> sharedSegmentReaderScheduler.dispose());
    }

    Flux<RfsLuceneDocument> readDocsFromSegment(ReaderAndBase readerAndBase, int docStartingId, Scheduler scheduler,
                                                int concurrency) {
        var segmentReader = readerAndBase.reader;
        var liveDocs = segmentReader.getLiveDocs();

        int segmentDocBase = readerAndBase.docBaseInParent;

        // Start at
        int startDocIdInSegment = Math.max(docStartingId - segmentDocBase, 0);
        int numDocsToProcessInSegment = segmentReader.maxDoc() - startDocIdInSegment;

        // For any errors, we want to log the segment reader debug info so we can see which segment is causing the issue.
        // This allows us to pass the supplier to getDocument without having to recompute the debug info
        // every time if requested multiple times.
        var segmentReaderDebugInfoCache = new AtomicReference<String>();
        final Supplier<String> getSegmentReaderDebugInfo = () -> segmentReaderDebugInfoCache.updateAndGet(s ->
            s == null ? segmentReader.toString() : s
        );

        log.atDebug().setMessage("For segment: {}, migrating from doc: {}. Will process {} docs in segment.")
                .addArgument(readerAndBase.reader)
                .addArgument(startDocIdInSegment)
                .addArgument(numDocsToProcessInSegment)
                .log();

        return Flux.range(startDocIdInSegment, numDocsToProcessInSegment)
                .flatMapSequentialDelayError(docIdx -> Mono.defer(() -> {
                    try {
                        if (liveDocs == null || liveDocs.get(docIdx)) {
                            // Get document, returns null to skip malformed docs
                            RfsLuceneDocument document = getDocument(segmentReader, docIdx, true, segmentDocBase, getSegmentReaderDebugInfo);
                            return Mono.justOrEmpty(document); // Emit only non-null documents
                        } else {
                            return Mono.empty(); // Skip non-live documents
                        }
                    } catch (Exception e) {
                        // Handle individual document read failures gracefully
                        log.atError().setMessage("Error reading document from reader {} with index: {}")
                            .addArgument(getSegmentReaderDebugInfo)
                            .addArgument(docIdx)
                            .setCause(e)
                            .log();
                        return Mono.error(new RuntimeException("Error reading document from reader with index " + docIdx
                            + " from segment " + getSegmentReaderDebugInfo.get(), e));
                    }
                }).subscribeOn(scheduler),
                        concurrency, 1)
                .subscribeOn(scheduler);
    }
    protected DirectoryReader wrapReader(DirectoryReader reader, boolean softDeletesEnabled, String softDeletesField) throws IOException {
        if (softDeletesEnabled) {
            return new SoftDeletesDirectoryReaderWrapper(reader, softDeletesField);
        }
        return reader;
    }

    protected RfsLuceneDocument getDocument(IndexReader reader, int luceneDocId, boolean isLive, int segmentDocBase, final Supplier<String> getSegmentReaderDebugInfo) {
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
                        // Lucene >= 7 (ES 6+ created segments)
                        var idBytes = field.binaryValue();
                        openSearchDocId = Uid.decodeId(idBytes.bytes);
                        break;
                    }
                    case "_uid": {
                        // Lucene <= 6 (ES <= 5 created segments)
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
                log.atWarn().setMessage("Skipping document with index {} from segment {} from source {}, it does not have an referenceable id.")
                    .addArgument(luceneDocId)
                    .addArgument(getSegmentReaderDebugInfo)
                    .addArgument(indexDirectoryPath)
                    .log();
                return null;  // Skip documents with missing id
            }

            if (sourceBytes == null || sourceBytes.bytes.length == 0) {
                log.atWarn().setMessage("Skipping document with index {} from segment {} from source {}, it does not have the _source field enabled.")
                    .addArgument(luceneDocId)
                    .addArgument(getSegmentReaderDebugInfo)
                    .addArgument(indexDirectoryPath)
                    .log();
                return null;  // Skip these
            }

            log.atDebug().setMessage("Reading document {}").addArgument(openSearchDocId).log();
        } catch (RuntimeException e) {
            StringBuilder errorMessage = new StringBuilder();
            errorMessage.append("Unable to parse Document id from Document with index ")
                .append(luceneDocId)
                .append(" from segment ")
                .append(getSegmentReaderDebugInfo.get())
                .append(".  The Document's Fields: ");
            document.getFields().forEach(f -> errorMessage.append(f.name()).append(", "));
            log.atError().setCause(e).setMessage("{}").addArgument(errorMessage).log();
            return null; // Skip documents with invalid id
        }

        if (!isLive) {
            log.atDebug().setMessage("Document {} is not live").addArgument(openSearchDocId).log();
            return null; // Skip these
        }

        log.atDebug().setMessage("Document {} read successfully").addArgument(openSearchDocId).log();
        return new RfsLuceneDocument(segmentDocBase + luceneDocId, openSearchDocId, type, sourceBytes.utf8ToString(), routing);
    }
}
