package org.opensearch.migrations.bulkload.delta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import org.opensearch.migrations.bulkload.common.DocumentChangeType;
import org.opensearch.migrations.bulkload.common.LuceneDocumentChange;
import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;
import org.opensearch.migrations.bulkload.lucene.ReaderAndBase;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * DeltaLuceneReader
 * <p>
 * Provides a delta-style backfill between a previous and current Lucene snapshot.
 * Emits both new documents and delete operations as {@link LuceneDocumentChange} via Reactor streams.
 *
 * <h3>Functionality</h3>
 * - Builds segment → reader maps from both snapshots.
 * - Detects new segments or new docs in shared segments.
 * - Streams additions for processing.
 * - Streams deletions as delete operations for processing.
 *
 * <h3>Limitations</h3>
 * - BitSet cloning can be memory-heavy (Upper bound in low hundreds of MBs based on 2^31 doc upper bound in segment)
 * - Using Segment based diff for delta calculation. In the future, we should consider doc based diffs.
 *      For deletes, we can dedupe any deletes where the same document id appears in the additions stream.
 *      For additions, we can dedupe where the delete stream contains a doc with the same id and same source.
 *      Since we have an upper bound on shard doc changes of 2^31 deletions and 2^31 additions, this dedupe must be
 *      performed on Disk-backed data stores. This is different from the LiveDocs BitSet which uses 1 bit per doc
 *      whereas a document's id can be up to 512 bytes.
 *
 * <h3>Complexity</h3>
 * Real-world performance assumes the number of segments is reasonably bounded (O(1)).
 * Although documents may scale up to 2^31, the number of segments typically maxes out
 * in the hundreds or thousands — beyond which cluster performance would degrade.
 *
 * <p>
 * Complexity is determined at the <b>segment level</b>:
 * <ul>
 *   <li><b>Wholly created or deleted segments:</b> O(1) time and O(1) space</li>
 *   <li><b>Partially modified segments (docs updated/deleted):</b>
 *       O(n) time and O(n) space, where n = number of documents in the segment</li>
 * </ul>
 *
 * <p>
 * In mixed workloads (updates/deletes + inserts), different segments may fall into
 * either category: some segments are replaced entirely (cheap), while others require
 * per-document processing (less-cheap).
 *
 * <p>
 * Lucene optimizes segments with no deletions by storing <code>liveDocs</code> as
 * <code>null</code>. This allows diffs for those segments to be computed in O(1) time
 * and space.
 *
 * <h4>Special Case: Append-Only Workloads</h4>
 * When no updates/deletes occur and segments are only removed through merges:
 * <ul>
 *   <li>Deleted docs: O(1) time, O(1) space</li>
 *   <li>Added docs: O(1) time, O(1) space</li>
 * </ul>
 */
@Slf4j
public class DeltaLuceneReader {

    private DeltaLuceneReader() {}

    /**
     * Container class for delta results containing both additions and deletions
     */
    public static class DeltaResult {
        public final Flux<LuceneDocumentChange> additions;
        public final Flux<LuceneDocumentChange> deletions;
        
        public DeltaResult(Flux<LuceneDocumentChange> additions, Flux<LuceneDocumentChange> deletions) {
            this.additions = additions;
            this.deletions = deletions;
        }
    }

    /**
     * Read delta documents including both additions and deletions.
     * Returns a DeltaResult containing separate streams for additions and deletions.
     */
    public static DeltaResult readDeltaDocsByLeavesFromStartingPosition(
        LuceneDirectoryReader previousReader, 
        LuceneDirectoryReader currentReader, 
        int startDocId,
        IRfsContexts.IDeltaStreamContext deltaContext
    ) {
        log.atInfo()
            .setMessage("Starting delta backfill from position {}")
            .addArgument(startDocId)
            .log();

        var previousSegmentToLeafReader = new TreeMap<String, LuceneLeafReader>();
        previousReader.leaves().forEach(
            leaf ->
                previousSegmentToLeafReader.put(leaf.reader().getSegmentName(), leaf.reader())
        );

        var currentSegmentToLeafReader = new TreeMap<String, LuceneLeafReader>();
        currentReader.leaves().forEach(
            leaf ->
                currentSegmentToLeafReader.put(leaf.reader().getSegmentName(), leaf.reader())
        );

        log.atInfo()
            .setMessage("Found {} segments in previous and {} segments in current")
            .addArgument(previousSegmentToLeafReader.size())
            .addArgument(currentSegmentToLeafReader.size())
            .log();

        // Record total segments seen
        long totalSegmentsSeen = (long) previousSegmentToLeafReader.size() + currentSegmentToLeafReader.size();
        deltaContext.recordSegmentsSeen(totalSegmentsSeen);

        log.atInfo()
            .setMessage("Calculating remove and add docs in snapshot")
            .log();

        // Docs to remove are additions between new and old
        // TODO: Consider updating offset to a int to start at 0 instead of Integer.MIN_VALUE
        var removes = getAdditionsBetweenSnapshot(currentSegmentToLeafReader, previousSegmentToLeafReader, Integer.MIN_VALUE);
        var additions = getAdditionsBetweenSnapshot(previousSegmentToLeafReader, currentSegmentToLeafReader, 0);

        // Calculate and record metrics
        var totalDocsToRemove = removes.stream()
            .mapToInt(s -> s.getLiveDocs() == null ? s.getReader().maxDoc() :
                    s.getLiveDocs().cardinality()
                )
            .sum();
        
        var totalDocsToAdd = additions.stream()
            .mapToInt(s -> s.getLiveDocs() == null ? s.getReader().maxDoc() :
                    s.getLiveDocs().cardinality()
                )
            .sum();
        
        // Record metrics
        deltaContext.recordDeltaDeletions(totalDocsToRemove);
        deltaContext.recordDeltaAdditions(totalDocsToAdd);

        log.atInfo()
            .setMessage("Delta Snapshot will process {} deleted docs and {} added docs")
            .addArgument(totalDocsToRemove)
            .addArgument(totalDocsToAdd)
            .log();

        var maxDocumentsToReadAtOnce = 100; // Arbitrary value
        var sharedSegmentReaderScheduler = Schedulers.boundedElastic();

        // Create additions stream
        var additionsStream = Flux.fromIterable(additions)
            .flatMapSequential( c ->
                LuceneReader.readDocsFromSegment(c,
                    startDocId,
                    sharedSegmentReaderScheduler,
                    maxDocumentsToReadAtOnce,
                    Path.of(c.getReader().getSegmentName()),
                    DocumentChangeType.INDEX)
            ).subscribeOn(sharedSegmentReaderScheduler); // Scheduler to read documents on

        // Create deletions stream - these are documents that were removed between snapshots
        var deletionsStream = Flux.fromIterable(removes)
            .flatMapSequential( c ->
                LuceneReader.readDocsFromSegment(c,
                    startDocId,
                    sharedSegmentReaderScheduler,
                    maxDocumentsToReadAtOnce,
                    Path.of(c.getReader().getSegmentName()),
                    DocumentChangeType.DELETE)
            ).subscribeOn(sharedSegmentReaderScheduler);

        return new DeltaResult(additionsStream, deletionsStream);
    }

    private static List<ReaderAndBase> getAdditionsBetweenSnapshot(TreeMap<String, LuceneLeafReader>
      previousSegmentReaderMap, TreeMap<String, LuceneLeafReader> currentSegmentReaderMap, int startingOffset) {
        var previousSnapshotSegmentReaderKeys = new TreeSet<>(previousSegmentReaderMap.keySet());
        var currentSnapshotSegmentReaderKeys = new TreeSet<>(currentSegmentReaderMap.keySet());

        var onlyInCurrentSnapshotSegmentReaderKeys = new TreeSet<>(currentSnapshotSegmentReaderKeys);
        onlyInCurrentSnapshotSegmentReaderKeys.removeAll(previousSnapshotSegmentReaderKeys);

        var inBothKeys = new TreeSet<>(previousSnapshotSegmentReaderKeys);
        inBothKeys.retainAll(currentSnapshotSegmentReaderKeys);

        List<ReaderAndBase> readerAndBases = new ArrayList<>();
        int offset = startingOffset;
        for (var key : onlyInCurrentSnapshotSegmentReaderKeys) {
            var reader = currentSegmentReaderMap.get(key);
            readerAndBases.add(new ReaderAndBase(reader, offset, reader.getLiveDocs()));
            offset += reader.maxDoc();
        }

        for (var readerKey : inBothKeys) {
            var previousSegmentReader = previousSegmentReaderMap.get(readerKey);
            var currentSegmentReader = currentSegmentReaderMap.get(readerKey);

            var previousLiveDocs = previousSegmentReader.getLiveDocs();
            var currentLiveDocs = currentSegmentReader.getLiveDocs();

            // Only add segment if previousSnapshot was missing a doc that the current one has
            // If previousLiveDocs == null, then previous has a superset of docs than current and we can skip
            if (previousLiveDocs == null) {
                continue;
            }
            BitSetConverter.FixedLengthBitSet liveDocs;
            if (currentLiveDocs != null) {
                // Compute currentLiveDocs AND NOT previousLiveDocs
                liveDocs = new BitSetConverter.FixedLengthBitSet(currentLiveDocs);
                liveDocs.andNot(previousLiveDocs);
            } else {
                // Compute NOT previousLiveDocs (all docs except those in previous)
                liveDocs = new BitSetConverter.FixedLengthBitSet(previousLiveDocs);
                liveDocs.flip(0, currentSegmentReader.maxDoc());
            }

            if (liveDocs.cardinality() == 0) {
                log.atDebug().setMessage("Skipping segment {} since no difference between segments found in snapshot.")
                    .addArgument(readerKey)
                    .log();
            } else {
                var segmentReaderToCreate = new ReaderAndBase(
                    currentSegmentReader,
                    offset,
                    liveDocs
                );
                offset += currentSegmentReader.maxDoc();
                readerAndBases.add(segmentReaderToCreate);
            }
        }
        return readerAndBases;
    }
}
