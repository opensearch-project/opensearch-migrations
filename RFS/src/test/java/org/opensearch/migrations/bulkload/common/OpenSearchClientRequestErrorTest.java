package org.opensearch.migrations.bulkload.common;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class OpenSearchClientRequestErrorTest {
    private final RestClient restClient = mock(RestClient.class);
    private final FailedRequestsLogger failedRequestsLogger = mock(FailedRequestsLogger.class);
    private final FailedDocumentStreamSink failedDocuments = mock(FailedDocumentStreamSink.class);
    private OpenSearchClient client;

    @BeforeEach
    void setUp() {
        var connection = mock(ConnectionContext.class);
        when(connection.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connection);
        client = spy(new OpenSearchClient_OS_2_11(restClient, failedRequestsLogger,
            Version.fromString("OS 3.7"), CompressionMode.UNCOMPRESSED));
        doReturn(Retry.fixedDelay(1, Duration.ofMillis(1))).when(client).getBulkRetryStrategy();
        client.setFailedDocumentStreamContext(failedDocuments, "test-session", "test-worker");
        when(failedDocuments.write(any())).thenReturn(Mono.empty());
    }

    static Stream<Arguments> requestErrors() {
        return Stream.of(
            Arguments.of(429, "Too Many Requests"),
            Arguments.of(502, "<html><body>Bad Gateway</body></html>"),
            Arguments.of(503, ""),
            Arguments.of(429, "{\"error\":\"rejected\",\"status\":429}"),
            // A request-level rejection must not acknowledge documents from an items-like body.
            Arguments.of(429, "{\"errors\":false,\"items\":[]}")
        );
    }

    private static List<IndexOp> documents() {
        return List.of(
            IndexOp.builder().operation(IndexOperationMeta.builder().id("doc-a").build())
                .document(Map.of("value", "a")).build(),
            IndexOp.builder().operation(IndexOperationMeta.builder().id("doc-b").build())
                .document(Map.of("value", "b")).build()
        );
    }

    @ParameterizedTest
    @MethodSource("requestErrors")
    void requestErrorRetriesAllDocumentsThenRecoversWithoutParsingItems(int status, String body) {
        var rejected = new HttpResponse(status, "Rejected", Map.of(), body);
        var success = new HttpResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}");
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(rejected), Mono.just(success));

        try (var logs = new CloseableLogSetup(BulkResponseParser.class.getName())) {
            var result = client.sendBulkRequest("test-index", List.copyOf(documents()), null).block();
            assertNotNull(result);
            assertEquals(200, result.statusCode);
            assertBothAttemptsPreserveDocuments();
            verifyNoInteractions(failedRequestsLogger, failedDocuments);
            assertTrue(logs.getLogEvents().isEmpty(), () -> "Unexpected item parsing: " + logs.getLogEvents());
        }
    }

    @ParameterizedTest
    @MethodSource("requestErrors")
    void requestErrorExhaustionPreservesDocumentsAndHttpFailureWithoutParsingItems(int status, String body) {
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(status, "Rejected", Map.of(), body)));

        try (var logs = new CloseableLogSetup(BulkResponseParser.class.getName())) {
            var error = assertThrows(RuntimeException.class,
                () -> client.sendBulkRequest("test-index", List.copyOf(documents()), null).block());
            assertTrue(error.getCause() instanceof OpenSearchClient.OperationFailed);
            var failure = (OpenSearchClient.OperationFailed) error.getCause();
            assertEquals(status, failure.response.statusCode);
            assertEquals(body, failure.response.body);
            assertBothAttemptsPreserveDocuments();
            verify(failedRequestsLogger).logBulkFailure(any(), any(), any(), any());
            var records = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
            verify(failedDocuments, times(2)).write(records.capture());
            assertEquals(List.of("doc-a", "doc-b"), records.getAllValues().stream()
                .map(FailedDocumentStreamRecord::getDocumentId).toList());
            for (var record : records.getAllValues()) {
                assertEquals(FailureClass.RETRYABLE_EXHAUSTED, record.getFailureClass());
                assertNotNull(record.getRequestItem());
                assertNull(record.getResponseItem());
                assertNull(record.getFailureType());
            }
            assertTrue(logs.getLogEvents().isEmpty(), () -> "Unexpected item parsing: " + logs.getLogEvents());
        }
    }

    private void assertBothAttemptsPreserveDocuments() {
        var bodies = ArgumentCaptor.forClass(byte[].class);
        verify(restClient, times(2)).postAsyncBytes(any(), bodies.capture(), any(), any());
        assertArrayEquals(bodies.getAllValues().get(0), bodies.getAllValues().get(1));
    }
}
