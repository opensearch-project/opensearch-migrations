package org.opensearch.migrations.bulkload.pipeline;

import java.time.Duration;

import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.reindexer.dlq.DlqSink;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link DocumentMigrationBootstrap#flushDlqOrThrow}, the DLQ-flush
 * checkpoint that runs immediately before
 * {@link DocumentMigrationBootstrap#runPartitionMigration} returns
 * WORK_COMPLETED. The contract is: a flush failure MUST throw so the work
 * item is never marked complete, leaving the partition for a successor to
 * re-process and re-emit any terminal failures.
 */
class DocumentMigrationBootstrapFlushTest {

    private static IWorkCoordinator.WorkItemAndDuration.WorkItem workItem() {
        return new IWorkCoordinator.WorkItemAndDuration.WorkItem("movies", 0, 0L);
    }

    @Test
    void nullSinkIsANoOp() {
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushDlqOrThrow(null, workItem()));
    }

    @Test
    void successfulFlushReturnsNormally() {
        var sink = mock(DlqSink.class);
        when(sink.flush()).thenReturn(Mono.empty());
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushDlqOrThrow(sink, workItem()));
    }

    @Test
    void failedFlushIsWrappedInRfsException() {
        var cause = new RuntimeException("S3 5xx");
        var sink = mock(DlqSink.class);
        when(sink.flush()).thenReturn(Mono.error(cause));

        var wi = workItem();
        var thrown = assertThrows(RfsException.class,
            () -> DocumentMigrationBootstrap.flushDlqOrThrow(sink, wi));

        assertThat(thrown.getCause(), is(cause));
        // Message embeds the work item's toString so an operator can correlate
        // the DLQ failure back to a specific partition.
        assertThat(thrown.getMessage(), containsString(wi.toString()));
    }

    @Test
    void flushTimeoutAlsoThrowsRfsException() {
        var sink = mock(DlqSink.class);
        // Stalled flush — mirror what happens when S3 hangs. Use a short
        // reactor timeout so the test doesn't actually wait minutes; the
        // helper treats any thrown exception the same way.
        Mono<Void> stalled = Mono.<Void>never().timeout(Duration.ofMillis(50));
        when(sink.flush()).thenReturn(stalled);

        var thrown = assertThrows(RfsException.class,
            () -> DocumentMigrationBootstrap.flushDlqOrThrow(sink, workItem()));
        assertThat(thrown.getMessage(), containsString("DLQ flush failed"));
    }

    @Test
    void workItemIsIncludedInFailureMessage() {
        var sink = mock(DlqSink.class);
        when(sink.flush()).thenReturn(Mono.error(new RuntimeException("boom")));

        var wi = new IWorkCoordinator.WorkItemAndDuration.WorkItem("books", 3, 100L);
        var thrown = assertThrows(RfsException.class,
            () -> DocumentMigrationBootstrap.flushDlqOrThrow(sink, wi));

        assertThat(thrown.getMessage(), equalTo("DLQ flush failed for " + wi));
    }
}
