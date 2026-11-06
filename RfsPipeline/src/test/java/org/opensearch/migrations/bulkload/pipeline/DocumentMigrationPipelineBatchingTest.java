package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.sink.GroupedDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class DocumentMigrationPipelineBatchingTest {
    private static final int MIB = 1024 * 1024;

    static Stream<Arguments> examples() {
        return Stream.of(
            Arguments.of(new int[] {9, 9, 9, 9}, List.of(1, 1, 1, 1), List.of(9L, 9L, 9L, 9L)),
            Arguments.of(new int[] {1, 1, 1, 98}, List.of(3, 1), List.of(3L, 98L)),
            Arguments.of(new int[] {1, 1, 1, 101}, List.of(3, 1), List.of(3L, 101L)),
            Arguments.of(new int[] {26, 26, 26, 26}, List.of(1, 1, 1, 1), List.of(26L, 26L, 26L, 26L)),
            Arguments.of(new int[] {101, 1, 1, 1}, List.of(1, 3), List.of(101L, 3L)),
            Arguments.of(new int[] {1, 1, 1, 8}, List.of(3, 1), List.of(3L, 8L)),
            Arguments.of(new int[] {5, 5, 5}, List.of(2, 1), List.of(10L, 5L)),
            Arguments.of(new int[] {1, 11, 1}, List.of(1, 1, 1), List.of(1L, 11L, 1L))
        );
    }

    @ParameterizedTest
    @MethodSource("examples")
    void respectsDefaultByteLimitExceptForSingleOversizedDocuments(
        int[] sourceMiB, List<Integer> expectedCounts, List<Long> expectedMiB
    ) {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = new ArrayList<Document>();
        for (int i = 0; i < sourceMiB.length; i++) {
            // Valid JSON with an exactly sized ASCII string value.
            var bytes = new byte[sourceMiB[i] * MIB];
            Arrays.fill(bytes, (byte) 'x');
            var prefix = "{\"data\":\"".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.arraycopy(prefix, 0, bytes, 0, prefix.length);
            bytes[bytes.length - 2] = '"';
            bytes[bytes.length - 1] = '}';
            documents.add(new Document("doc-" + i, bytes, Document.Operation.UPSERT, Map.of(), Map.of()));
        }

        var counts = new CopyOnWriteArrayList<Integer>();
        var sizes = new CopyOnWriteArrayList<Long>();
        var writtenIds = new CopyOnWriteArrayList<String>();
        var source = Mockito.mock(DocumentSource.class);
        var sink = Mockito.mock(DocumentSink.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));
        Mockito.when(sink.writeBatch(Mockito.eq("index"), Mockito.anyList())).thenAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(1);
            long bytes = batch.stream().mapToLong(Document::sourceLength).sum();
            counts.add(batch.size());
            sizes.add(bytes / MIB);
            writtenIds.addAll(batch.stream().map(Document::id).toList());
            Assertions.assertTrue(batch.size() == 1 || bytes <= 10L * MIB);
            return Mono.just(new BatchResult(batch.size(), bytes));
        });

        new DocumentMigrationPipeline(source, sink, Integer.MAX_VALUE, 10L * MIB)
            .migratePartition(partition, "index", 0)
            .blockLast(Duration.ofSeconds(20));

        Assertions.assertEquals(expectedCounts, counts);
        Assertions.assertEquals(expectedMiB, sizes);
        Assertions.assertEquals(documents.stream().map(Document::id).toList(), writtenIds);
    }

    @Test
    void carriesPreparedSizeAnnotationsFromBatchingToTheSink() {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = List.of(
            new Document("doc-1", new byte[] {'1'}, Document.Operation.UPSERT, Map.of(), Map.of()),
            new Document("doc-2", new byte[] {'2'}, Document.Operation.UPSERT, Map.of(), Map.of())
        );
        var source = Mockito.mock(DocumentSource.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));

        class AnnotatingSink implements GroupedDocumentSink<String> {
            final List<List<PreparedDocument<String>>> receivedBatches = new ArrayList<>();

            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                throw new AssertionError("The grouped pipeline must retain the preparation annotation");
            }

            @Override
            public int preferredSourceBatchGroupSize() {
                return 2;
            }

            @Override
            public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
                return new PreparedDocument<>(document, 6, "prepared-" + document.id());
            }

            @Override
            public Mono<BatchResult> writeBatchGroup(
                String collectionName, List<List<Document>> sourceBatches, int requestConcurrency
            ) {
                throw new AssertionError("The grouped pipeline must retain the preparation annotation");
            }

            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                receivedBatches.addAll(sourceBatches);
                long documentCount = sourceBatches.stream().mapToLong(List::size).sum();
                return Mono.just(new BatchResult(documentCount, documentCount));
            }
        }

        var sink = new AnnotatingSink();
        new DocumentMigrationPipeline(source, sink, Integer.MAX_VALUE, 10)
            .migratePartition(partition, "index", 0)
            .blockLast(Duration.ofSeconds(10));

        Assertions.assertEquals(List.of(List.of("doc-1"), List.of("doc-2")),
            sink.receivedBatches.stream()
                .map(batch -> batch.stream().map(prepared -> prepared.document().id()).toList())
                .toList());
        Assertions.assertEquals(List.of(List.of("prepared-doc-1"), List.of("prepared-doc-2")),
            sink.receivedBatches.stream()
                .map(batch -> batch.stream()
                    .map(GroupedDocumentSink.PreparedDocument::preparation)
                    .toList())
                .toList());
    }

    @Test
    void packsPreparedChunksByExactSizeIncludingAnOversizedDocument() {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = Stream.iterate(0, i -> i + 1)
            .limit(6)
            .map(i -> new Document("doc-" + i, new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of()))
            .toList();
        var preparedSizes = List.of(4L, 4L, 4L, 11L, 4L, 4L);
        var source = Mockito.mock(DocumentSource.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));

        class ExactPackingSink implements GroupedDocumentSink<String> {
            final List<List<String>> receivedBatches = new ArrayList<>();

            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                throw new AssertionError("The grouped path must be used");
            }

            @Override
            public int preferredSourceBatchGroupSize() {
                return 8;
            }

            @Override
            public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
                int index = Integer.parseInt(document.id().substring("doc-".length()));
                return new PreparedDocument<>(document, preparedSizes.get(index), "prepared-" + document.id());
            }

            @Override
            public Mono<BatchResult> writeBatchGroup(
                String collectionName, List<List<Document>> sourceBatches, int requestConcurrency
            ) {
                throw new AssertionError("The prepared grouped path must be used");
            }

            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                sourceBatches.stream()
                    .map(batch -> batch.stream().map(item -> item.document().id()).toList())
                    .forEach(receivedBatches::add);
                long count = sourceBatches.stream().mapToLong(List::size).sum();
                return Mono.just(new BatchResult(count, count));
            }
        }

        var sink = new ExactPackingSink();
        new DocumentMigrationPipeline(source, sink, 10, 10, 1, 8)
            .migratePartition(partition, "index", 0)
            .blockLast(Duration.ofSeconds(10));

        Assertions.assertEquals(
            List.of("doc-0,doc-1", "doc-2", "doc-3", "doc-4,doc-5"),
            sink.receivedBatches.stream().map(batch -> String.join(",", batch)).sorted().toList()
        );
    }

    @Test
    void reportsPipelineBoundariesOncePerChunkBatchAndRequestGroup() {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = Stream.iterate(0, i -> i + 1)
            .limit(6)
            .map(i -> new Document("doc-" + i, new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of()))
            .toList();
        var preparedSizes = List.of(4L, 4L, 4L, 11L, 4L, 4L);
        var source = Mockito.mock(DocumentSource.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));

        class InstrumentedSink implements GroupedDocumentSink<String> {
            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                throw new AssertionError("The grouped path must be used");
            }

            @Override
            public int preferredSourceBatchGroupSize() {
                return 8;
            }

            @Override
            public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
                int index = Integer.parseInt(document.id().substring("doc-".length()));
                return new PreparedDocument<>(document, preparedSizes.get(index), "prepared-" + document.id());
            }

            @Override
            public Mono<BatchResult> writeBatchGroup(
                String collectionName, List<List<Document>> sourceBatches, int requestConcurrency
            ) {
                throw new AssertionError("The prepared grouped path must be used");
            }

            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                long count = sourceBatches.stream().mapToLong(List::size).sum();
                return Mono.just(new BatchResult(count, count));
            }
        }

        class RecordingMetrics implements PipelineMetrics {
            final AtomicInteger configured = new AtomicInteger();
            final AtomicInteger chunksQueued = new AtomicInteger();
            final AtomicInteger chunksStarted = new AtomicInteger();
            final AtomicInteger chunksFinished = new AtomicInteger();
            final AtomicInteger chunksReleased = new AtomicInteger();
            final AtomicInteger packingCalls = new AtomicInteger();
            final AtomicInteger batchesPacked = new AtomicInteger();
            final AtomicInteger requestGroupsStarted = new AtomicInteger();
            final AtomicInteger requestGroupsFinished = new AtomicInteger();
            final AtomicInteger requestGroupBatches = new AtomicInteger();
            final AtomicInteger requestGroupDocuments = new AtomicInteger();
            final AtomicInteger checkpointedDocuments = new AtomicInteger();
            final AtomicInteger packedDocuments = new AtomicInteger();

            @Override
            public void onConfigured(
                long maxBatchDocuments,
                long maxBatchBytes,
                long batchConcurrency,
                long preparationConcurrency,
                long preparationChunkDocuments,
                long preparationReservoirChunks,
                long sourceBatchGroupSize
            ) {
                Assertions.assertEquals(10, maxBatchDocuments);
                Assertions.assertEquals(10, maxBatchBytes);
                Assertions.assertEquals(8, batchConcurrency);
                Assertions.assertTrue(preparationConcurrency >= 1);
                Assertions.assertEquals(3, preparationChunkDocuments);
                Assertions.assertEquals(preparationConcurrency * 3, preparationReservoirChunks);
                Assertions.assertEquals(8, sourceBatchGroupSize);
                configured.incrementAndGet();
            }

            @Override
            public void onPreparationChunkQueued(long count, long sourceBytes) {
                chunksQueued.incrementAndGet();
            }

            @Override
            public void onPreparationChunkStarted(long count, long sourceBytes, double queueMillis) {
                Assertions.assertTrue(queueMillis >= 0);
                chunksStarted.incrementAndGet();
            }

            @Override
            public void onPreparationChunkFinished(
                long count, long sourceBytes, long preparedBytes, double durationMillis, boolean successful
            ) {
                Assertions.assertTrue(successful);
                Assertions.assertTrue(durationMillis >= 0);
                chunksFinished.incrementAndGet();
            }

            @Override
            public void onPreparationChunkReleased(long count, long preparedBytes, double orderedWaitMillis) {
                Assertions.assertTrue(orderedWaitMillis >= 0);
                chunksReleased.incrementAndGet();
            }

            @Override
            public void onPackingChunk(long count, double durationMillis) {
                Assertions.assertTrue(durationMillis >= 0);
                packingCalls.incrementAndGet();
            }

            @Override
            public void onBatchPacked(
                long count, long preparedBytes, double documentFillPercent, double byteFillPercent
            ) {
                batchesPacked.incrementAndGet();
                packedDocuments.addAndGet(Math.toIntExact(count));
            }

            @Override
            public void onRequestGroupStarted(
                long batches, long count, long preparedBytes, double oldestReadyMillis
            ) {
                Assertions.assertTrue(oldestReadyMillis >= 0);
                requestGroupBatches.addAndGet(Math.toIntExact(batches));
                requestGroupDocuments.addAndGet(Math.toIntExact(count));
                requestGroupsStarted.incrementAndGet();
            }

            @Override
            public void onRequestGroupFinished(
                long count, long preparedBytes, double durationMillis, boolean successful
            ) {
                Assertions.assertTrue(successful);
                Assertions.assertTrue(durationMillis >= 0);
                requestGroupsFinished.incrementAndGet();
            }

            @Override
            public void onCheckpoint(long count, long sourceBytes) {
                checkpointedDocuments.addAndGet(Math.toIntExact(count));
            }
        }

        var metrics = new RecordingMetrics();
        new DocumentMigrationPipeline(source, new InstrumentedSink(), 10, 10, 1, 8, metrics)
            .migratePartition(partition, "index", 0)
            .blockLast(Duration.ofSeconds(10));

        Assertions.assertEquals(1, metrics.configured.get());
        Assertions.assertEquals(2, metrics.chunksQueued.get());
        Assertions.assertEquals(2, metrics.chunksStarted.get());
        Assertions.assertEquals(2, metrics.chunksFinished.get());
        Assertions.assertEquals(2, metrics.chunksReleased.get());
        Assertions.assertEquals(2, metrics.packingCalls.get());
        Assertions.assertEquals(4, metrics.batchesPacked.get());
        Assertions.assertEquals(6, metrics.packedDocuments.get());
        Assertions.assertEquals(1, metrics.requestGroupsStarted.get());
        Assertions.assertEquals(1, metrics.requestGroupsFinished.get());
        Assertions.assertEquals(4, metrics.requestGroupBatches.get());
        Assertions.assertEquals(6, metrics.requestGroupDocuments.get());
        Assertions.assertEquals(6, metrics.checkpointedDocuments.get());
    }

    @Test
    void preparesSourceBatchesInParallelAndCheckpointsInOrder() throws Exception {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = Stream.iterate(0, i -> i + 1)
            .limit(4)
            .map(i -> new Document("doc-" + i, new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of()))
            .toList();
        var source = Mockito.mock(DocumentSource.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));

        class ParallelPreparationSink implements GroupedDocumentSink<String> {
            final CountDownLatch preparationsStarted = new CountDownLatch(2);
            final CountDownLatch releasePreparations = new CountDownLatch(1);
            final AtomicInteger activePreparations = new AtomicInteger();
            final AtomicInteger maxActivePreparations = new AtomicInteger();
            final List<String> received = new CopyOnWriteArrayList<>();

            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                throw new AssertionError("The grouped path must be used");
            }

            @Override
            public int preferredSourceBatchGroupSize() {
                return 1;
            }

            @Override
            public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
                int active = activePreparations.incrementAndGet();
                maxActivePreparations.accumulateAndGet(active, Math::max);
                preparationsStarted.countDown();
                try {
                    if (!releasePreparations.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release preparation work");
                    }
                    return new PreparedDocument<>(document, document.sourceLength(), "prepared-" + document.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    activePreparations.decrementAndGet();
                }
            }

            @Override
            public Mono<BatchResult> writeBatchGroup(
                String collectionName, List<List<Document>> sourceBatches, int requestConcurrency
            ) {
                throw new AssertionError("The prepared grouped path must be used");
            }

            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                sourceBatches.stream()
                    .flatMap(List::stream)
                    .map(PreparedDocument::document)
                    .map(Document::id)
                    .forEach(received::add);
                long count = sourceBatches.stream().mapToLong(List::size).sum();
                return Mono.just(new BatchResult(count, count));
            }
        }

        var sink = new ParallelPreparationSink();
        var future = new DocumentMigrationPipeline(source, sink, 1, 100, 1, 4)
            .migratePartition(partition, "index", 0)
            .collectList()
            .toFuture();
        boolean preparationsOverlapped = sink.preparationsStarted.await(1, TimeUnit.SECONDS);
        sink.releasePreparations.countDown();
        future.get(10, TimeUnit.SECONDS);

        Assertions.assertTrue(preparationsOverlapped);
        Assertions.assertTrue(sink.maxActivePreparations.get() >= 2);
        Assertions.assertEquals(
            documents.stream().map(Document::id).sorted().toList(),
            sink.received.stream().sorted().toList()
        );
        Assertions.assertEquals(
            List.of(1L, 2L, 3L, 4L),
            future.get().stream().map(cursor -> cursor.nextPosition()).toList()
        );
    }

    @Test
    void keepsPreparationOnStablePersistentLanes() {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = Stream.iterate(0, i -> i + 1)
            .limit(8)
            .map(i -> new Document("doc-" + i, new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of()))
            .toList();
        var source = Mockito.mock(DocumentSource.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));

        class LaneRecordingSink implements GroupedDocumentSink<String> {
            final Map<String, String> preparationThreads = new ConcurrentHashMap<>();

            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                throw new AssertionError("The grouped path must be used");
            }

            @Override
            public int preferredSourceBatchGroupSize() {
                return 1;
            }

            @Override
            public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
                preparationThreads.put(document.id(), Thread.currentThread().getName());
                return new PreparedDocument<>(document, 1, "prepared-" + document.id());
            }

            @Override
            public Mono<BatchResult> writeBatchGroup(
                String collectionName, List<List<Document>> sourceBatches, int requestConcurrency
            ) {
                throw new AssertionError("The prepared grouped path must be used");
            }

            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                long count = sourceBatches.stream().mapToLong(List::size).sum();
                return Mono.just(new BatchResult(count, count));
            }
        }

        var sink = new LaneRecordingSink();
        new DocumentMigrationPipeline(source, sink, 1, 100, 1, 4)
            .migratePartition(partition, "index", 0)
            .blockLast(Duration.ofSeconds(10));

        Assertions.assertTrue(sink.preparationThreads.values().stream().distinct().count() >= 2);
        for (int lane = 0; lane < 4; lane++) {
            Assertions.assertEquals(
                sink.preparationThreads.get("doc-" + lane),
                sink.preparationThreads.get("doc-" + (lane + 4))
            );
        }
    }

    @Test
    void combinesConcurrentRequestResultsForOneSourceChunk() throws Exception {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = List.of(
            new Document("doc-0", new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of()),
            new Document("doc-1", new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of())
        );
        var source = Mockito.mock(DocumentSource.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));
        var started = new CountDownLatch(2);
        var completions = new ConcurrentHashMap<String, Sinks.One<BatchResult>>();
        documents.forEach(document -> completions.put(document.id(), Sinks.one()));

        class ConcurrentResultSink implements GroupedDocumentSink<String> {
            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                throw new AssertionError("The grouped path must be used");
            }

            @Override
            public int preferredSourceBatchGroupSize() {
                return 1;
            }

            @Override
            public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
                return new PreparedDocument<>(document, 6, "prepared-" + document.id());
            }

            @Override
            public Mono<BatchResult> writeBatchGroup(
                String collectionName, List<List<Document>> sourceBatches, int requestConcurrency
            ) {
                throw new AssertionError("The prepared grouped path must be used");
            }

            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                var id = sourceBatches.get(0).get(0).document().id();
                started.countDown();
                return completions.get(id).asMono();
            }
        }

        var future = new DocumentMigrationPipeline(source, new ConcurrentResultSink(), 8, 10, 1, 2)
            .migratePartition(partition, "index", 0)
            .collectList()
            .toFuture();
        Assertions.assertTrue(started.await(10, TimeUnit.SECONDS));
        var target = new MigrationMetrics.Operations(1, 0, 1);
        var request = new MigrationMetrics.Requests(1, 1, 0, 6, 1);
        CompletableFuture.allOf(
            CompletableFuture.runAsync(() ->
                completions.get("doc-0").tryEmitValue(new BatchResult(1, 1, target, request))),
            CompletableFuture.runAsync(() ->
                completions.get("doc-1").tryEmitValue(new BatchResult(1, 1, target, request)))
        ).get(10, TimeUnit.SECONDS);

        var cursors = future.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(1, cursors.size());
        var metrics = cursors.get(0).metrics();
        Assertions.assertEquals(2, metrics.target().upserts());
        Assertions.assertEquals(2, metrics.requests().total());
        Assertions.assertEquals(12, metrics.requests().bytes());
    }

    @Test
    void keepsSeveralPreparationChunksInFlightWhenTheTargetBatchIsLarge() throws Exception {
        var partition = new EsShardPartition("snapshot", "index", 0);
        var documents = Stream.iterate(0, i -> i + 1)
            .limit(8)
            .map(i -> new Document("doc-" + i, new byte[] { 1 }, Document.Operation.UPSERT, Map.of(), Map.of()))
            .toList();
        var source = Mockito.mock(DocumentSource.class);
        Mockito.when(source.readDocuments(partition, 0)).thenReturn(Flux.fromIterable(documents));

        class ChunkedPreparationSink implements GroupedDocumentSink<String> {
            final CountDownLatch preparationsStarted = new CountDownLatch(2);
            final CountDownLatch releasePreparations = new CountDownLatch(1);
            final AtomicInteger activePreparations = new AtomicInteger();
            final AtomicInteger maxActivePreparations = new AtomicInteger();

            @Override
            public Mono<Void> createCollection(CollectionMetadata metadata) {
                return Mono.empty();
            }

            @Override
            public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
                throw new AssertionError("The grouped path must be used");
            }

            @Override
            public int preferredSourceBatchGroupSize() {
                return 1;
            }

            @Override
            public PreparedDocument<String> prepareSourceDocument(String collectionName, Document document) {
                int active = activePreparations.incrementAndGet();
                maxActivePreparations.accumulateAndGet(active, Math::max);
                preparationsStarted.countDown();
                try {
                    if (!releasePreparations.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release preparation work");
                    }
                    return new PreparedDocument<>(document, document.sourceLength(), "prepared-" + document.id());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                } finally {
                    activePreparations.decrementAndGet();
                }
            }

            @Override
            public Mono<BatchResult> writeBatchGroup(
                String collectionName, List<List<Document>> sourceBatches, int requestConcurrency
            ) {
                throw new AssertionError("The prepared grouped path must be used");
            }

            @Override
            public Mono<BatchResult> writePreparedBatchGroup(
                String collectionName,
                List<List<PreparedDocument<String>>> sourceBatches,
                int requestConcurrency
            ) {
                long count = sourceBatches.stream().mapToLong(List::size).sum();
                return Mono.just(new BatchResult(count, count));
            }
        }

        var sink = new ChunkedPreparationSink();
        var future = new DocumentMigrationPipeline(source, sink, 8, 100, 1, 4)
            .migratePartition(partition, "index", 0)
            .collectList()
            .toFuture();
        boolean preparationsOverlapped = sink.preparationsStarted.await(1, TimeUnit.SECONDS);
        sink.releasePreparations.countDown();
        future.get(10, TimeUnit.SECONDS);

        Assertions.assertTrue(preparationsOverlapped);
        Assertions.assertTrue(sink.maxActivePreparations.get() >= 2);
    }
}
