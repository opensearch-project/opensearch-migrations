package org.opensearch.migrations.bulkload.lucene;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Shared utility for streaming documents from sorted Lucene segments.
 * Used by both ES snapshot reading (LuceneReader) and Solr backup reading (SolrBackupSource).
 *
 * <p>Segments are sorted by {@link SegmentNameSorter#INSTANCE} for deterministic ordering,
 * and cumulative document bases are computed so that a global document offset can be used
 * to resume reading from an arbitrary position. Within each segment, live documents are
 * iterated (using the liveDocs bitset when available) and processed with bounded concurrency
 * via {@code flatMapSequential}.
 */
@Slf4j
public final class SegmentDocStream {

    private SegmentDocStream() {}

    /**
     * Stream documents from a list of leaf reader contexts, starting at a global document offset.
     *
     * <p>Segments are sorted by name for deterministic ordering. Documents within each segment
     * are read with bounded concurrency via flatMapSequential.
     *
     * @param leaves the leaf reader contexts from a DirectoryReader
     * @param startDocId global document ID to resume from (0 = start from beginning)
     * @param concurrency max concurrent document reads within a segment
     * @param scheduler the scheduler to use for I/O operations
     * @param documentReader function that takes (reader, docIdx, segmentDocBase) and returns
     *                       a Mono of the output type T (empty Mono to skip a document)
     * @param <T> the output element type
     * @return Flux of documents in segment order
     */
    public static <T> Flux<T> fromLeaves(
        List<? extends LuceneLeafReaderContext> leaves,
        int startDocId,
        int concurrency,
        Scheduler scheduler,
        DocumentReader<T> documentReader
    ) {
        if (leaves.isEmpty()) {
            return Flux.empty();
        }

        var readers = leaves.stream()
            .map(LuceneLeafReaderContext::reader)
            .sorted(SegmentNameSorter.INSTANCE)
            .toList();

        return streamFromSortedReaders(readers, startDocId, concurrency, scheduler, documentReader);
    }

    /**
     * Simpler overload that takes a list of LuceneLeafReader directly (used by Solr path
     * which already has readers extracted from contexts).
     *
     * @param readers the leaf readers, which will be sorted by segment name
     * @param startDocId global document ID to resume from (0 = start from beginning)
     * @param concurrency max concurrent document reads within a segment
     * @param scheduler the scheduler to use for I/O operations
     * @param documentReader function that takes (reader, docIdx, segmentDocBase) and returns
     *                       a Mono of the output type T (empty Mono to skip a document)
     * @param <T> the output element type
     * @return Flux of documents in segment order
     */
    public static <T> Flux<T> fromReaders(
        List<? extends LuceneLeafReader> readers,
        int startDocId,
        int concurrency,
        Scheduler scheduler,
        DocumentReader<T> documentReader
    ) {
        if (readers.isEmpty()) {
            return Flux.empty();
        }

        var sortedReaders = readers.stream()
            .sorted(SegmentNameSorter.INSTANCE)
            .toList();

        return streamFromSortedReaders(sortedReaders, startDocId, concurrency, scheduler, documentReader);
    }

    /**
     * Core implementation: given already-sorted readers, build cumulative doc bases,
     * binary-search to the starting segment, and stream documents sequentially across segments.
     */
    private static <T> Flux<T> streamFromSortedReaders(
        List<? extends LuceneLeafReader> sortedReaders,
        int startDocId,
        int concurrency,
        Scheduler scheduler,
        DocumentReader<T> documentReader
    ) {
        // Build ReaderAndBase list with cumulative document bases
        var sortedReaderAndBase = new ArrayList<ReaderAndBase>(sortedReaders.size());
        int cumulativeDocBase = 0;
        for (var reader : sortedReaders) {
            sortedReaderAndBase.add(new ReaderAndBase(reader, cumulativeDocBase, reader.getLiveDocs()));
            cumulativeDocBase += reader.maxDoc();
        }

        // Binary search to find the starting segment
        int startIndex = findStartingSegment(sortedReaderAndBase, startDocId);

        log.atDebug().setMessage("Streaming from segment index {} of {} (startDocId={})")
            .addArgument(startIndex)
            .addArgument(sortedReaderAndBase.size())
            .addArgument(startDocId)
            .log();

        // Stream segments sequentially; within each segment, read docs with bounded concurrency
        return Flux.fromIterable(sortedReaderAndBase.subList(startIndex, sortedReaderAndBase.size()))
            .concatMap(readerAndBase -> readSegment(readerAndBase, startDocId, concurrency, scheduler, documentReader));
    }

    /**
     * Uses binary search to find the first segment whose cumulative doc base is
     * less than or equal to the specified startDocId.
     *
     * <p>This mirrors the logic in {@code LuceneReader.getSegmentsFromStartingSegment()}.
     */
    private static int findStartingSegment(List<ReaderAndBase> sortedReaderAndBase, int startDocId) {
        if (startDocId <= 0) {
            return 0;
        }

        var segmentStartingDocIds = sortedReaderAndBase.stream()
            .map(ReaderAndBase::getDocBaseInParent)
            .toArray();
        int index = Arrays.binarySearch(segmentStartingDocIds, startDocId);

        // If an exact match is found (binarySearch returns non-negative), use that index.
        // If not found, binarySearch returns -(insertionPoint) - 1, where insertionPoint
        // is the first position where docBaseInParent > startDocId.
        // We want the last segment with docBaseInParent <= startDocId.
        if (index < 0) {
            int insertionPoint = -(index + 1);
            index = Math.max(insertionPoint - 1, 0);
        }

        return index;
    }

    /**
     * Read all live documents from a single segment with bounded concurrency.
     *
     * <p>If the segment has a liveDocs bitset, only live (non-deleted) document indices
     * are iterated. If liveDocs is null (no deletions), all document indices are iterated.
     * The startDocId is used to skip documents within the first segment when resuming.
     */
    private static <T> Flux<T> readSegment(
        ReaderAndBase readerAndBase,
        int startDocId,
        int concurrency,
        Scheduler scheduler,
        DocumentReader<T> documentReader
    ) {
        var segmentReader = readerAndBase.getReader();
        var liveDocs = readerAndBase.getLiveDocs();
        int segmentDocBase = readerAndBase.getDocBaseInParent();

        // Within this segment, skip docs that come before startDocId
        int startDocIdInSegment = (startDocId <= segmentDocBase) ? 0 : startDocId - segmentDocBase;

        log.atDebug().setMessage("Reading segment {}: docBase={}, startInSegment={}, maxDoc={}")
            .addArgument(segmentReader::getSegmentName)
            .addArgument(segmentDocBase)
            .addArgument(startDocIdInSegment)
            .addArgument(segmentReader::maxDoc)
            .log();

        // Build the stream of live document indices within this segment
        IntStream docIdxStream = (liveDocs != null)
            ? liveDocs.stream().filter(idx -> idx >= startDocIdInSegment)
            : IntStream.range(startDocIdInSegment, segmentReader.maxDoc());

        return Flux.fromStream(docIdxStream.boxed())
            .flatMapSequential(
                docIdx -> Mono.defer(() -> documentReader.read(segmentReader, docIdx, segmentDocBase))
                    .subscribeOn(scheduler),
                concurrency,
                1
            );
    }

    /**
     * Functional interface for reading a single document from a segment.
     *
     * @param <T> the output type produced for each document
     */
    @FunctionalInterface
    public interface DocumentReader<T> {
        /**
         * Read a document and produce a result.
         *
         * @param reader the leaf reader for the segment containing the document
         * @param docIdx the document index within the segment (segment-local)
         * @param segmentDocBase the cumulative doc base for this segment (for computing global doc IDs)
         * @return a Mono emitting the result, or empty Mono to skip the document
         */
        Mono<T> read(LuceneLeafReader reader, int docIdx, int segmentDocBase);
    }
}
