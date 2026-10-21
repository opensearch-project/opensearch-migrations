package org.opensearch.migrations;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator.WorkItemAndDuration.WorkItem;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.metrics.WorkItemMetricsRecord;
import org.opensearch.migrations.reindexer.metrics.WorkItemMetricsSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void flushFailurePreventsHandoffAndShutdownCompletion(boolean shardComplete) {
        var sink = mock(WorkItemMetricsSink.class);
        var written = new AtomicBoolean();
        var failure = new IOException("upload failed");
        when(sink.write(any())).thenReturn(Mono.fromRunnable(() -> written.set(true)));
        when(sink.flush()).thenAnswer(call -> {
            assertTrue(written.get());
            return Mono.error(failure);
        });
        var coordinator = mock(IWorkCoordinator.class);
        var work = new WorkItemAndDuration(Instant.now().plusSeconds(60), new WorkItem("idx", 0, 0L));
        var reporter = RfsMigrateDocuments.committedMetricsReporter(sink, "s", "w", "snapshot", null, null);
        var error = assertThrows(RuntimeException.class, () -> RfsMigrateDocuments.commitProgress(coordinator, work,
            new WorkItemCursor(1, MigrationMetrics.ZERO, shardComplete, 0L), 1, null, () -> null, reporter));
        assertSame(failure, Exceptions.unwrap(error));
        verifyNoInteractions(coordinator);
    }

    @Test
    void flushFailurePreventsNormalCompletion() {
        var sink = mock(WorkItemMetricsSink.class);
        var failure = new IOException("upload failed");
        when(sink.write(any())).thenReturn(Mono.empty());
        when(sink.flush()).thenReturn(Mono.error(failure));
        var coordinator = mock(IWorkCoordinator.class);
        var scoped = new ScopedWorkCoordinator(coordinator, mock(LeaseExpireTrigger.class));
        var work = new WorkItemAndDuration(Instant.now().plusSeconds(60), new WorkItem("idx", 0, 0L));
        var reporter = RfsMigrateDocuments.committedMetricsReporter(sink, "s", "w", "snapshot", null, null);
        IWorkCoordinator.WorkAcquisitionOutcomeVisitor<Void> visitor =
            mock(IWorkCoordinator.WorkAcquisitionOutcomeVisitor.class);
        var error = assertThrows(RuntimeException.class, () -> scoped.ensurePhaseCompletion(wc -> work, visitor, () -> null,
            wi -> reporter.apply(wi, new WorkItemCursor(0, MigrationMetrics.ZERO, true, 0L))));
        assertSame(failure, Exceptions.unwrap(error));
        verifyNoInteractions(coordinator);
    }
}
