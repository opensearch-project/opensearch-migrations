package org.opensearch.migrations.bulkload.delta;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.RfsLuceneDocument;
import org.opensearch.migrations.bulkload.lucene.BitSetConverter;
import org.opensearch.migrations.bulkload.lucene.LuceneDirectoryReader;
import org.opensearch.migrations.bulkload.lucene.LuceneLeafReader;
import org.opensearch.migrations.bulkload.lucene.LuceneReader;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.slf4j.event.Level;
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
        log.atInfo()
            .setMessage("Starting delta backfill from position {}")
            .addArgument(startDocId)
            .log();
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

        log.atInfo()
            .setMessage("Found {} segments in base and {} segments in current")
            .addArgument(baseSegmentToLeafReader.size())
            .addArgument(currentSegmentToLeafReader.size())
            .log();
            
        log.atInfo()
            .setMessage("Calculating remove and add docs in snapshot")
            .log();

        // Docs to remove are additions between new and old
        // TODO: Consider updating offset to a int to start at 0 instead of Integer.MIN_VALUE
        var removes = getAdditionsBetweenSnapshot(currentSegmentToLeafReader, baseSegmentToLeafReader, Integer.MIN_VALUE);
        var additions = getAdditionsBetweenSnapshot(baseSegmentToLeafReader, currentSegmentToLeafReader, 0);

        var totalDocsToRemove = removes.stream()
            .mapToInt(s -> s.liveDocOverride == null ? s.reader.maxDoc() :
                    s.liveDocOverride.cardinality()
                )
            .sum();

        log.atLevel(totalDocsToRemove > 0 ? Level.WARN : Level.INFO)
            .setMessage("Delta Snapshot in UPDATES_ONLY mode will skip {} possible deleted docs (could be from segment merges)")
            .addArgument(totalDocsToRemove)
            .log();

        var maxDocumentsToReadAtOnce = 100; // Arbitrary value
        var sharedSegmentReaderScheduler = Schedulers.newBoundedElastic(maxDocumentsToReadAtOnce, Integer.MAX_VALUE, "sharedSegmentReader");

        return Flux.fromIterable(additions)
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

    // Lower case to appease sonar until sonar is updated to java 17
    record segmentReaderAndLiveDoc(
        LuceneLeafReader reader,
        BitSetConverter.LengthDisabledBitSet liveDocOverride,
        int baseDocIdx
    ){
        // Base Record Implementation
    }


    private static List<segmentReaderAndLiveDoc> getAdditionsBetweenSnapshot(TreeMap<String, LuceneLeafReader>
      baseSegmentReaderMap, TreeMap<String, LuceneLeafReader> currentSegmentReaderMap, int startingOffset) {
        var baseKeys    = new TreeSet<>(baseSegmentReaderMap.keySet());
        var currentKeys = new TreeSet<>(currentSegmentReaderMap.keySet());

        var onlyInCurrentKeys = new TreeSet<>(currentKeys);
        onlyInCurrentKeys.removeAll(baseKeys);

        var inBothKeys        = new TreeSet<>(baseKeys);
        inBothKeys.retainAll(currentKeys);

        List<segmentReaderAndLiveDoc> readerAndBases = new ArrayList<>();
        int offset = startingOffset;
        for (var key : onlyInCurrentKeys) {
            var reader = currentSegmentReaderMap.get(key);
            readerAndBases.add(new segmentReaderAndLiveDoc(reader, reader.getLiveDocs(), offset));
            offset += reader.maxDoc();
        }

        for (var readerKey : inBothKeys) {
            var baseSegmentReader = baseSegmentReaderMap.get(readerKey);
            var currentSegmentReader = currentSegmentReaderMap.get(readerKey);

            var baseLiveDocs = baseSegmentReader.getLiveDocs();
            var currentLiveDocs = currentSegmentReader.getLiveDocs();

            // Only add segment if baseSnapshot was missing a doc that the current one has
            // If baseLiveDocs == null, then base has a superset of docs than current and we can skip
            if (baseLiveDocs == null) {
                continue;
            }
            BitSetConverter.LengthDisabledBitSet liveDocs;
            if (currentLiveDocs != null) {
                // Compute currentLiveDocs AND NOT baseLiveDocs
                liveDocs = (BitSetConverter.LengthDisabledBitSet) currentLiveDocs.clone();
                liveDocs.andNot(baseLiveDocs);
            } else {
                // Compute NOT baseLiveDocs (all docs except those in base)
                liveDocs = (BitSetConverter.LengthDisabledBitSet) baseLiveDocs.clone();
                liveDocs.flip(0, currentSegmentReader.maxDoc());
            }

            if (liveDocs.cardinality() == 0) {
                log.atDebug().setMessage("Skipping segment {} since no difference between segments found in snapshot.")
                    .addArgument(readerKey)
                    .log();
            } else {
                var segmentReaderToCreate = new segmentReaderAndLiveDoc(
                    currentSegmentReader,
                    liveDocs,
                    offset
                );
                offset += currentSegmentReader.maxDoc();
                readerAndBases.add(segmentReaderToCreate);
            }
        }
        return readerAndBases;
    }

    static Flux<RfsLuceneDocument> readDocsFromSegment(segmentReaderAndLiveDoc readerAndBase, int docStartingId, Scheduler scheduler,
                                                int concurrency, Path indexDirectoryPath) {
        var segmentReader = readerAndBase.reader;
        var liveDocs = readerAndBase.liveDocOverride;

        int segmentDocBase = readerAndBase.baseDocIdx;

        // Start at
        int startDocIdInSegment = Math.max(docStartingId - segmentDocBase, 0);

        // For any errors, we want to log the segment reader debug info so we can see which segment is causing the issue.
        // This allows us to pass the supplier to getDocument without having to recompute the debug info
        // every time if requested multiple times.
        var segmentReaderDebugInfoCache = new AtomicReference<String>();
        final Supplier<String> getSegmentReaderDebugInfo = () -> segmentReaderDebugInfoCache.updateAndGet(s ->
            s == null ? segmentReader.toString() : s
        );

        log.atDebug().setMessage("For segment: {}, migrating from doc: {}. Will process {} docs in segment.")
                .addArgument(segmentReader)
                .addArgument(startDocIdInSegment)
                .addArgument(() -> segmentReader.maxDoc() - startDocIdInSegment)
                .log();
        var idxStream = (liveDocs != null) ? liveDocs.stream().filter(idx -> idx >= startDocIdInSegment) :
            IntStream.range(startDocIdInSegment, readerAndBase.reader.maxDoc());
        return Flux.fromStream(idxStream.boxed())
                .flatMapSequentialDelayError(docIdx -> Mono.defer(() -> {
                    try {
                        // Get document, returns null to skip malformed docs
                        RfsLuceneDocument document = LuceneReader.getDocument(segmentReader, docIdx, true, segmentDocBase, getSegmentReaderDebugInfo, indexDirectoryPath);
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
}
