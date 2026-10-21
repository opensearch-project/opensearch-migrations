package org.opensearch.migrations.bulkload.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentMigrationBootstrapDocCountTest {
    private record Counts(OptionalLong source, OptionalLong lucene, long thisGeneration, OptionalLong prior) {}
    private record Result(List<WorkItemCursor> cursors, Counts counts) {}

    private Result migrate(int roots, WorkItemAndDuration workItem, OptionalLong luceneCount) {
        var partition = new EsShardPartition("snap", "idx", 0);
        var documents = IntStream.range(0, roots)
            .mapToObj(i -> new Document("doc-" + i, new byte[] {1}, Document.Operation.UPSERT,
                Map.of(), Map.of(Document.SOURCE_META_LUCENE_DOC_NUMBER, i * 5 + 4)))
            .toList();
        var source = mock(DocumentSource.class);
        when(source.listPartitions("idx")).thenReturn(List.of(partition));
        when(source.countLiveDocuments(partition)).thenReturn(luceneCount);
        when(source.readDocuments(any(), anyLong())).thenAnswer(call -> Flux.fromIterable(documents)
            .filter(doc -> doc.position().orElseThrow() >= call.<Long>getArgument(1)));
        var sink = mock(DocumentSink.class);
        when(sink.writeBatch(any(), any())).thenAnswer(call -> {
            List<Document> batch = call.getArgument(1);
            return Mono.just(new BatchResult(batch.size(), batch.size()));
        });
        var cursors = new ArrayList<WorkItemCursor>();
        var counts = new AtomicReference<Counts>();
        var bootstrap = DocumentMigrationBootstrap.builder()
            .documentSource(source)
            .targetClient(mock(OpenSearchClient.class))
            .cursorConsumer(cursors::add)
            .shardDocCountReporter((wi, total, lucene, thisGen, prior) ->
                counts.set(new Counts(total, lucene, thisGen, prior)))
            .build();
        try (var context = DocumentMigrationTestContext.factory().noOtelTracking().createReindexContext()) {
            bootstrap.runPartitionMigration(workItem, new PipelineConfig(source, sink, 10, 1000, 3), context);
        }
        return new Result(cursors, counts.get());
    }

    private static WorkItemAndDuration workItem(long position, OptionalLong count) {
        return new WorkItemAndDuration(Instant.now().plusSeconds(60),
            new WorkItemAndDuration.WorkItem("idx", 0, position), count);
    }

    @Test
    void multiDocumentBatchesPreserveTheTotalAcrossThreeGenerations() {
        var first = migrate(30, workItem(0, OptionalLong.of(0)), OptionalLong.of(150));
        var checkpoint = first.cursors().get(0);
        assertEquals(49, checkpoint.getProgressCheckpointNum());
        assertEquals(OptionalLong.of(9), checkpoint.getDocsBeforeCheckpoint());
        var second = migrate(30, workItem(checkpoint.getProgressCheckpointNum(),
            checkpoint.getDocsBeforeCheckpoint()), OptionalLong.of(150));
        assertEquals(OptionalLong.of(30), second.counts().source());
        assertEquals(21, second.counts().thisGeneration());
        checkpoint = second.cursors().get(0);
        var third = migrate(30, workItem(checkpoint.getProgressCheckpointNum(),
            checkpoint.getDocsBeforeCheckpoint()), OptionalLong.of(150));
        assertEquals(OptionalLong.of(30), third.counts().source());
        assertEquals(OptionalLong.of(150), third.counts().lucene());
    }

    @Test
    void unknownCountsStayUnknownAcrossHandoffs() {
        var result = migrate(30, workItem(49, OptionalLong.empty()), OptionalLong.empty());
        assertTrue(result.counts().source().isEmpty());
        assertTrue(result.counts().lucene().isEmpty());
        assertTrue(result.counts().prior().isEmpty());
        assertTrue(result.cursors().stream().allMatch(c -> c.getDocsBeforeCheckpoint().isEmpty()));
    }

    @Test
    void anEmptyShardReportsKnownZeroCounts() {
        var result = migrate(0, workItem(0, OptionalLong.of(0)), OptionalLong.of(0));
        assertEquals(new Counts(OptionalLong.of(0), OptionalLong.of(0), 0, OptionalLong.of(0)), result.counts());
    }
}
