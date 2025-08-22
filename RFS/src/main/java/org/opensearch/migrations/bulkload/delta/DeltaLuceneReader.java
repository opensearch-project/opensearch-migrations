package org.opensearch.migrations.bulkload.delta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;
import org.opensearch.migrations.bulkload.lucene.ReaderAndBase;
import org.opensearch.migrations.bulkload.tracing.BaseRootRfsContext;
import org.opensearch.migrations.bulkload.tracing.RfsContexts;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * DeltaLuceneReader
 * <p>
 * Provides a delta-style backfill between a previous and current Lucene snapshot.
 * Emits only new documents (skips deletion for now) as {@link RfsLuceneDocument} via Reactor streams.
 *
 * <h3>Functionality</h3>
 * - Builds segment → reader maps from both snapshots.
 * - Detects new segments or new docs in shared segments.
 * - Streams additions for processing.
 * - Calculates deletions for future expansion to support removing deleted docs
 *
 * <h3>Limitations</h3>
 * - Deletions not emitted (logged only).
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

    /* Start reading docs from a specific document id.
       If the startDocId is 0, it will start from the first document in the shard.
       Note: The delta calculation is made on the entire shard regardless of where startDocId
     */
    public static Publisher<RfsLuceneDocument> readDocsByLeavesFromStartingPosition(
        LuceneDirectoryReader previousReader, 
        LuceneDirectoryReader currentReader, 
        int startDocId,
        BaseRootRfsContext rootContext
    ) {
        // Create delta stream context for telemetry
        try (var deltaContext = new RfsContexts.DeltaStreamContext(rootContext, null)) {
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

            log.atLevel(totalDocsToRemove > 0 ? Level.WARN : Level.INFO)
                .setMessage("Delta Snapshot in UPDATES_ONLY mode will skip {} possible deleted docs (could be from segment merges)")
                .addArgument(totalDocsToRemove)
                .log();

            var maxDocumentsToReadAtOnce = 100; // Arbitrary value
            var sharedSegmentReaderScheduler = Schedulers.newBoundedElastic(maxDocumentsToReadAtOnce, Integer.MAX_VALUE, "sharedSegmentReader");

            return Flux.fromIterable(additions)
                .flatMapSequential( c ->
                    LuceneReader.readDocsFromSegment(c,
                        startDocId,
                        sharedSegmentReaderScheduler,
                        maxDocumentsToReadAtOnce,
                        Path.of(c.getReader().getSegmentName()))
                ).subscribeOn(sharedSegmentReaderScheduler) // Scheduler to read documents on
                .publishOn(Schedulers.boundedElastic()) // Switch scheduler for subsequent chain
                .doFinally(s -> sharedSegmentReaderScheduler.dispose());
        }
    }

    private static List<ReaderAndBase> getAdditionsBetweenSnapshot(TreeMap<String, LuceneLeafReader>
      previousSegmentReaderMap, TreeMap<String, LuceneLeafReader> currentSegmentReaderMap, int startingOffset) {
        var prevousSnapshotSegmentReaderKeys    = new TreeSet<>(previousSegmentReaderMap.keySet());
        var currentSnapshotSegmentReaderKeys = new TreeSet<>(currentSegmentReaderMap.keySet());

        var onlyIncurrentSnapshotSegmentReaderKeys = new TreeSet<>(currentSnapshotSegmentReaderKeys);
        onlyIncurrentSnapshotSegmentReaderKeys.removeAll(prevousSnapshotSegmentReaderKeys);

        var inBothKeys        = new TreeSet<>(prevousSnapshotSegmentReaderKeys);
        inBothKeys.retainAll(currentSnapshotSegmentReaderKeys);

        List<ReaderAndBase> readerAndBases = new ArrayList<>();
        int offset = startingOffset;
        for (var key : onlyIncurrentSnapshotSegmentReaderKeys) {
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
