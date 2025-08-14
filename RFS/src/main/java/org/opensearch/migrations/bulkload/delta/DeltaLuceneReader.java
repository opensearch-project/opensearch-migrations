package org.opensearch.migrations.bulkload.delta;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneDocument;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReaderContext;
import org.opensearch.migrations.bulkload.lucene.LuceneLiveDocs;
import org.opensearch.migrations.bulkload.lucene.ReaderAndBase;
import org.opensearch.migrations.bulkload.lucene.SegmentNameSorter;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class DeltaLuceneReader {

    private DeltaLuceneReader() {}

    /* Start reading docs from a specific segment and document id.
       If the startSegmentIndex is 0, it will start from the first segment.
       If the startDocId is 0, it will start from the first document in the segment.
     */
    public static Publisher<RfsLuceneDocument> readDocsByLeavesFromStartingPosition(LuceneDirectoryReader baseReader, LuceneDirectoryReader currentReader, int startDocId) {
       // Start with brute force solution
        var baseSegmentToLeafReader = new TreeMap<String, LuceneLeafReader>();
        baseReader.leaves().forEach(
            leaf ->
                baseSegmentToLeafReader.put(leaf.reader().getSegmentName(), leaf.reader())
        );

        var currentSegmentToLeafReader = new TreeMap<String, LuceneLeafReader>();
        currentReader.leaves().forEach(
            leaf ->
                currentSegmentToLeafReader.put(leaf.reader().getSegmentName(), leaf.reader())
        );

        // --- compute key sets
        var baseKeys    = new TreeSet<>(baseSegmentToLeafReader.keySet());
        var currentKeys = new TreeSet<>(currentSegmentToLeafReader.keySet());

        var onlyInBaseKeys    = new TreeSet<>(baseKeys);
        onlyInBaseKeys.removeAll(currentKeys);

        var onlyInCurrentKeys = new TreeSet<>(currentKeys);
        onlyInCurrentKeys.removeAll(baseKeys);

        var inBothKeys        = new TreeSet<>(baseKeys);
        inBothKeys.retainAll(currentKeys);

        var maxDocumentsToReadAtOnce = 100; // Arbitrary value
        var sharedSegmentReaderScheduler = Schedulers.newBoundedElastic(maxDocumentsToReadAtOnce, Integer.MAX_VALUE, "sharedSegmentReader");


        // Start with just new docs, will add remove later
        List<SegmentReaderAndLiveDoc> readerAndBases = new ArrayList<>();
        // TODO: For delta backfill we need to move to using `long` everywhere since we may have more than 2^31 docs to work through
        // when adding both docs to remove and docs to add
        int offset = 0;
        for (var key : onlyInCurrentKeys) {
            var reader = currentSegmentToLeafReader.get(key);
            readerAndBases.add(new SegmentReaderAndLiveDoc(reader, reader.getLiveDocs(), offset));
            offset += reader.maxDoc();
        }

        for (var readerKey : inBothKeys) {
            var baseSegmentReader = baseSegmentToLeafReader.get(readerKey);
            var currentSegmentReader = currentSegmentToLeafReader.get(readerKey);

            var baseLiveDocs = baseSegmentReader.getLiveDocs();
            var currentLiveDocs = currentSegmentReader.getLiveDocs();

            // Only add segment if baseSnapshot was missing a doc that the current one has
            // If baseLiveDocs == null, then base has a superset of docs than current
            if (baseLiveDocs != null) {
                LuceneLiveDocs liveDocs = (currentLiveDocs != null) ? currentLiveDocs
                            .andNot(baseLiveDocs)
                    : baseLiveDocs.not();

                var segmentReaderToCreate = new SegmentReaderAndLiveDoc(
                    currentSegmentReader,
                    liveDocs,
                    offset
                );
                offset += currentSegmentReader.maxDoc();
                readerAndBases.add(segmentReaderToCreate);

            }
        }

        return Flux.fromIterable(readerAndBases)
            .flatMapSequential( c ->
                readDocsFromSegment(c,
                    startDocId,
                    sharedSegmentReaderScheduler,
                    maxDocumentsToReadAtOnce,
                    Path.of(c.reader.getSegmentName()))
            ).subscribeOn(sharedSegmentReaderScheduler) // Scheduler to read documents on
            .publishOn(Schedulers.boundedElastic()) // Switch scheduler for subsequent chain
            .doFinally(s -> sharedSegmentReaderScheduler.dispose());
    }

    record SegmentReaderAndLiveDoc(
        LuceneLeafReader reader,
        LuceneLiveDocs liveDocOverride,
        int baseDocIdx
    ){};

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

    static Flux<RfsLuceneDocument> readDocsFromSegment(SegmentReaderAndLiveDoc readerAndBase, int docStartingId, Scheduler scheduler,
                                                int concurrency, Path indexDirectoryPath) {
        var segmentReader = readerAndBase.reader;
        var liveDocs = readerAndBase.liveDocOverride;

        int segmentDocBase = readerAndBase.baseDocIdx;

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
                            RfsLuceneDocument document = DeltaLuceneReader.getDocument(segmentReader, docIdx.intValue(), true, segmentDocBase, getSegmentReaderDebugInfo, indexDirectoryPath);
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

    static RfsLuceneDocument getDocument(LuceneLeafReader reader, int luceneDocId, boolean isLive, int segmentDocBase, final Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath) {
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
                log.atWarn().setMessage("Skipping document with index {} from segment {} from source {}, it does not have an referenceable id.")
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
        return new RfsLuceneDocument(segmentDocBase + luceneDocId, openSearchDocId, type, sourceBytes, routing);
    }
}
