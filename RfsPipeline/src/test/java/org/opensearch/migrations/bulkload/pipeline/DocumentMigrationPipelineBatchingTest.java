package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
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
            List.of(List.of("doc-0", "doc-1"), List.of("doc-2"), List.of("doc-3"), List.of("doc-4", "doc-5")),
            sink.receivedBatches
        );
    }

    @Test
    void preparesSourceBatchesInParallelWithoutReorderingThem() throws Exception {
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
        Assertions.assertEquals(documents.stream().map(Document::id).toList(), sink.received);
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
