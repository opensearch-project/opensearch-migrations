package org.opensearch.migrations.bulkload.pipeline;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.CollectionRoutedTarget;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.RestClient;
import org.opensearch.migrations.bulkload.common.ServerlessCollectionRouting;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.adapter.OpenSearchDocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.SyntheticDocumentSource;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.bulkload.workcoordination.IWorkCoordinator;
import org.opensearch.migrations.bulkload.worker.CompletionStatus;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.tracing.DocumentMigrationTestContext;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentMigrationBootstrapCollectionRoutingTest {
    private static final String INDEX = "tenant-a-2024";

    @Test
    @SuppressWarnings("unchecked")
    void routedWorkItem_probesOnCallingThreadAndSendsCollectionHeader() {
        var restClient = mock(RestClient.class);
        var connectionContext = mock(ConnectionContext.class);
        when(connectionContext.getUri()).thenReturn(URI.create("https://123456789012.aoss.us-east-1.on.aws/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        var response = "{\"took\":1,\"errors\":false,\"items\":[]}";
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, response)));
        var client = new OpenSearchClient_OS_2_11(restClient, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED);

        var routing = ServerlessCollectionRouting.fromJson(
            "{\"regexCollectionRouting\": [{\"sourceIndex\": \"(.+)-\\\\d{4}\", \"collection\": \"$1\"}]}").orElseThrow();
        var probeThreads = new CopyOnWriteArrayList<Thread>();
        var routedTarget = new CollectionRoutedTarget(routing, collection -> {
            probeThreads.add(Thread.currentThread());
            return false;
        });

        var source = new SyntheticDocumentSource(INDEX, 1, 1);
        var ctx = DocumentMigrationTestContext.factory().noOtelTracking().createReindexContext();
        var sink = new OpenSearchDocumentSink(client, null, false,
            DocumentExceptionAllowlist.empty(), ctx::createBulkRequest, routedTarget);
        var bootstrap = DocumentMigrationBootstrap.builder()
            .documentSource(source)
            .targetClient(client)
            .maxDocsPerBatch(1000)
            .maxBytesPerBatch(Long.MAX_VALUE)
            .batchConcurrency(1)
            .collectionRoutedTarget(routedTarget)
            .build();
        var workItem = new IWorkCoordinator.WorkItemAndDuration(
            Instant.now().plusSeconds(60),
            new IWorkCoordinator.WorkItemAndDuration.WorkItem(INDEX, 0, 0L));

        var status = bootstrap.runPartitionMigration(workItem,
            new PipelineConfig(source, sink, 1000, Long.MAX_VALUE, 1), ctx);

        assertThat(status, is(CompletionStatus.WORK_COMPLETED));
        // The probe may block, so it has to run before the reactive pipeline starts
        assertThat(probeThreads, equalTo(List.of(Thread.currentThread())));
        ArgumentCaptor<Map<String, List<String>>> headers = ArgumentCaptor.forClass(Map.class);
        verify(restClient).postAsyncBytes(eq(INDEX + "/_bulk"), any(), headers.capture(), any());
        assertThat(headers.getValue().get(ServerlessCollectionRouting.COLLECTION_NAME_HEADER), equalTo(List.of("tenant-a")));
    }
}
