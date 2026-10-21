package org.opensearch.migrations;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration.WorkItem;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.metrics.WorkItemMetricsRecord;
import org.opensearch.migrations.reindexer.metrics.WorkItemMetricsSink;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkItemMetricsReporterTest {
    @Test
    void stampsIdentityAndPreservesUnknownCounts() {
        var sink = mock(WorkItemMetricsSink.class);
        when(sink.write(any())).thenReturn(Mono.empty());
        when(sink.flush()).thenReturn(Mono.empty());
        var workItem = new WorkItem("idx", 2, 42L);
        var committed = RfsMigrateDocuments.committedMetricsReporter(sink, "session", "worker", "snapshot", null, null)
            .apply(workItem, new WorkItemCursor(43, MigrationMetrics.ZERO, true, null));
        var captured = ArgumentCaptor.forClass(WorkItemMetricsRecord.class);
        verify(sink).write(captured.capture());
        var record = captured.getValue();
        assertEquals("session", record.getSessionId());
        assertEquals("worker", record.getWorkerId());
        assertEquals(workItem.toString(), record.getWorkItemId());
        assertNull(record.getLiveLuceneDocCount());
        assertEquals("snapshot", record.getSnapshotName());
        assertEquals(record.getCommitId(), committed.get("commitId"));
        assertEquals(42L, ((Number) committed.get("fromPosition")).longValue());
        assertEquals(43L, ((Number) committed.get("nextPosition")).longValue());
        assertTrue(record.isShardComplete());
        assertEquals(MigrationMetrics.ZERO, record.getMetrics());
    }

    @Test
    void flushRunsAfterWriteAndFailurePropagates() {
        var sink = mock(WorkItemMetricsSink.class);
        var written = new AtomicBoolean();
        when(sink.write(any())).thenReturn(Mono.fromRunnable(() -> written.set(true)));
        when(sink.flush()).thenAnswer(call -> {
            assertTrue(written.get());
            return Mono.error(new IOException("upload failed"));
        });
        assertThrows(Exception.class, () -> RfsMigrateDocuments.committedMetricsReporter(sink, "s", "w", "snapshot", null, null)
            .apply(new WorkItem("idx", 0, 0L), new WorkItemCursor(0, MigrationMetrics.ZERO, true, 0L)));
    }
}
