package org.opensearch.migrations.bulkload.pipeline;

import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;

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
    void flushFailedDocumentStreamForBatch_nullSinkIsANoOp() {
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushFailedDocumentStreamForBatch(null));
    }

    @Test
    void flushFailedDocumentStreamForBatch_successReturnsNormally() {
        var sink = mock(FailedDocumentStreamSink.class);
        when(sink.flush()).thenReturn(Mono.empty());
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushFailedDocumentStreamForBatch(sink));
    }

    @Test
    void flushFailedDocumentStreamForBatch_failurePropagatesSoProgressIsNotCommitted() {
        // The exception must propagate (not be swallowed): the pipeline onNext lets it reach
        // the error consumer, so the progress cursor is NOT advanced for this batch and the
        // work item is never marked complete — a successor reprocesses and re-emits.
        var cause = new RuntimeException("S3 5xx");
        var sink = mock(FailedDocumentStreamSink.class);
        when(sink.flush()).thenReturn(Mono.error(cause));

        var thrown = assertThrows(RuntimeException.class,
            () -> DocumentMigrationBootstrap.flushFailedDocumentStreamForBatch(sink));
        assertThat(thrown, is(cause));
    }
}
