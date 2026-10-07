package org.opensearch.migrations.bulkload.pipeline;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
import org.opensearch.migrations.bulkload.workcoordination.WorkItemDocCounts;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.bulkload.worker.WorkItemCursor;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;
import org.opensearch.migrations.reindexer.tracing.IDocumentMigrationContexts;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentMigrationBootstrapDocCountsTest {

    private static final String INDEX = "movies";

    private static OpenSearchClient clientReturning(String... bulkResponses) {
        var restClient = mock(RestClient.class);
        var connectionContext = mock(ConnectionContext.class);
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        var stubbing = when(restClient.postAsyncBytes(any(), any(), any(), any()));
        for (var body : bulkResponses) {
            stubbing = stubbing.thenReturn(Mono.just(new HttpResponse(200, "", null, body)));
        }
        return new OpenSearchClient_OS_2_11(restClient, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED) {
            @Override
            protected Retry getBulkRetryStrategy() {
                return Retry.fixedDelay(2, Duration.ofMillis(1));
            }
        };
    }

    private static String ok(String id) {
        return "{\"index\":{\"_index\":\"" + INDEX + "\",\"_id\":\"" + id + "\",\"status\":201,\"result\":\"created\"}}";
    }

    private static String failed(String id, String type, int status) {
        return "{\"index\":{\"_index\":\"" + INDEX + "\",\"_id\":\"" + id + "\",\"status\":" + status
            + ",\"error\":{\"type\":\"" + type + "\",\"reason\":\"r\"}}}";
    }

    private static String bulkResponse(boolean errors, String... items) {
        return "{\"took\":1,\"errors\":" + errors + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private static IWorkCoordinator.WorkItemAndDuration workItem() {
        return new IWorkCoordinator.WorkItemAndDuration(
            Instant.now().plusSeconds(60),
            new IWorkCoordinator.WorkItemAndDuration.WorkItem(INDEX, 0, 0L));
    }

    private record Harness(DocumentMigrationBootstrap bootstrap,
                           PipelineConfig pipelineConfig,
                           DocumentMigrationTestContext rootContext,
                           IDocumentMigrationContexts.IDocumentReindexContext context,
                           AtomicReference<WorkItemCursor> cursorRef) {}

    private static Harness harness(OpenSearchClient client, int numDocs, int maxDocsPerBatch) {
        var source = new SyntheticDocumentSource(INDEX, 1, numDocs);
        var rootContext = DocumentMigrationTestContext.factory().withAllTracking();
        var ctx = rootContext.createReindexContext();
        var sink = new OpenSearchDocumentSink(client, null, false,
            DocumentExceptionAllowlist.empty(), ctx::createBulkRequest);
        var pipelineConfig = new PipelineConfig(source, sink, maxDocsPerBatch, Long.MAX_VALUE, 1);
        var cursorRef = new AtomicReference<WorkItemCursor>();
        var bootstrap = DocumentMigrationBootstrap.builder()
            .documentSource(source)
            .targetClient(client)
            .maxDocsPerBatch(maxDocsPerBatch)
            .maxBytesPerBatch(Long.MAX_VALUE)
            .batchConcurrency(1)
            .cursorConsumer(cursorRef::set)
            .build();
        return new Harness(bootstrap, pipelineConfig, rootContext, ctx, cursorRef);
    }

    private static long sumOf(Harness h, String metricName) {
        return points(h, metricName).stream().mapToLong(LongPointData::getValue).sum();
    }

    private static List<LongPointData> points(Harness h, String metricName) {
        return h.rootContext().inMemoryInstrumentationBundle.getFinishedMetrics().stream()
            .filter(md -> md.getName().equals(metricName))
            .map(MetricData::getLongSumData)
            .flatMap(d -> d.getPoints().stream())
            .collect(Collectors.toList());
    }

    @Test
    void countsSucceededAndFailedPerCommittedBatch() {
        // Two batches of 2 docs. Batch 1: one success, one mapper failure.
        // Batch 2: one success, one retryable rejection that succeeds on retry.
        var client = clientReturning(
            bulkResponse(true, ok("a"), failed("b", "mapper_parsing_exception", 400)),
            bulkResponse(true, ok("c"), failed("d", "es_rejected_execution_exception", 429)),
            bulkResponse(false, ok("d")));
        var h = harness(client, 4, 2);

        var status = h.bootstrap().runPartitionMigration(workItem(), h.pipelineConfig(), h.context());

        assertThat(status, equalTo(CompletionStatus.WORK_COMPLETED));
        assertThat(h.cursorRef().get().getProgressCheckpointNum(), equalTo(4L));
        assertThat(h.cursorRef().get().getDocCounts(), equalTo(new WorkItemDocCounts(3, 1)));

        assertThat(sumOf(h, IDocumentMigrationContexts.MetricNames.DOCS_SUCCEEDED), equalTo(3L));
        var failedPoints = points(h, IDocumentMigrationContexts.MetricNames.DOCS_FAILED);
        var failureTypeKey = AttributeKey.stringKey(IDocumentMigrationContexts.AttributeNames.FAILURE_TYPE);
        var failedByType = failedPoints.stream().collect(Collectors.toMap(
            p -> p.getAttributes().get(failureTypeKey), LongPointData::getValue, Long::sum));
        assertThat(failedByType, equalTo(Map.of("mapper_parsing_exception", 1L)));
    }

    @Test
    void failedBatchRecordsNothing() {
        // Every attempt is rejected: retries exhaust, the batch errors and is never committed, so
        // nothing is counted (a successor lease will re-send it).
        var rejected = bulkResponse(true, failed("a", "es_rejected_execution_exception", 429));
        var responses = new String[20];
        Arrays.fill(responses, rejected);
        var client = clientReturning(responses);
        var h = harness(client, 1, 1);

        assertThrows(RfsException.class,
            () -> h.bootstrap().runPartitionMigration(workItem(), h.pipelineConfig(), h.context()));

        assertThat(h.cursorRef().get(), nullValue());
        assertThat(sumOf(h, IDocumentMigrationContexts.MetricNames.DOCS_SUCCEEDED), equalTo(0L));
        assertThat(sumOf(h, IDocumentMigrationContexts.MetricNames.DOCS_FAILED), equalTo(0L));
    }
}
