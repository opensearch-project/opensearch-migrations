package org.opensearch.migrations.bulkload.common;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator.BulkItemResponseEntry;
import org.opensearch.migrations.bulkload.pipeline.adapter.LuceneAdapter;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICheckedIdempotentPutRequestContext;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;
import org.opensearch.migrations.testutils.CloseableLogSetup;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntry;
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntryRetryableFailure;

@ExtendWith(MockitoExtension.class)
class OpenSearchClientTest {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();

    @Mock(strictness = Strictness.LENIENT)
    RestClient restClient;

    @Mock(strictness = Strictness.LENIENT)
    ConnectionContext connectionContext;

    @Mock
    FailedRequestsLogger failedRequestLogger;

    OpenSearchClient openSearchClient;

    @BeforeEach
    void beforeTest() {
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        openSearchClient = spy(new OpenSearchClient_OS_2_11(restClient, failedRequestLogger, Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED));
    }

    @Test
    void testCreateIndex() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"created\":\"yup!\"}";
        var createItemResponse = new HttpResponse(200, "", null, createdItemRawJson);

        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var result = createIndex(restClient, rawJson);

        // Assertions
        assertThat(result.get().toPrettyString(), containsString(rawJson));
        // The interface is to send back the passed json if on success
        assertThat(result.get().toPrettyString(), not(containsString(createdItemRawJson)));
    }

    @Test
    void testCreateIndex_alreadyExists() {
        var checkIfExistsResponse = new HttpResponse(200, "", null, "I exist!");

        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));

        var rawJson = "{ }";
        var result = createIndex(restClient, rawJson);

        assertThat(result, equalTo(Optional.empty()));
    }

    @Test
    void testCreateIndex_errorOnCreation_retried() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"error\":\"unauthorized\"}";
        var createItemResponse = new HttpResponse(403, "", null, createdItemRawJson);

        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var exception = assertThrows(IllegalStateException.class, () -> createIndex(restClient, rawJson));

        // Assertions
        assertThat(exception.getClass().getName(), containsString("RetryExhaustedException"));
        assertThat(exception.getMessage(), containsString("Retries exhausted"));
        assertThat(exception.getCause().getMessage(), containsString("403"));
        assertThat(exception.getCause().getMessage(), containsString("unauthorized"));
        // The interface is to send back the passed json if on success
    }

    @Test
    void testCreateIndex_errorOnCreation_notRetriedOnBadRequest() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"error\":\"illegal_argument_exception\"}";
        var createItemResponse = new HttpResponse(400, "", null, createdItemRawJson);

        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var exception = assertThrows(InvalidResponse.class, () -> createIndex(restClient, rawJson));

        // Assertions
        assertThat(exception.getMessage(), containsString("illegal_argument_exception"));
    }

    private void setupOkResponse(RestClient restClient, String url, String body) {
        var versionResponse = new HttpResponse(200, "OK", Map.of(), body);
        when(restClient.getAsync(url, null)).thenReturn(Mono.just(versionResponse));
    }

    @Test
    void testBulkRequest_succeedAfterRetries() {
        var docId1 = "tt1979320";
        var docId2 = "tt0816711";

        // Use retryable failures (es_rejected_execution_exception): we want this test
        // to drive the bulk retry loop. NON_RETRYABLE error types (e.g.
        // version_conflict_engine_exception) would be sent to the failed document stream on the first
        // attempt and never retried — see BulkDocErrorTypes.NON_RETRYABLE.
        var bothDocsFail = bulkItemResponse(
            true,
            List.of(itemEntryRetryableFailure(docId1), itemEntryRetryableFailure(docId2))
        );
        var oneFailure = bulkItemResponse(
            true,
            List.of(itemEntry(docId1), itemEntryRetryableFailure(docId2))
        );
        var finalDocSuccess = bulkItemResponse(false, List.of(itemEntry(docId2)));
        var server500 = new HttpResponse(500, "", null, "{\"error\":\"Cannot Process Error!\"}");

        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(bothDocsFail))
            .thenReturn(Mono.just(oneFailure))
            .thenReturn(Mono.just(server500))
            .thenReturn(Mono.just(finalDocSuccess));

        var bulkDocs = List.of(createBulkDoc(docId1), createBulkDoc(docId2));
        doReturn(Retry.fixedDelay(6, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

        // Action
        var responseMono = openSearchClient.sendBulkRequest(
            "myIndex",
            bulkDocs,
            mock(IRfsContexts.IRequestContext.class),
            false,
            DocumentExceptionAllowlist.empty()
        );
        responseMono.block();

        // Assertions
        // StepVerifier.create(responseMono).expectComplete().verify();

        verify(restClient, times(4)).postAsyncBytes(any(), any(), any(), any());
        verifyNoInteractions(failedRequestLogger);
    }

    @Test
    void testBulkRequest_recordsTotalFailures() {
        var docId1 = "tt1979320";
        // Retryable failure so the test exercises the retry-exhaust path; a
        // NON_RETRYABLE type would skip retries entirely.
        var docFails = bulkItemResponse(true, List.of(itemEntryRetryableFailure(docId1)));

        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(docFails));

        var maxRetries = 6;
        doReturn(Retry.fixedDelay(maxRetries, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

        var bulkDoc = createBulkDoc(docId1);
        var indexName = "alwaysFailingIndexName";

        // Action
        var responseMono = openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class),
            false,
            DocumentExceptionAllowlist.empty()
        );
        var exception = assertThrows(Exception.class, () -> responseMono.block());

        // Assertions
        assertThat(exception.getMessage(), containsString("Retries exhausted"));

        var maxAttempts = maxRetries + 1;
        verify(restClient, times(maxAttempts)).postAsyncBytes(any(), any(), any(), any());
        verify(failedRequestLogger).logBulkFailure(any(), any(), any(), any());
        verifyNoMoreInteractions(failedRequestLogger);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void versionConflictsAreFailuresUnlessExplicitlyAllowlisted(boolean raw, boolean allowVersionConflicts) throws Exception {
        var failedDocuments = mock(FailedDocumentStreamSink.class);
        when(failedDocuments.write(any())).thenReturn(Mono.empty());
        openSearchClient.setFailedDocumentStreamContext(failedDocuments, "version-test", "worker");
        doReturn(Retry.fixedDelay(2, Duration.ofMillis(1))).when(openSearchClient).getBulkRetryStrategy();
        var documents = List.of(
            snapshotDocument("external", 7L),
            new Document("external-gte", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Document.Operation.UPSERT,
                Map.of(Document.HINT_VERSION, "7", Document.HINT_VERSION_TYPE, "external_gte"), Map.of()),
            snapshotDocument("internal", null),
            snapshotDocument("bad-mapping", 7L),
            snapshotDocument("retry", Long.MAX_VALUE)
        );
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, """
                {"errors":true,"items":[
                  {"index":{"_id":"external","status":409,"error":{"type":"version_conflict_engine_exception"}}},
                  {"index":{"_id":"external-gte","status":409,"error":{"type":"version_conflict_engine_exception"}}},
                  {"index":{"_id":"internal","status":409,"error":{"type":"version_conflict_engine_exception"}}},
                  {"index":{"_id":"bad-mapping","status":400,"error":{"type":"mapper_parsing_exception"}}},
                  {"index":{"_id":"retry","status":429,"error":{"type":"es_rejected_execution_exception"}}}
                ]}
                """)))
            .thenReturn(Mono.just(new HttpResponse(200, "", null,
                "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"retry\",\"status\":201,\"result\":\"created\"}}]}")));

        var allowlist = allowVersionConflicts
            ? new DocumentExceptionAllowlist(Set.of("version_conflict_engine_exception"))
            : DocumentExceptionAllowlist.empty();
        if (raw) {
            openSearchClient.sendBulkRequestRaw("products", documents, null, false, allowlist).block();
        } else {
            openSearchClient.sendBulkRequest("products",
                documents.stream().map(doc -> BulkOperationConverter.fromDocument(doc, "products")).toList(),
                null, false, allowlist).block();
        }

        var failures = ArgumentCaptor.forClass(FailedDocumentStreamRecord.class);
        var expectedFailures = allowVersionConflicts
            ? List.of("bad-mapping") : List.of("external", "external-gte", "internal", "bad-mapping");
        verify(failedDocuments, times(expectedFailures.size())).write(failures.capture());
        assertEquals(expectedFailures,
            failures.getAllValues().stream().map(FailedDocumentStreamRecord::getDocumentId).toList());
        for (var failure : failures.getAllValues()) {
            assertEquals(FailureClass.NON_RETRYABLE, failure.getFailureClass());
            assertEquals(failure.getDocumentId().equals("bad-mapping")
                ? "mapper_parsing_exception" : "version_conflict_engine_exception", failure.getFailureType());
        }
        var requests = ArgumentCaptor.forClass(byte[].class);
        verify(restClient, times(2)).postAsyncBytes(any(), requests.capture(), any(), any());
        var retriedLines = new String(requests.getAllValues().get(1), java.nio.charset.StandardCharsets.UTF_8).split("\n");
        assertEquals(2, retriedLines.length);
        var retriedAction = OBJECT_MAPPER.readTree(retriedLines[0]).path("index");
        assertEquals("retry", retriedAction.path("_id").asText());
        assertEquals(Long.MAX_VALUE, retriedAction.path("version").longValue());
        assertEquals("external", retriedAction.path("version_type").asText());
        verifyNoInteractions(failedRequestLogger);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generatedIdsOmitVersionsOnInitialWritesAndRetries(boolean raw) throws Exception {
        doReturn(Retry.fixedDelay(2, Duration.ofMillis(1))).when(openSearchClient).getBulkRetryStrategy();
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, """
                {"errors":true,"items":[
                  {"index":{"_id":"generated-1","status":429,"error":{"type":"es_rejected_execution_exception"}}}
                ]}
                """)))
            .thenReturn(Mono.just(new HttpResponse(200, "", null,
                "{\"errors\":false,\"items\":[{\"index\":{\"_id\":\"generated-2\",\"status\":201,\"result\":\"created\"}}]}")));
        var documents = List.of(snapshotDocument("source-id", Long.MAX_VALUE));
        if (raw) {
            openSearchClient.sendBulkRequestRaw("products", documents, null, true, DocumentExceptionAllowlist.empty()).block();
        } else {
            openSearchClient.sendBulkRequest("products",
                documents.stream().map(doc -> BulkOperationConverter.fromDocument(doc, "products")).toList(),
                null, true, DocumentExceptionAllowlist.empty()).block();
        }
        var requests = ArgumentCaptor.forClass(byte[].class);
        verify(restClient, times(2)).postAsyncBytes(any(), requests.capture(), any(), any());
        for (byte[] body : requests.getAllValues()) {
            var action = OBJECT_MAPPER.readTree(new String(body, java.nio.charset.StandardCharsets.UTF_8).split("\n")[0])
                .path("index");
            assertFalse(action.has("_id"));
            assertFalse(action.has("version"));
            assertFalse(action.has("version_type"));
        }
        verifyNoInteractions(failedRequestLogger);
    }

    private static Document snapshotDocument(String id, Long version) {
        return new LuceneAdapter().fromLucene(new LuceneDocumentChange(0, id, null,
            "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null, DocumentChangeType.INDEX, version));
    }

    private HttpResponse bulkItemResponse(boolean hasErrors, List<BulkItemResponseEntry> entries) {
        var responseBody = BulkRequestGenerator.bulkItemResponse(hasErrors, entries);
        return new HttpResponse(200, "", null, responseBody);
    }

    private BulkOperationSpec createBulkDoc(String docId) {
        var bulkDoc = mock(IndexOp.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        var operation = mock(IndexOperationMeta.class);
        when(operation.getId()).thenReturn(docId);
        when(bulkDoc.getOperation()).thenReturn(operation);
        when(bulkDoc.getOperationType()).thenReturn(OperationType.INDEX);
        when(bulkDoc.isIncludeDocument()).thenReturn(true);
        when(bulkDoc.getDocument()).thenReturn(java.util.Map.of("field", "value"));
        return bulkDoc;
    }

    @SneakyThrows
    private Optional<ObjectNode> createIndex(RestClient restClient, String rawJson) {
        var body = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        return openSearchClient.createIndex("indexName", body, mock(ICheckedIdempotentPutRequestContext.class));
    }

    @Test
    void testBulkRequest_addsGzipHeaders_whenSupported() {
        var docId = "tt1979320";
        var bulkSuccess = bulkItemResponse(false, List.of(itemEntry(docId)));

        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(bulkSuccess));
        openSearchClient = spy(new OpenSearchClient_OS_2_11(restClient, failedRequestLogger, Version.fromString("OS 2.11"),
                CompressionMode.GZIP_BODY_COMPRESSION));

        var bulkDoc = createBulkDoc(docId);
        var indexName = "testIndex";

        // Action
        openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class),
            false,
            DocumentExceptionAllowlist.empty()
        ).block();

        // Assertions
        ArgumentCaptor<Map<String, List<String>>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restClient).postAsyncBytes(eq(indexName + "/_bulk"), any(), headersCaptor.capture(), any());

        Map<String, List<String>> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders.get("accept-encoding"), equalTo(List.of("gzip")));
        assertThat(capturedHeaders.get("content-encoding"), equalTo(List.of("gzip")));
    }

    @Test
    void testBulkRequest_doesNotAddGzipHeaders_whenNotSupported() {
        var docId = "tt1979320";
        var bulkSuccess = bulkItemResponse(false, List.of(itemEntry(docId)));

        openSearchClient = spy(new OpenSearchClient_OS_2_11(restClient, failedRequestLogger, Version.fromString("OS 2.11"),
                CompressionMode.UNCOMPRESSED));
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(bulkSuccess));

        var bulkDoc = createBulkDoc(docId);
        var indexName = "testIndex";

        // Action
        openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class),
            false,
            DocumentExceptionAllowlist.empty()
        ).block();

        // Assertions
        ArgumentCaptor<Map<String, List<String>>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restClient).postAsyncBytes(eq(indexName + "/_bulk"), any(), headersCaptor.capture(), any());

        Map<String, List<String>> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders.get("accept-encoding"), equalTo(null));
        assertThat(capturedHeaders.get("content-encoding"), equalTo(null));
    }

    @Test
    void testBulkRequest_warningLogContainsDetails_onError() {
        try (var logs = new CloseableLogSetup(OpenSearchClient.class.getName())) {
            var docId1 = "tt1979320";
            // Retryable failure so the retry loop runs and emits the "After bulk
            // request attempt" warn log. The asserted reason text below must match
            // the helper's response body.
            var docFails = bulkItemResponse(true, List.of(itemEntryRetryableFailure(docId1)));

            when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(docFails));

            var maxRetries = 1;
            doReturn(Retry.fixedDelay(maxRetries, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

            var bulkDoc = createBulkDoc(docId1);
            var indexName = "alwaysFailingIndexName";

            // Action
            var responseMono = openSearchClient.sendBulkRequest(
                    indexName,
                    List.of(bulkDoc),
                    mock(IRfsContexts.IRequestContext.class),
            false,
            DocumentExceptionAllowlist.empty()
            );
            assertThrows(Exception.class, responseMono::block);

            // Verify the logs
            List<String> bulkErrorWarnLogs = logs.getLogEvents().stream()
                    .filter(log -> log.contains("After bulk request attempt")).toList();
            for (String bulkErrorWarnLog : bulkErrorWarnLogs) {
                // Add buffer for additional log line characters
                assertThat(bulkErrorWarnLog.length(), lessThan(OpenSearchClient.BULK_TRUNCATED_RESPONSE_MAX_LENGTH + 300));
                assertThat(bulkErrorWarnLog, containsString("rejected execution of bulk request"));
            }
        }
    }

    @Test
    void testBulkRequest_truncatesLargeLog_onError() {
        try (var logs = new CloseableLogSetup(OpenSearchClient.class.getName())) {
            var docId1 = "tt1979320";
            // Create dummy large response message
            StringBuilder sb = new StringBuilder();
            sb.append("{\"data\":\"");
            int desiredStringLength = OpenSearchClient.BULK_TRUNCATED_RESPONSE_MAX_LENGTH + 2000;
            while (sb.length() < desiredStringLength) {
                sb.append("a");
            }
            sb.append("\"}");
            String jsonString = sb.toString();
            var largeResponse = BulkItemResponseEntry.builder().raw(jsonString).build();
            var docFails = bulkItemResponse(true, List.of(largeResponse));

            when(restClient.postAsyncBytes(any(), any(), any(), any())).thenReturn(Mono.just(docFails));

            var maxRetries = 1;
            doReturn(Retry.fixedDelay(maxRetries, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

            var bulkDoc = createBulkDoc(docId1);
            var indexName = "alwaysFailingIndexName";

            // Action
            var responseMono = openSearchClient.sendBulkRequest(
                    indexName,
                    List.of(bulkDoc),
                    mock(IRfsContexts.IRequestContext.class),
            false,
            DocumentExceptionAllowlist.empty()
            );
            assertThrows(Exception.class, responseMono::block);

            // Verify the logs
            List<String> bulkErrorWarnLogs = logs.getLogEvents().stream()
                    .filter(log -> log.contains("After bulk request attempt")).toList();
            for (String bulkErrorWarnLog : bulkErrorWarnLogs) {
                // Add buffer for additional log line characters
                assertThat(bulkErrorWarnLog.length(), lessThan(OpenSearchClient.BULK_TRUNCATED_RESPONSE_MAX_LENGTH + 300));
                assertThat(bulkErrorWarnLog, containsString("aaaaa... [truncated] ...aaaaa"));
            }

        }
    }

    @Test
    void testNonBulkRequest_doesNotAddGzipHeaders() {
        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(new HttpResponse(404, "", null, "does not exist")));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(new HttpResponse(200, "", null, "{\"created\":\"yup!\"}")));

        // Action
        openSearchClient.createIndex("testIndex", OBJECT_MAPPER.createObjectNode(), mock(IRfsContexts.ICheckedIdempotentPutRequestContext.class));

        // Assertions
        verify(restClient).getAsync(any(), any());
        verify(restClient).putAsync(any(), any(), any());
        verifyNoMoreInteractions(restClient);
    }
}
