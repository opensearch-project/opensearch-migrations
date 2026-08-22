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
        // Empty Mono, completes without emitting.
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushFailedDocumentStreamForBatch(null).block());
    }

    @Test
    void flushFailedDocumentStreamForBatch_successCompletesNormally() {
        var sink = mock(FailedDocumentStreamSink.class);
        when(sink.flush()).thenReturn(Mono.empty());
        assertDoesNotThrow(() -> DocumentMigrationBootstrap.flushFailedDocumentStreamForBatch(sink).block());
    }

    @Test
    void flushFailedDocumentStreamForBatch_failurePropagatesSoProgressIsNotCommitted() {
        // The Mono must error (not swallow): the pipeline routes it to onError, so the progress
        // cursor is NOT advanced for this batch and the work item is never marked complete — a
        // successor reprocesses and re-emits.
        var cause = new RuntimeException("S3 5xx");
        var sink = mock(FailedDocumentStreamSink.class);
        when(sink.flush()).thenReturn(Mono.error(cause));

        var thrown = assertThrows(RuntimeException.class,
            () -> DocumentMigrationBootstrap.flushFailedDocumentStreamForBatch(sink).block());
        assertThat(thrown, is(cause));
    }
}
