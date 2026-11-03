package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BulkRequestTraceTest {
    private static final String RESPONSE = "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"a\",\"result\":\"created\"}}]}";
    private RestClient restClient;
    private OpenSearchClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        var connectionContext = mock(ConnectionContext.class);
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        client = new OpenSearchClient_OS_2_11(restClient, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, 100, 10_000_000) {
            @Override
            protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                return Retry.fixedDelay(1, Duration.ofMillis(1));
            }
        };
    }

    @AfterEach
    void turnTracingOff() {
        Configurator.setLevel(OpenSearchClient.BULK_REQUEST_TRACE_LOGGER, Level.OFF);
    }

    /** Capture the trace logger's output with tracing on. */
    @com.google.errorprone.annotations.MustBeClosed
    private static CloseableLogSetup tracing() {
        Configurator.setLevel(OpenSearchClient.BULK_REQUEST_TRACE_LOGGER, Level.TRACE);
        return new CloseableLogSetup(OpenSearchClient.BULK_REQUEST_TRACE_LOGGER);
    }

    private void send() {
        client.sendBulkRequestRaw("idx", List.of(new Document("a", "{\"secret\":\"value\"}".getBytes(StandardCharsets.UTF_8),
            Document.Operation.UPSERT, Map.of(), Map.of())), null, false, DocumentExceptionAllowlist.empty()).block();
    }

    @Test
    void nothingIsFormattedWhileTracingIsOff() {
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "OK", Map.of(), RESPONSE)));
        Configurator.setLevel(OpenSearchClient.BULK_REQUEST_TRACE_LOGGER, Level.OFF);
        long before = OpenSearchClient.bulkTraceBodiesFormatted.get();

        send();

        assertEquals(before, OpenSearchClient.bulkTraceBodiesFormatted.get());
    }

    @Test
    void requestsAndResponsesAreLoggedWhenTracingIsOn() {
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "OK", Map.of(), RESPONSE)));
        try (var logs = tracing()) {
            send();

            assertThat(logs.getLogEvents(), hasSize(2));
            var request = logs.getLogEvents().get(0);
            assertThat(request, containsString("Bulk request attempt 1 to index 'idx': 1 operations"));
            assertThat(request, containsString("{\"secret\":\"value\"}"));
            var response = logs.getLogEvents().get(1);
            assertThat(response, containsString("Bulk response to attempt 1 for index 'idx': status 200"));
            assertThat(response, containsString("\"result\":\"created\""));
        }
    }

    @Test
    void aTransportErrorIsLoggedWithItsExceptionForEveryAttempt() {
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.error(new IOException("Connection reset by peer")));
        try (var logs = tracing()) {
            assertThrows(RuntimeException.class, this::send);

            var errors = logs.getLogEvents().stream().filter(line -> line.contains("got no response")).toList();
            assertThat(String.valueOf(logs.getLogEvents()), errors, hasSize(2));
            assertThat(errors.get(1), containsString("attempt 2"));
            assertThat(errors.get(1), containsString("java.io.IOException: Connection reset by peer"));
        }
    }

    @Test
    void longBodiesAreCutAtTheConfiguredLimit() {
        var body = new byte[OpenSearchClient.BULK_TRACE_MAX_BYTES + 10];
        java.util.Arrays.fill(body, (byte) 'x');

        var traced = OpenSearchClient.traceBody(body);

        assertThat(traced, endsWith("... [10 more bytes not logged]"));
        assertEquals(OpenSearchClient.BULK_TRACE_MAX_BYTES + "... [10 more bytes not logged]".length(), traced.length());
    }

    @Test
    void theLimitComesFromTheEnvironmentWithASafeDefault() {
        assertEquals(1024 * 1024, OpenSearchClient.parseTraceMaxBytes(null));
        assertEquals(1024 * 1024, OpenSearchClient.parseTraceMaxBytes("not-a-number"));
        assertEquals(0, OpenSearchClient.parseTraceMaxBytes("0"));
        assertEquals(500, OpenSearchClient.parseTraceMaxBytes(" 500 "));
    }
}
