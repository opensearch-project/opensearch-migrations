package com.rfs.common;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.rfs.common.DocumentReindexer.BulkDocSection;
import com.rfs.common.http.HttpResponse;
import com.rfs.http.BulkRequestGenerator;
import com.rfs.http.BulkRequestGenerator.BulkItemResponseEntry;
import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.IRfsContexts.ICheckedIdempotentPutRequestContext;
import lombok.SneakyThrows;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static com.rfs.http.BulkRequestGenerator.itemEntry;
import static com.rfs.http.BulkRequestGenerator.itemEntryFailure;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OpenSearchClientTest {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();

    @Test
    void testCreateIndex() {
        // Setup
        var checkIfExistsResponse = new HttpResponse(404, "", null, "does not exist");
        var createdItemRawJson = "{\"created\":\"yup!\"}";
        var createItemResponse = new HttpResponse(200, "", null, createdItemRawJson);

        var restClient = mock(RestClient.class);
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

        var restClient = mock(RestClient.class);
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

        var restClient = mock(RestClient.class);
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

        var restClient = mock(RestClient.class);
        when(restClient.getAsync(any(), any())).thenReturn(Mono.just(checkIfExistsResponse));
        when(restClient.putAsync(any(), any(), any())).thenReturn(Mono.just(createItemResponse));

        // Action
        var rawJson = "{ }";
        var exception = assertThrows(InvalidResponse.class, () -> createIndex(restClient, rawJson));

        // Assertions
        assertThat(exception.getMessage(), containsString("illegal_argument_exception"));
    }

    @Test
    void testBulkRequest_succeedAfterRetries() {
        var docId1 = "tt1979320";
        var docId2 = "tt0816711";

        var bothDocsFail = bulkItemResponse(
            true,
            List.of(itemEntryFailure(docId1), itemEntryFailure(docId2))
        );
        var oneFailure = bulkItemResponse(
            true,
            List.of(itemEntry(docId1), itemEntryFailure(docId2))
        );
        var finalDocSuccess = bulkItemResponse(false, List.of(itemEntry(docId2)));
        var server500 = new HttpResponse(500, "", null, "{\"error\":\"Cannot Process Error!\"}");

        var restClient = mock(RestClient.class);
        when(restClient.postAsync(any(), any(), any())).thenReturn(Mono.just(bothDocsFail))
            .thenReturn(Mono.just(oneFailure))
            .thenReturn(Mono.just(server500))
            .thenReturn(Mono.just(finalDocSuccess));

        var bulkDocs = List.of(createBulkDoc(docId1), createBulkDoc(docId2));

        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = spy(new OpenSearchClient(restClient, failedRequestLogger));
        doReturn(Retry.fixedDelay(6, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

        // Action
        var responseMono = openSearchClient.sendBulkRequest(
            "myIndex",
            bulkDocs,
            mock(IRfsContexts.IRequestContext.class)
        );
        responseMono.block();

        // Assertions
        // StepVerifier.create(responseMono).expectComplete().verify();

        verify(restClient, times(4)).postAsync(any(), any(), any());
        verifyNoInteractions(failedRequestLogger);
    }

    @Test
    void testBulkRequest_recordsTotalFailures() {
        var docId1 = "tt1979320";
        var docFails = bulkItemResponse(true, List.of(itemEntryFailure(docId1)));

        var restClient = mock(RestClient.class);
        when(restClient.postAsync(any(), any(), any())).thenReturn(Mono.just(docFails));

        var failedRequestLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = spy(new OpenSearchClient(restClient, failedRequestLogger));

        var maxRetries = 6;
        doReturn(Retry.fixedDelay(maxRetries, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

        var bulkDoc = createBulkDoc(docId1);
        var indexName = "alwaysFailingIndexName";

        // Action
        var responseMono = openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class)
        );
        var exception = assertThrows(Exception.class, () -> responseMono.block());

        // Assertions
        assertThat(exception.getMessage(), containsString("Retries exhausted"));

        var maxAttempts = maxRetries + 1;
        verify(restClient, times(maxAttempts)).postAsync(any(), any(), any());
        verify(failedRequestLogger).logBulkFailure(any(), any(), any(), any());
        verifyNoMoreInteractions(failedRequestLogger);
    }

    private HttpResponse bulkItemResponse(boolean hasErrors, List<BulkItemResponseEntry> entries) {
        var responseBody = BulkRequestGenerator.bulkItemResponse(hasErrors, entries);
        return new HttpResponse(200, "", null, responseBody);
    }

    private BulkDocSection createBulkDoc(String docId) {
        var bulkDoc = mock(BulkDocSection.class);
        when(bulkDoc.getDocId()).thenReturn(docId);
        when(bulkDoc.asBulkIndex()).thenReturn("BULK-INDEX\nBULK_BODY");
        return bulkDoc;
    }

    @SneakyThrows
    private Optional<ObjectNode> createIndex(RestClient restClient, String rawJson) {
        var openSearchClient = new OpenSearchClient(restClient, mock(FailedRequestsLogger.class));

        var body = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        return openSearchClient.createIndex("indexName", body, mock(ICheckedIdempotentPutRequestContext.class));
    }
}
