package org.opensearch.migrations.bulkload.pipeline;

import org.opensearch.migrations.reindexer.dlq.DlqSink;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentMigrationBootstrapFlushTest {

    @Test
    void flushDlqForBatch_nullSinkIsANoOp() {
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushDlqForBatch(null));
    }

    @Test
    void flushDlqForBatch_successReturnsNormally() {
        var sink = mock(DlqSink.class);
        when(sink.flush()).thenReturn(Mono.empty());
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushDlqForBatch(sink));
    }

    @Test
    void flushDlqForBatch_failurePropagatesSoProgressIsNotCommitted() {
        // The exception must propagate (not be swallowed): the pipeline onNext lets it reach
        // the error consumer, so the progress cursor is NOT advanced for this batch and the
        // work item is never marked complete — a successor reprocesses and re-emits.
        var cause = new RuntimeException("S3 5xx");
        var sink = mock(DlqSink.class);
        when(sink.flush()).thenReturn(Mono.error(cause));

        var thrown = assertThrows(RuntimeException.class,
            () -> DocumentMigrationBootstrap.flushDlqForBatch(sink));
        assertThat(thrown, is(cause));
    }
}
