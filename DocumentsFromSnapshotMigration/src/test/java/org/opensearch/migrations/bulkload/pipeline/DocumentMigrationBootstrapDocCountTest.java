package org.opensearch.migrations.bulkload.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;
import org.opensearch.migrations.bulkload.pipeline.adapter.EsShardPartition;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentMigrationBootstrapDocCountTest {
    private record Result(int documentsRead, Optional<SnapshotDocumentCounts> snapshotCount) {}

    private Result migrate(int roots, String startingCursor, Optional<SnapshotDocumentCounts> luceneCount) {
        var partition = new EsShardPartition("snap", "idx", 0);
        var documents = IntStream.range(0, roots)
            .mapToObj(i -> new PositionedDocument(new Document("doc-" + i, new byte[] {1}, Document.Operation.UPSERT,
                Map.of(), Map.of()), Integer.toString(i * 5 + 5)))
            .toList();
        var source = mock(DocumentSource.class);
        when(source.listPartitions("idx")).thenReturn(List.of(partition));
        when(source.findPartition("idx", partition.name())).thenReturn(Optional.of(partition));
        when(source.snapshotDocumentCounts(partition)).thenReturn(luceneCount);
        long start = startingCursor == null ? 0 : Long.parseLong(startingCursor);
        when(source.readDocuments(any(), any())).thenAnswer(call -> Flux.fromIterable(documents)
            .filter(doc -> Long.parseLong(doc.cursorAfter()) > start));
        var processed = new AtomicInteger();
        var sink = mock(DocumentSink.class);
        when(sink.writeBatch(any(), any())).thenAnswer(call -> {
            List<PositionedDocument> batch = call.getArgument(1);
            processed.addAndGet(batch.size());
            return Mono.just(new BatchResult(batch.size(), batch.size(), batch.get(batch.size() - 1).cursorAfter()));
        });
        var count = new AtomicReference<Optional<SnapshotDocumentCounts>>();
        var bootstrap = DocumentMigrationBootstrap.builder()
            .documentSource(source)
            .targetClient(mock(OpenSearchClient.class))
            .cursorConsumer(cursor -> {
                if (cursor.isShardComplete()) {
                    count.set(Optional.ofNullable(cursor.getSnapshotDocumentCounts()));
                }
            })
            .build();
        var workItem = new WorkItemAndDuration(Instant.now().plusSeconds(60),
            new WorkItemAndDuration.WorkItem("idx", partition.name(), startingCursor));
        try (var context = DocumentMigrationTestContext.factory().noOtelTracking().createReindexContext()) {
            bootstrap.runPartitionMigration(workItem, new PipelineConfig(source, sink, 10, 1000, 3), context);
        }
        return new Result(processed.get(), count.get());
    }

    @Test
    void snapshotCountIsIndependentOfResumePosition() {
        var full = migrate(5, null, Optional.of(new SnapshotDocumentCounts(25, 20, 4)));
        var resumed = migrate(5, "14", Optional.of(new SnapshotDocumentCounts(25, 20, 4)));
        assertEquals(5, full.documentsRead());
        assertEquals(3, resumed.documentsRead());
        assertEquals(full.snapshotCount(), resumed.snapshotCount());
        assertEquals(Optional.of(new SnapshotDocumentCounts(25, 20, 4)), resumed.snapshotCount());
    }

    @Test
    void unavailableCountStaysUnknown() {
        assertTrue(migrate(5, null, Optional.empty()).snapshotCount().isEmpty());
    }

    @Test
    void emptyShardReportsZero() {
        assertEquals(Optional.of(new SnapshotDocumentCounts(0, 0, 0)), migrate(0, null, Optional.of(new SnapshotDocumentCounts(0, 0, 0))).snapshotCount());
    }
}
