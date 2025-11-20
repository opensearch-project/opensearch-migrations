package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.RfsDocumentOperation;
import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class LuceneReader {

    private LuceneReader() {}

    /* Start reading docs from a specific segment and document id.
       If the startSegmentIndex is 0, it will start from the first segment.
       If the startDocId is 0, it will start from the first document in the segment.
     */
    static Publisher<RfsLuceneDocument> readDocsByLeavesFromStartingPosition(LuceneDirectoryReader reader, int startDocId) {
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
                    maxDocumentsToReadAtOnce,
                    reader.getIndexDirectoryPath(),
                    RfsDocumentOperation.INDEX)
            )
            .subscribeOn(sharedSegmentReaderScheduler) // Scheduler to read documents on
            .publishOn(Schedulers.boundedElastic()) // Switch scheduler for subsequent chain
            .doFinally(s -> sharedSegmentReaderScheduler.dispose());
    }

    /**
     * Retrieves, sorts, and processes document segments, returning a {@link Flux} of segments
     * starting from the first segment where the cumulative document base is less than or equal
     * to the specified start document ID.
     *
     * @param originalLeaves A list of {@link LuceneLeafReaderContext} representing the document segments.
     * @param startDocId The document ID from which to begin processing.
     * @return A {@link Flux} emitting the sorted segments starting from the identified segment,
     *         wrapped in {@link ReaderAndBase}.
     */
    static Flux<ReaderAndBase> getSegmentsFromStartingSegment(List<? extends LuceneLeafReaderContext> originalLeaves, int startDocId) {
        if (originalLeaves.isEmpty()) {
            return Flux.empty();
        }

        // Step 1: Sort the segments by name
        var sortedLeaves = originalLeaves.stream()
            .map(LuceneLeafReaderContext::reader)
            .sorted(SegmentNameSorter.INSTANCE)
            .toList();

        // Step 2: Build the list of ReaderAndBase objects with cumulative doc base
        var sortedReaderAndBase = new ArrayList<ReaderAndBase>();
        int cumulativeDocBase = 0;
        for (var segment : sortedLeaves) {
            sortedReaderAndBase.add(new ReaderAndBase(segment, cumulativeDocBase, segment.getLiveDocs()));
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
            index = Math.max(insertionPoint - 1, 0);
        }

        // Step 5: Return the sublist starting from the first valid segment
        return Flux.fromIterable(sortedReaderAndBase.subList(index, sortedReaderAndBase.size()));
    }

    public static Flux<RfsLuceneDocument> readDocsFromSegment(ReaderAndBase readerAndBase, int docStartingId, Scheduler scheduler,
                                                int concurrency, Path indexDirectoryPath, RfsDocumentOperation operation) {
        var segmentReader = readerAndBase.getReader();
        var liveDocs = readerAndBase.getLiveDocs();

        int segmentDocBase = readerAndBase.getDocBaseInParent();

        // Start at
        int startDocIdInSegment = (docStartingId <= segmentDocBase) ? 0 : docStartingId - segmentDocBase;

        // For any errors, we want to log the segment reader debug info so we can see which segment is causing the issue.
        // This allows us to pass the supplier to getDocument without having to recompute the debug info
        // every time if requested multiple times.
        var segmentReaderDebugInfoCache = new AtomicReference<String>();
        final Supplier<String> getSegmentReaderDebugInfo = () -> segmentReaderDebugInfoCache.updateAndGet(s ->
            s == null ? segmentReader.toString() : s
        );

        log.atDebug().setMessage("For segment: {}, migrating from doc: {}. Will process {} docs in segment.")
                .addArgument(readerAndBase.getReader())
                .addArgument(startDocIdInSegment)
                .addArgument(() -> segmentReader.maxDoc() - startDocIdInSegment)
                .log();

        var idxStream = (liveDocs != null) ? liveDocs.stream().filter(idx -> idx >= startDocIdInSegment) :
            IntStream.range(startDocIdInSegment, segmentReader.maxDoc());
        return Flux.fromStream(idxStream.boxed())
            .flatMapSequentialDelayError(docIdx -> Mono.defer(() -> {
                    try {
                        // Get document, returns null to skip malformed docs
                        RfsLuceneDocument document = LuceneReader.getDocument(segmentReader, docIdx, true, segmentDocBase, getSegmentReaderDebugInfo, indexDirectoryPath, operation);
                        return Mono.justOrEmpty(document); // Emit only non-null documents
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

    public static RfsLuceneDocument getDocument(LuceneLeafReader reader, int luceneDocId, boolean isLive, int segmentDocBase, final Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, RfsDocumentOperation operation) {
        LuceneDocument document;
        try {
            document = reader.document(luceneDocId);
        } catch (IOException e) {
            log.atError().setCause(e).setMessage("Failed to read document at Lucene index location {}")
                .addArgument(luceneDocId).log();
            return null;
        }

        String openSearchDocId = null;
        String type = null;
        String sourceBytes = null;
        String routing = null;

        try {
            for (var field : document.getFields()) {
                String fieldName = field.name();
                switch (fieldName) {
                    case "_id": {
                        // Lucene >= 7 (ES 6+ created segments)
                        openSearchDocId = field.asUid();
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
                        sourceBytes = field.utf8ToStringValue();
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
                log.atDebug().setMessage("Skipping document with index {} from segment {} from source {}, it does not have an referenceable id.")
                    .addArgument(luceneDocId)
                    .addArgument(getSegmentReaderDebugInfo)
                    .addArgument(indexDirectoryPath)
                    .log();
                return null;  // Skip documents with missing id
            }

            if (sourceBytes == null || sourceBytes.isEmpty()) {
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
        return new RfsLuceneDocument(segmentDocBase + luceneDocId, openSearchDocId, type, sourceBytes, routing, operation);
    }
}
