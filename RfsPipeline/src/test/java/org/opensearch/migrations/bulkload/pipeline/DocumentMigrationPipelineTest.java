package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentMigrationPipelineTest {
    private static final EsShardPartition PARTITION = new EsShardPartition("snapshot", "index", 0);

    @Test
    void checkpointsFollowInputOrderWhenBulkRequestsFinishOutOfOrder() {
        var source = mock(DocumentSource.class);
        var sink = mock(DocumentSink.class);
        var first = Sinks.<BatchResult>one();
        var second = Sinks.<BatchResult>one();
        var subscribed = new CountDownLatch(2);
        when(source.readDocuments(PARTITION, 10)).thenReturn(Flux.just(document("first"), document("second")));
        when(sink.writeBatch(eq("index"), anyList())).thenAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(1);
            var response = batch.get(0).id().equals("first") ? first : second;
            return response.asMono().doOnSubscribe(s -> subscribed.countDown());
        });
        var pipeline = new DocumentMigrationPipeline(source, sink, 1, 1024, 1, 2);

        StepVerifier.create(pipeline.migratePartition(PARTITION, "index", 10))
            .then(() -> await(subscribed))
            .then(() -> second.tryEmitValue(new BatchResult(1, 2)))
            .then(() -> first.tryEmitValue(new BatchResult(1, 2)))
            .expectNextMatches(cursor -> cursor.lastDocProcessed() == 11)
            .expectNextMatches(cursor -> cursor.lastDocProcessed() == 12)
            .expectComplete()
            .verify(Duration.ofSeconds(10));
    }

    @Test
    void retryingTheSamePublisherStartsAtTheOriginalCheckpoint() {
        var source = mock(DocumentSource.class);
        var sink = mock(DocumentSink.class);
        when(source.readDocuments(PARTITION, 7)).thenReturn(Flux.just(document("first")));
        when(sink.writeBatch(eq("index"), anyList())).thenReturn(Mono.just(new BatchResult(1, 2)));
        var migration = new DocumentMigrationPipeline(source, sink, 1, 1024)
            .migratePartition(PARTITION, "index", 7);

        StepVerifier.create(migration.repeat(1))
            .expectNextMatches(cursor -> cursor.lastDocProcessed() == 8)
            .expectNextMatches(cursor -> cursor.lastDocProcessed() == 8)
            .expectComplete()
            .verify(Duration.ofSeconds(10));
    }

    @Test
    void sourceLessDeletesAreBatchedByMetadataBytes() {
        var source = mock(DocumentSource.class);
        var sink = mock(DocumentSink.class);
        when(source.readDocuments(PARTITION, 0)).thenReturn(Flux.range(0, 10000)
            .map(i -> new Document("doc-" + i, null, Document.Operation.DELETE,
                Map.of(Document.HINT_ROUTING, "route"), Map.of())));
        when(sink.writeBatch(eq("index"), anyList())).thenAnswer(invocation -> {
            List<Document> batch = invocation.getArgument(1);
            assertTrue(batch.size() <= 10, "A deletion-only shard must not accumulate in a single request");
            return Mono.just(new BatchResult(batch.size(), 0));
        });
        var progress = new DocumentMigrationPipeline(source, sink, Integer.MAX_VALUE, 1024)
            .migratePartition(PARTITION, "index", 0).collectList().block(Duration.ofSeconds(10));

        assertTrue(progress.size() > 1000);
        assertEquals(10000, progress.get(progress.size() - 1).lastDocProcessed());
    }

    private static Document document(String id) {
        return new Document(id, new byte[] {'{', '}'}, Document.Operation.UPSERT, null, null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Both bulk requests must be in flight");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
