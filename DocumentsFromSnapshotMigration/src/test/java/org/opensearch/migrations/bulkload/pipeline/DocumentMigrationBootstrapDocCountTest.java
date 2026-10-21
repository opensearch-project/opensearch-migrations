package org.opensearch.migrations.bulkload.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;
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
    private record Result(int documentsRead, OptionalLong snapshotCount) {}

    private Result migrate(int roots, long startingPosition, OptionalLong luceneCount) {
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
        var processed = new AtomicInteger();
        var sink = mock(DocumentSink.class);
        when(sink.writeBatch(any(), any())).thenAnswer(call -> {
            List<Document> batch = call.getArgument(1);
            processed.addAndGet(batch.size());
            return Mono.just(new BatchResult(batch.size(), batch.size()));
        });
        var count = new AtomicReference<OptionalLong>();
        var bootstrap = DocumentMigrationBootstrap.builder()
            .documentSource(source)
            .targetClient(mock(OpenSearchClient.class))
            .shardDocCountReporter((wi, total) -> count.set(total))
            .build();
        var workItem = new WorkItemAndDuration(Instant.now().plusSeconds(60),
            new WorkItemAndDuration.WorkItem("idx", 0, startingPosition));
        try (var context = DocumentMigrationTestContext.factory().noOtelTracking().createReindexContext()) {
            bootstrap.runPartitionMigration(workItem, new PipelineConfig(source, sink, 10, 1000, 3), context);
        }
        return new Result(processed.get(), count.get());
    }

    @Test
    void snapshotCountIsIndependentOfResumePosition() {
        var full = migrate(5, 0, OptionalLong.of(25));
        var resumed = migrate(5, 14, OptionalLong.of(25));
        assertEquals(5, full.documentsRead());
        assertEquals(3, resumed.documentsRead());
        assertEquals(full.snapshotCount(), resumed.snapshotCount());
        assertEquals(OptionalLong.of(25), resumed.snapshotCount());
    }

    @Test
    void unavailableCountStaysUnknown() {
        assertTrue(migrate(5, 0, OptionalLong.empty()).snapshotCount().isEmpty());
    }

    @Test
    void emptyShardReportsZero() {
        assertEquals(OptionalLong.of(0), migrate(0, 0, OptionalLong.of(0)).snapshotCount());
    }
}
