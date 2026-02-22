package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.workcoordination.LeaseExpireTrigger;
import org.opensearch.migrations.bulkload.workcoordination.ScopedWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipelineDocumentsRunnerTest {

    @Mock private IWorkCoordinator workCoordinator;
    @Mock private LeaseExpireTrigger leaseExpireTrigger;
    @Mock private DocumentSource source;
    @Mock private DocumentSink sink;

    @Test
    void migrateNextShardAcquiresWorkAndProcesses() throws Exception {
        var workItem = new IWorkCoordinator.WorkItemAndDuration(
            Instant.now().plusSeconds(600),
            new IWorkCoordinator.WorkItemAndDuration.WorkItem("test-index", 0, 0)
        );
        when(workCoordinator.acquireNextWorkItem(any(), any()))
            .thenReturn(workItem);
        doNothing().when(workCoordinator).completeWorkItem(anyString(), any());

        var shardId = new ShardId("snap", "test-index", 0);
        var doc = new DocumentChange("d1", null, "{\"f\":1}".getBytes(), null, DocumentChange.ChangeType.INDEX);
        when(source.readDocuments(any(ShardId.class), eq(0)))
            .thenReturn(Flux.just(doc));
        when(sink.writeBatch(any(), eq("test-index"), anyList()))
            .thenReturn(Mono.just(new ProgressCursor(shardId, 1, 1, 7)));

        var cursors = new ArrayList<WorkItemCursor>();
        var cancellationRef = new AtomicReference<Runnable>();

        var scopedCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        var runner = new PipelineDocumentsRunner(
            scopedCoordinator,
            Duration.ofMinutes(10),
            source,
            sink,
            1000,
            10_000_000L,
            "snap",
            cursors::add,
            cancellationRef::set
        );

        var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        var status = runner.migrateNextShard(testContext::createReindexContext);

        assertEquals(PipelineDocumentsRunner.CompletionStatus.WORK_COMPLETED, status);
        assertFalse(cursors.isEmpty());
        assertEquals(1, cursors.get(0).getProgressCheckpointNum());
        assertNotNull(cancellationRef.get());
    }

    @Test
    void migrateNextShardReturnsNothingDoneWhenNoWork() throws Exception {
        when(workCoordinator.acquireNextWorkItem(any(), any()))
            .thenReturn(new IWorkCoordinator.NoAvailableWorkToBeDone());

        var scopedCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        var runner = new PipelineDocumentsRunner(
            scopedCoordinator,
            Duration.ofMinutes(10),
            source,
            sink,
            1000,
            10_000_000L,
            "snap",
            cursor -> {},
            runnable -> {}
        );

        var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        var status = runner.migrateNextShard(testContext::createReindexContext);

        assertEquals(PipelineDocumentsRunner.CompletionStatus.NOTHING_DONE, status);
        verifyNoInteractions(source, sink);
    }

    @Test
    void migrateNextShardReturnsNothingDoneWhenAlreadyCompleted() throws Exception {
        when(workCoordinator.acquireNextWorkItem(any(), any()))
            .thenReturn(new IWorkCoordinator.AlreadyCompleted());

        var scopedCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        var runner = new PipelineDocumentsRunner(
            scopedCoordinator,
            Duration.ofMinutes(10),
            source,
            sink,
            1000,
            10_000_000L,
            "snap",
            cursor -> {},
            runnable -> {}
        );

        var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        var status = runner.migrateNextShard(testContext::createReindexContext);

        assertEquals(PipelineDocumentsRunner.CompletionStatus.NOTHING_DONE, status);
    }

    @Test
    void migrateNextShardUsesStartingDocIdFromWorkItem() throws Exception {
        var workItem = new IWorkCoordinator.WorkItemAndDuration(
            Instant.now().plusSeconds(600),
            new IWorkCoordinator.WorkItemAndDuration.WorkItem("test-index", 0, 500)
        );
        when(workCoordinator.acquireNextWorkItem(any(), any()))
            .thenReturn(workItem);
        doNothing().when(workCoordinator).completeWorkItem(anyString(), any());

        var shardId = new ShardId("snap", "test-index", 0);
        var doc = new DocumentChange("d1", null, "{\"f\":1}".getBytes(), null, DocumentChange.ChangeType.INDEX);
        // Verify that readDocuments is called with startingDocOffset=500
        when(source.readDocuments(any(ShardId.class), eq(500)))
            .thenReturn(Flux.just(doc));
        when(sink.writeBatch(any(), eq("test-index"), anyList()))
            .thenReturn(Mono.just(new ProgressCursor(shardId, 501, 1, 7)));

        var cursors = new ArrayList<WorkItemCursor>();
        var scopedCoordinator = new ScopedWorkCoordinator(workCoordinator, leaseExpireTrigger);
        var runner = new PipelineDocumentsRunner(
            scopedCoordinator,
            Duration.ofMinutes(10),
            source,
            sink,
            1000,
            10_000_000L,
            "snap",
            cursors::add,
            runnable -> {}
        );

        var testContext = DocumentMigrationTestContext.factory().noOtelTracking();
        runner.migrateNextShard(testContext::createReindexContext);

        // Verify source was called with the correct starting offset
        verify(source).readDocuments(any(ShardId.class), eq(500));
        // Progress cursor should reflect cumulative offset (500 + 1 = 501)
        assertEquals(501, cursors.get(0).getProgressCheckpointNum());
    }
}
