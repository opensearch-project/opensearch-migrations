package org.opensearch.migrations.bulkload.common;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenSearchClientOversizedOperationTest {
    private static final long LIMIT = 200;
    private static final String SECRET = "do-not-log-this-value";

    private OpenSearchClient client;
    private IRfsContexts.IRequestContext context;

    @BeforeEach
    void setUp() {
        var restClient = mock(RestClient.class);
        var connectionContext = mock(ConnectionContext.class);
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "OK", Map.of(), "{\"errors\":false,\"items\":[]}")));
        client = new OpenSearchClient_OS_2_11(restClient, mock(FailedRequestsLogger.class),
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, 100, LIMIT);
        context = mock(IRfsContexts.IRequestContext.class);
    }

    private static Document doc(String id, int bodyChars) {
        var body = "{\"v\":\"" + SECRET + "x".repeat(Math.max(0, bodyChars - SECRET.length())) + "\"}";
        return new Document(id, body.getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(), Map.of());
    }

    private static List<String> oversizedWarnings(CloseableLogSetup logs) {
        return logs.getLogEvents().stream().filter(line -> line.contains("in a request by itself")).toList();
    }

    @Test
    void oversizedOperationsAreCountedEachTimeAndWarnedAboutAtMostOncePerInterval() {
        try (var logs = new CloseableLogSetup(OpenSearchClient.class.getName())) {
            var docs = List.of(doc("small", 10), doc("big-secret-id-1", 400), doc("big-secret-id-2", 400));

            client.sendBulkRequestRaw("idx", docs, context, false, DocumentExceptionAllowlist.empty()).block();

            verify(context, times(2)).addBulkOversizedOperation(OpenSearchClient.RAW_PATH);
            var warnings = oversizedWarnings(logs);
            assertThat(warnings, hasSize(1));
            var warning = warnings.get(0);
            assertThat(warning, containsString("for index 'idx'"));
            assertThat(warning, containsString(LIMIT + "-byte bulk request limit (raw path)"));
            assertThat(warning, not(containsString(SECRET)));
            assertThat(warning, not(containsString("big-secret-id")));
        }
    }

    @Test
    void theTransformedPathIsLabelled() {
        try (var logs = new CloseableLogSetup(OpenSearchClient.class.getName())) {
            var op = BulkOperationConverter.fromDocument(doc("big", 400), "idx");

            client.sendBulkRequest("idx", List.of(op), context, false, DocumentExceptionAllowlist.empty()).block();

            verify(context).addBulkOversizedOperation(OpenSearchClient.TRANSFORMED_PATH);
            assertThat(oversizedWarnings(logs).get(0), containsString("(transformed path)"));
        }
    }

    @Test
    void batchesWithinTheLimitAreNotReported() {
        try (var logs = new CloseableLogSetup(OpenSearchClient.class.getName())) {
            client.sendBulkRequestRaw("idx", List.of(doc("a", 10), doc("b", 10)), context, false,
                DocumentExceptionAllowlist.empty()).block();

            verify(context, never()).addBulkOversizedOperation(any());
            assertThat(oversizedWarnings(logs), hasSize(0));
        }
    }
}
