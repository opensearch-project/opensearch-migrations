package org.opensearch.migrations.bulkload.pipeline;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.RfsException;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.SyntheticDocumentSource;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.S3FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage of the per-batch failed document stream flush gating in
 * {@link DocumentMigrationBootstrap#runPartitionMigration}: a real
 * {@link DocumentMigrationPipeline} drives a synthetic document source through a real
 * {@link OpenSearchClient} (backed by a mock {@code RestClient} that fails one document),
 * with a real {@link S3FailedDocumentStreamSink} whose S3 upload outcome we control.
 *
 * <p>Contract under test (at-least-once for failed document stream drops): the progress cursor — which becomes
 * the work-coordination checkpoint — must only advance after the batch's terminal failures
 * are durably persisted. If the per-batch flush fails, the work item must NOT be marked
 * complete (here: {@code runPartitionMigration} throws and the cursor never advances), so a
 * successor reprocesses and re-emits.
 */
class DocumentMigrationBootstrapFailedDocumentStreamE2ETest {

    private static final String INDEX = "movies";

    /** Bulk response with a single non-retryable failure → exactly one failed document stream record produced. */
    private static OpenSearchClient clientThatFailsOneDoc() {
        var restClient = mock(RestClient.class);
        var connectionContext = mock(ConnectionContext.class);
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        var response = "{\"took\":1,\"errors\":true,\"items\":[{\"index\":{\"_index\":\"" + INDEX
            + "\",\"_id\":\"bad-doc\",\"status\":400,\"error\":{\"type\":\"mapper_parsing_exception\","
            + "\"reason\":\"bad\"}}}]}";
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, response)));
        return new OpenSearchClient_OS_2_11(restClient, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED);
    }

    private static IWorkCoordinator.WorkItemAndDuration workItem() {
        return new IWorkCoordinator.WorkItemAndDuration(
            Instant.now().plusSeconds(60),
            new IWorkCoordinator.WorkItemAndDuration.WorkItem(INDEX, 0, 0L));
    }

    private record Harness(DocumentMigrationBootstrap bootstrap,
                           PipelineConfig pipelineConfig,
                           IDocumentMigrationContexts.IDocumentReindexContext context,
                           AtomicReference<WorkItemCursor> cursorRef) {}

    private static Harness harness(OpenSearchClient client) {
        var source = new SyntheticDocumentSource(INDEX, 1, 1);   // 1 partition, 1 doc
        var ctx = DocumentMigrationTestContext.factory().noOtelTracking().createReindexContext();
        var sink = new OpenSearchDocumentSink(client, null, false,
            DocumentExceptionAllowlist.empty(), ctx::createBulkRequest);
        var pipelineConfig = new PipelineConfig(source, sink, 1000, Long.MAX_VALUE, 1);
        var cursorRef = new AtomicReference<WorkItemCursor>();
        var bootstrap = DocumentMigrationBootstrap.builder()
            .documentSource(source)
            .targetClient(client)
            .maxDocsPerBatch(1000)
            .maxBytesPerBatch(Long.MAX_VALUE)
            .batchConcurrency(1)
            .cursorConsumer(cursorRef::set)
            .build();
        return new Harness(bootstrap, pipelineConfig, ctx, cursorRef);
    }

    @Test
    void perBatchFlushFailureAbortsWorkItemAndDoesNotAdvanceProgress() {
        var client = clientThatFailsOneDoc();
        // Real sink whose S3 upload throws — the per-batch flush of the (one) buffered failed document stream
        // record therefore fails.
        var failedDocumentStreamSink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> {
                throw new IOException("S3 unavailable");
            })
            .build();
        client.setFailedDocumentStreamContext(failedDocumentStreamSink, "s", "w");
        var h = harness(client);

        var thrown = assertThrows(RfsException.class,
            () -> h.bootstrap().runPartitionMigration(workItem(), h.pipelineConfig(), h.context()));

        // The failure propagated, so the caller (migrateOneShard) never marks the work item
        // complete — and the progress cursor was never advanced for the failed batch.
        assertThat(thrown.getMessage(), is("Partition migration failed for " + workItem().getWorkItem()));
        assertThat(h.cursorRef().get(), nullValue());
    }

    @Test
    void perBatchFlushSuccessCommitsProgressAndPersistsFailedDocumentStream() {
        var client = clientThatFailsOneDoc();
        var uploaded = new CopyOnWriteArrayList<String>();
        var failedDocumentStreamSink = S3FailedDocumentStreamSink.builder()
            .bucket("b").prefix("rfs-failed-document-stream/").sessionId("s").workerId("w").region("r")
            .uploader((uri, data, region) -> uploaded.add(uri))
            .build();
        client.setFailedDocumentStreamContext(failedDocumentStreamSink, "s", "w");
        var h = harness(client);

        var status = h.bootstrap().runPartitionMigration(workItem(), h.pipelineConfig(), h.context());

        // Work item completed, progress advanced to the one processed doc, and the failed document stream record
        // was durably uploaded by the per-batch flush.
        assertThat(status, is(CompletionStatus.WORK_COMPLETED));
        assertThat(h.cursorRef().get().getProgressCheckpointNum(), equalTo(1L));
        assertThat(uploaded, hasSize(1));
    }

    @Test
    void noFailedDocumentStreamConfigured_completesNormally() {
        // Sanity: with no failed document stream sink installed (getFailedDocumentStreamSink() == null), the per-batch flush is a
        // no-op and the shard completes as before.
        var client = clientThatFailsOneDoc();
        var h = harness(client);

        var status = h.bootstrap().runPartitionMigration(workItem(), h.pipelineConfig(), h.context());

        assertThat(status, is(CompletionStatus.WORK_COMPLETED));
        assertThat(h.cursorRef().get().getProgressCheckpointNum(), equalTo(1L));
    }
}
