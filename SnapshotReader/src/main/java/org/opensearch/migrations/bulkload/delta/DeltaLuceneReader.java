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
 * DeltaLuceneReader — delta-style backfill between a previous and current Lucene snapshot.
 */
@Slf4j
public class DeltaLuceneReader {

    private DeltaLuceneReader() {}

    public static class DeltaResult {
        public final Flux<LuceneDocumentChange> additions;
        public final Flux<LuceneDocumentChange> deletions;

        public DeltaResult(Flux<LuceneDocumentChange> additions, Flux<LuceneDocumentChange> deletions) {
            this.additions = additions;
            this.deletions = deletions;
        }
    }

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
            leaf -> previousSegmentToLeafReader.put(leaf.reader().getSegmentName(), leaf.reader())
        );

        var currentSegmentToLeafReader = new TreeMap<String, LuceneLeafReader>();
        currentReader.leaves().forEach(
            leaf -> currentSegmentToLeafReader.put(leaf.reader().getSegmentName(), leaf.reader())
        );

        log.atInfo()
            .setMessage("Found {} segments in previous and {} segments in current")
            .addArgument(previousSegmentToLeafReader.size())
            .addArgument(currentSegmentToLeafReader.size())
            .log();

        long totalSegmentsSeen = (long) previousSegmentToLeafReader.size() + currentSegmentToLeafReader.size();
        deltaContext.recordSegmentsSeen(totalSegmentsSeen);

        var removes = getAdditionsBetweenSnapshot(currentSegmentToLeafReader, previousSegmentToLeafReader, Integer.MIN_VALUE);
        var additions = getAdditionsBetweenSnapshot(previousSegmentToLeafReader, currentSegmentToLeafReader, 0);

        var totalDocsToRemove = removes.stream()
            .mapToInt(s -> s.getLiveDocs() == null ? s.getReader().maxDoc() : s.getLiveDocs().cardinality())
            .sum();
        var totalDocsToAdd = additions.stream()
            .mapToInt(s -> s.getLiveDocs() == null ? s.getReader().maxDoc() : s.getLiveDocs().cardinality())
            .sum();

        deltaContext.recordDeltaDeletions(totalDocsToRemove);
        deltaContext.recordDeltaAdditions(totalDocsToAdd);

        log.atInfo()
            .setMessage("Delta Snapshot will process {} deleted docs and {} added docs")
            .addArgument(totalDocsToRemove)
            .addArgument(totalDocsToAdd)
            .log();

        var maxDocumentsToReadAtOnce = 100;
        var sharedSegmentReaderScheduler = Schedulers.boundedElastic();

        var additionsStream = Flux.fromIterable(additions)
            .flatMapSequential(c ->
                LuceneReader.readDocsFromSegment(c,
                    startDocId,
                    sharedSegmentReaderScheduler,
                    maxDocumentsToReadAtOnce,
                    Path.of(c.getReader().getSegmentName()),
                    DocumentChangeType.INDEX)
            ).subscribeOn(sharedSegmentReaderScheduler);

        var deletionsStream = Flux.fromIterable(removes)
            .flatMapSequential(c ->
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
        var prevousSnapshotSegmentReaderKeys    = new TreeSet<>(previousSegmentReaderMap.keySet());
        var currentSnapshotSegmentReaderKeys = new TreeSet<>(currentSegmentReaderMap.keySet());

        var onlyIncurrentSnapshotSegmentReaderKeys = new TreeSet<>(currentSnapshotSegmentReaderKeys);
        onlyIncurrentSnapshotSegmentReaderKeys.removeAll(prevousSnapshotSegmentReaderKeys);

        var inBothKeys = new TreeSet<>(prevousSnapshotSegmentReaderKeys);
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

            if (previousLiveDocs == null) {
                continue;
            }
            BitSetConverter.FixedLengthBitSet liveDocs;
            if (currentLiveDocs != null) {
                liveDocs = new BitSetConverter.FixedLengthBitSet(currentLiveDocs);
                liveDocs.andNot(previousLiveDocs);
            } else {
                liveDocs = new BitSetConverter.FixedLengthBitSet(previousLiveDocs);
                liveDocs.flip(0, currentSegmentReader.maxDoc());
            }

            if (liveDocs.cardinality() == 0) {
                log.atDebug().setMessage("Skipping segment {} since no difference between segments found in snapshot.")
                    .addArgument(readerKey)
                    .log();
            } else {
                var segmentReaderToCreate = new ReaderAndBase(currentSegmentReader, offset, liveDocs);
                offset += currentSegmentReader.maxDoc();
                readerAndBases.add(segmentReaderToCreate);
            }
        }
        return readerAndBases;
    }
}
