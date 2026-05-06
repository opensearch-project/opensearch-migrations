package org.opensearch.migrations.bulkload.lucene;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Slf4j
public class LuceneReader {

    /** Dedicated scheduler for Lucene I/O — more threads than boundedElastic on small pods. */
    private static final Scheduler LUCENE_IO_SCHEDULER = Schedulers.newBoundedElastic(
        100, Integer.MAX_VALUE, "lucene-io", 60, true
    );

    /** Concurrency for flatMapSequential within a segment — matches the scheduler thread count. */
    private static final int SEGMENT_READ_CONCURRENCY = 100;

    private LuceneReader() {}

    /* Start reading docs from a specific segment and document id.
       If the startSegmentIndex is 0, it will start from the first segment.
       If the startDocId is 0, it will start from the first document in the segment.
       Segments are read sequentially; within each segment, docs are read with bounded
       concurrency (matching the Lucene I/O scheduler thread count) via flatMapSequential
       to keep the source feeding batches fast enough.
     */
    public static Flux<LuceneDocumentChange> readDocsByLeavesFromStartingPosition(LuceneDirectoryReader reader, int startDocId, FieldMappingContext mappingContext) {
        return readDocsByLeavesFromStartingPosition(reader, startDocId, mappingContext, false);
    }

    public static Flux<LuceneDocumentChange> readDocsByLeavesFromStartingPosition(LuceneDirectoryReader reader, int startDocId, FieldMappingContext mappingContext, boolean useRecoverySource) {
        log.atInfo().setMessage("{} documents in {} leaves found in the current Lucene index")
            .addArgument(reader::maxDoc)
            .addArgument(() -> reader.leaves().size())
            .log();

        return getSegmentsFromStartingSegment(reader.leaves(), startDocId)
            .concatMapDelayError(c -> readDocsFromSegment(c,
                    startDocId,
                    reader.getIndexDirectoryPath(),
                    DocumentChangeType.INDEX,
                    mappingContext,
                    useRecoverySource)
            )
            .subscribeOn(LUCENE_IO_SCHEDULER);
    }

    /** Backwards-compatible overload without mapping context */
    public static Flux<LuceneDocumentChange> readDocsByLeavesFromStartingPosition(LuceneDirectoryReader reader, int startDocId) {
        return readDocsByLeavesFromStartingPosition(reader, startDocId, null);
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

    public static Flux<LuceneDocumentChange> readDocsFromSegment(ReaderAndBase readerAndBase, int docStartingId,
                                                Path indexDirectoryPath, DocumentChangeType operation,
                                                FieldMappingContext mappingContext) {
        return readDocsFromSegment(readerAndBase, docStartingId, indexDirectoryPath, operation, mappingContext, false);
    }

    public static Flux<LuceneDocumentChange> readDocsFromSegment(ReaderAndBase readerAndBase, int docStartingId,
                                                Path indexDirectoryPath, DocumentChangeType operation,
                                                FieldMappingContext mappingContext, boolean useRecoverySource) {
        var segmentReader = readerAndBase.getReader();
        var liveDocs = readerAndBase.getLiveDocs();

        int segmentDocBase = readerAndBase.getDocBaseInParent();

        // Start at
        int startDocIdInSegment = (docStartingId <= segmentDocBase) ? 0 : docStartingId - segmentDocBase;

        // Per-segment term position cache. Built lazily on first need. Backed by an on-disk
        // spill directory so the in-heap footprint stays bounded even for large segments
        // (a 3GB shard's analyzed-text TreeMap can otherwise OOM at 64GB JVM). The spill
        // directory is created lazily on first field build and deleted by the Flux's
        // doFinally hook below on both success and error paths.
        final Path spillRoot = SourcelessSpillConfig.newSegmentSpillRoot();
        final SegmentTermIndex termIndex = new SegmentTermIndex(
                spillRoot, SourcelessSpillConfig.sortBufferBytes());

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
            .flatMapSequential(docIdx -> Mono.defer(() -> {
                    try {
                        LuceneDocumentChange document = LuceneReader.getDocument(segmentReader, docIdx, true, segmentDocBase, getSegmentReaderDebugInfo, indexDirectoryPath, operation, mappingContext, termIndex, useRecoverySource);
                        return Mono.justOrEmpty(document);
                    } catch (Exception e) {
                        log.atError().setMessage("Error reading document from reader {} with index: {}")
                            .addArgument(getSegmentReaderDebugInfo)
                            .addArgument(docIdx)
                            .setCause(e)
                            .log();
                        return Mono.error(new RuntimeException("Error reading document from reader with index " + docIdx
                            + " from segment " + getSegmentReaderDebugInfo.get(), e));
                    }
                }).subscribeOn(LUCENE_IO_SCHEDULER), SEGMENT_READ_CONCURRENCY, 1)
            .doFinally(sig -> termIndex.close());
    }

    /** Backwards-compatible overload without mapping context */
    public static Flux<LuceneDocumentChange> readDocsFromSegment(ReaderAndBase readerAndBase, int docStartingId,
                                                Path indexDirectoryPath, DocumentChangeType operation) {
        return readDocsFromSegment(readerAndBase, docStartingId, indexDirectoryPath, operation, null);
    }

    public static LuceneDocumentChange getDocument(LuceneLeafReader reader, int luceneDocId, boolean isLive, int segmentDocBase,
            final Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, DocumentChangeType operation,
            FieldMappingContext mappingContext) {
        return getDocument(reader, luceneDocId, isLive, segmentDocBase, getSegmentReaderDebugInfo,
            indexDirectoryPath, operation, mappingContext, null, false);
    }

    public static LuceneDocumentChange getDocument(LuceneLeafReader reader, int luceneDocId, boolean isLive, int segmentDocBase,
            final Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, DocumentChangeType operation,
            FieldMappingContext mappingContext, SegmentTermIndex termIndex) {
        return getDocument(reader, luceneDocId, isLive, segmentDocBase, getSegmentReaderDebugInfo,
            indexDirectoryPath, operation, mappingContext, termIndex, false);
    }

    public static LuceneDocumentChange getDocument(LuceneLeafReader reader, int luceneDocId, boolean isLive, int segmentDocBase,
            final Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, DocumentChangeType operation,
            FieldMappingContext mappingContext, SegmentTermIndex termIndex, boolean useRecoverySource) {
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
        byte[] sourceBytes = null;
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
                        // All versions — keep as raw bytes to avoid String allocation
                        sourceBytes = field.utf8Value();
                        break;
                    }
                    case "_recovery_source": {
                        // ES 7+ / OpenSearch soft-deletes field. When opted in, treat as _source
                        // for documents where _source is absent (disabled or filtered).
                        if (useRecoverySource && sourceBytes == null) {
                            sourceBytes = field.utf8Value();
                        }
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

            sourceBytes = resolveSourceBytes(sourceBytes, reader, luceneDocId, document, mappingContext,
                openSearchDocId, getSegmentReaderDebugInfo, indexDirectoryPath, termIndex);
            if (sourceBytes == null) {
                return null;
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
        return new LuceneDocumentChange(segmentDocBase + luceneDocId, openSearchDocId, type, sourceBytes, routing, operation);
    }

    /**
     * Resolves the _source bytes for a document.
     * <p>
     * Priority chain:
     * <ol>
     *   <li>If _source is missing but _recovery_source is present, use the full recovery source directly.</li>
     *   <li>If both _source (partial — filtered by includes/excludes) and _recovery_source (full original)
     *       are present, merge recovery on top of the partial source so filtered fields are restored
     *       verbatim from the original JSON.</li>
     *   <li>Otherwise fall back to doc_values/stored/points/terms reconstruction (Solr / pre-7.x path).</li>
     * </ol>
     * @return resolved source bytes, or null if the document should be skipped
     */
    private static byte[] resolveSourceBytes(byte[] sourceBytes,
            LuceneLeafReader reader, int luceneDocId,
            LuceneDocument document, FieldMappingContext mappingContext, String openSearchDocId,
            Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, SegmentTermIndex termIndex) {
        boolean hasSource = sourceBytes != null && sourceBytes.length > 0;

        if (!hasSource) {
            return reconstructSourceBytes(reader, luceneDocId, document, mappingContext,
                openSearchDocId, getSegmentReaderDebugInfo, indexDirectoryPath, termIndex);
        }
        if (mappingContext != null) {
            String merged = SourceReconstructor.mergeWithDocValues(
                new String(sourceBytes, java.nio.charset.StandardCharsets.UTF_8), reader, luceneDocId, document, mappingContext, termIndex);
            return merged.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return sourceBytes;
    }

    private static byte[] reconstructSourceBytes(LuceneLeafReader reader, int luceneDocId,
            LuceneDocument document, FieldMappingContext mappingContext, String openSearchDocId,
            Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, SegmentTermIndex termIndex) {
        if (mappingContext == null) {
            log.atWarn().setMessage("Skipping document with index {} from segment {} from source {}, it does not have the _source field enabled.")
                .addArgument(luceneDocId)
                .addArgument(getSegmentReaderDebugInfo)
                .addArgument(indexDirectoryPath)
                .log();
            return null;
        }
        log.atDebug().setMessage("Document {} has no _source, attempting reconstruction from doc_values and stored fields")
            .addArgument(openSearchDocId).log();
        String reconstructed = SourceReconstructor.reconstructSource(reader, luceneDocId, document, mappingContext, termIndex);
        if (reconstructed == null || reconstructed.isEmpty()) {
            log.atWarn().setMessage("Skipping document with index {} from segment {} from source {}, _source is missing and reconstruction failed.")
                .addArgument(luceneDocId)
                .addArgument(getSegmentReaderDebugInfo)
                .addArgument(indexDirectoryPath)
                .log();
            return null;
        }
        log.atDebug().setMessage("Successfully reconstructed _source for document {} from doc_values")
            .addArgument(openSearchDocId).log();
        return reconstructed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Backwards-compatible overload without mapping context */
    public static LuceneDocumentChange getDocument(LuceneLeafReader reader, int luceneDocId, boolean isLive, int segmentDocBase, 
            final Supplier<String> getSegmentReaderDebugInfo, Path indexDirectoryPath, DocumentChangeType operation) {
        return getDocument(reader, luceneDocId, isLive, segmentDocBase, getSegmentReaderDebugInfo, indexDirectoryPath, operation, null);
    }
}
