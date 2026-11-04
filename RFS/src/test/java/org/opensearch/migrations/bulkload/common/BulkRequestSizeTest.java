package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.GzipPayloadRequestTransformer;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Each bulk attempt reports its documents and its size before and after gzip, to the metrics and the trace. */
class BulkRequestSizeTest {
    private record Body(int documents, long bytes, long sentBytes) {}

    @AfterEach
    void turnTracingOff() {
        Configurator.setLevel(OpenSearchClient.BULK_REQUEST_TRACE_LOGGER, Level.OFF);
    }

    @Test
    void aRequestWaitingForASlotIsNotCountedAsSent() {
        var restClient = mock(RestClient.class);
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connection);
        // The first request never answers, so with one slot the second can only wait.
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.never());
        var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(), Version.fromString("OS 2.11"),
            CompressionMode.UNCOMPRESSED, 100, 10_000_000);
        client.setBulkInFlightLimit(new AdaptiveLimit(1));
        var bodies = new ArrayList<Body>();
        client.setBulkFlowMetrics(new BulkFlowMetrics() {
            @Override
            public void onRequestBody(int documents, long bytes, long sentBytes) {
                bodies.add(new Body(documents, bytes, sentBytes));
            }
        });
        var source = "{}".getBytes(StandardCharsets.UTF_8);
        for (var id : List.of("a", "b")) {
            client.sendBulkRequestRaw("idx", List.of(new Document(id, source, Document.Operation.UPSERT, Map.of(),
                Map.of())), null, false, DocumentExceptionAllowlist.empty()).subscribe();
        }
        assertEquals(1, bodies.size(), bodies.toString());
    }

    @Test
    void aRequestWaitingForASlotIsNotAssembledOrCompressed() {
        var restClient = mock(RestClient.class);
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connection);
        // Hold the only adaptive slot so the second request remains queued.
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.never());
        var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(), Version.fromString("OS 2.11"),
            CompressionMode.GZIP_BODY_COMPRESSION, 100, 10_000_000);
        client.setBulkInFlightLimit(new AdaptiveLimit(1));
        var source = ("{\"field\":\"" + "x".repeat(2000) + "\"}").getBytes(StandardCharsets.UTF_8);
        var subscriptions = new ArrayList<reactor.core.Disposable>();

        try (MockedStatic<BulkNdjson> ndjson = mockStatic(BulkNdjson.class, CALLS_REAL_METHODS);
             MockedStatic<GzipPayloadRequestTransformer> gzip =
                 mockStatic(GzipPayloadRequestTransformer.class, CALLS_REAL_METHODS)) {
            for (var id : List.of("a", "b")) {
                subscriptions.add(client.sendBulkRequestRaw("idx",
                    List.of(new Document(id, source, Document.Operation.UPSERT, Map.of(), Map.of())),
                    null, false, DocumentExceptionAllowlist.empty()).subscribe());
            }

            ndjson.verify(() -> BulkNdjson.assemblePooled(anyList(), any()), times(1));
            gzip.verify(() -> GzipPayloadRequestTransformer.gzip(any(ByteBuffer[].class)), times(1));
        } finally {
            subscriptions.forEach(reactor.core.Disposable::dispose);
        }
    }

    @ParameterizedTest
    @EnumSource(value = CompressionMode.class, names = {"UNCOMPRESSED", "GZIP_BODY_COMPRESSION"})
    void sizesAreReportedForWhatIsSent(CompressionMode mode) throws Exception {
        var restClient = mock(RestClient.class);
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connection);
        var ids = List.of("a", "b", "c");
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(new HttpResponse(200, "OK",
            Map.of(), BulkRequestGenerator.bulkItemResponse(false, ids.stream().map(BulkRequestGenerator::itemEntry)
                .toList()))));
        var client = new OpenSearchClient_OS_2_11(restClient, new FailedRequestsLogger(), Version.fromString("OS 2.11"),
            mode, 100, 10_000_000);
        var bodies = new ArrayList<Body>();
        client.setBulkFlowMetrics(new BulkFlowMetrics() {
            @Override
            public void onRequestBody(int documents, long bytes, long sentBytes) {
                bodies.add(new Body(documents, bytes, sentBytes));
            }
        });
        // Repetitive enough that gzip shrinks it.
        var source = ("{\"field\":\"" + "x".repeat(2000) + "\"}").getBytes(StandardCharsets.UTF_8);

        Configurator.setLevel(OpenSearchClient.BULK_REQUEST_TRACE_LOGGER, Level.TRACE);
        try (var trace = new CloseableLogSetup(OpenSearchClient.BULK_REQUEST_TRACE_LOGGER)) {
            client.sendBulkRequestRaw("idx", ids.stream().map(id -> new Document(id, source, Document.Operation.UPSERT,
                Map.of(), Map.of())).toList(), null, false, DocumentExceptionAllowlist.empty()).block();

            var sent = ArgumentCaptor.forClass(byte[].class);
            verify(restClient).postAsyncBytes(any(), sent.capture(), any(), any());
            assertEquals(1, bodies.size(), bodies.toString());
            var body = bodies.get(0);
            assertEquals(3, body.documents());
            assertEquals(sent.getValue().length, body.sentBytes());
            byte[] ndjson = mode == CompressionMode.GZIP_BODY_COMPRESSION
                ? new GZIPInputStream(new ByteArrayInputStream(sent.getValue())).readAllBytes()
                : sent.getValue();
            assertEquals(ndjson.length, body.bytes());
            if (mode == CompressionMode.GZIP_BODY_COMPRESSION) {
                assertTrue(body.sentBytes() < body.bytes() / 10, body.toString());
            } else {
                assertEquals(body.bytes(), body.sentBytes());
            }

            var request = trace.getLogEvents().get(0);
            assertTrue(request.contains("3 operations, " + body.bytes() + " bytes, " + body.sentBytes() + " bytes sent "
                + (mode == CompressionMode.GZIP_BODY_COMPRESSION ? "gzipped" : "uncompressed")), request);
        }
    }
}
