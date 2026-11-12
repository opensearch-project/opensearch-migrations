package org.opensearch.migrations.bulkload.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.testutils.CloseableLogSetup;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises request-level throttling over a real HTTP connection and the RFS bulk retry loop. */
class OpenSearchClientHttpRetryTest {
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void plainTextThrottlingOverHttpPreservesPendingDocuments(boolean recover, boolean raw) throws Exception {
        var requests = Collections.synchronizedList(new ArrayList<String>());
        var paths = Collections.synchronizedList(new ArrayList<String>());
        var failedRequests = mock(FailedRequestsLogger.class);
        var failedDocuments = mock(FailedDocumentStreamSink.class);
        when(failedDocuments.write(any())).thenReturn(Mono.empty());

        try (var server = SimpleNettyHttpServer.makeNettyServer(false, request -> {
            requests.add(request.content().toString(StandardCharsets.UTF_8));
            paths.add(request.uri());
            boolean success = recover && requests.size() == 3;
            var body = (success ? "{\"errors\":false,\"items\":[]}" : "Too Many Requests")
                .getBytes(StandardCharsets.UTF_8);
            return new SimpleHttpResponse(
                Map.of("Content-Type", success ? "application/json" : "text/plain",
                    "Content-Length", Integer.toString(body.length)),
                body, success ? "OK" : "Too Many Requests", success ? 200 : 429);
        }); var logs = new CloseableLogSetup(BulkResponseParser.class.getName())) {
            var restClient = new RestClient(ConnectionContextTestParams.builder()
                .host("http://localhost:" + server.port).build().toConnectionContext());
            var client = spy(new OpenSearchClient_OS_2_11(restClient, failedRequests,
                Version.fromString("OS 3.7"), CompressionMode.UNCOMPRESSED));
            doReturn(Retry.fixedDelay(2, Duration.ofMillis(1))).when(client).getBulkRetryStrategy();
            client.setFailedDocumentStreamContext(failedDocuments, "test-session", "test-worker");
            var doc = IndexOp.builder().operation(IndexOperationMeta.builder().id("doc-a").build())
                .document(Map.of("value", "test")).build();
            var rawDoc = new Document("doc-a", "{\"value\":\"test\"}".getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(), Map.of());
            var result = raw
                ? client.sendBulkRequestRaw("test-index", List.of(rawDoc), null, false, DocumentExceptionAllowlist.empty())
                : client.sendBulkRequest("test-index", List.of(doc), null);

            if (recover) {
                var response = result.block(Duration.ofSeconds(10));
                assertNotNull(response);
                assertEquals(200, response.statusCode);
                verifyNoInteractions(failedRequests, failedDocuments);
            } else {
                var error = assertThrows(RuntimeException.class, () -> result.block(Duration.ofSeconds(10)));
                assertTrue(error.getCause() instanceof OpenSearchClient.OperationFailed);
                var failure = (OpenSearchClient.OperationFailed) error.getCause();
                assertEquals(429, failure.response.statusCode);
                assertEquals("Too Many Requests", failure.response.body);
                verify(failedRequests).logBulkFailure(any(), any(), any(), any());
                var record = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
                verify(failedDocuments, times(1)).write(record.capture());
                assertEquals("doc-a", record.getValue().getDocumentId());
                assertEquals(FailureClass.RETRYABLE_EXHAUSTED, record.getValue().getFailureClass());
            }
            assertEquals(3, requests.size());
            assertEquals(List.of("/test-index/_bulk", "/test-index/_bulk", "/test-index/_bulk"), paths);
            assertEquals(requests.get(0), requests.get(1));
            assertEquals(requests.get(1), requests.get(2));
            assertTrue(requests.get(0).contains("doc-a"));
            assertTrue(logs.getLogEvents().isEmpty(), () -> "Unexpected item parsing: " + logs.getLogEvents());
        }
    }
}
