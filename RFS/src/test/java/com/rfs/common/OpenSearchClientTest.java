package com.rfs.common;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import com.rfs.common.DocumentReindexer.BulkDocSection;
import com.rfs.common.http.HttpResponse;
import com.rfs.tracing.IRfsContexts;
import com.rfs.tracing.IRfsContexts.ICheckedIdempotentPutRequestContext;

import lombok.SneakyThrows;
import reactor.core.publisher.Mono;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import reactor.test.StepVerifier;
import reactor.util.retry.Retry;


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
    void testBulkRequest() {
        var response = "{\r\n" + //
            "    \"took\": 11,\r\n" + //
            "    \"errors\": true,\r\n" + //
            "    \"items\": [\r\n" + //
            "        {\r\n" + //
            "            \"index\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"tt1979320\",\r\n" + //
            "                \"_version\": 1,\r\n" + //
            "                \"result\": \"created\",\r\n" + //
            "                \"_shards\": {\r\n" + //
            "                    \"total\": 2,\r\n" + //
            "                    \"successful\": 1,\r\n" + //
            "                    \"failed\": 0\r\n" + //
            "                },\r\n" + //
            "                \"_seq_no\": 1,\r\n" + //
            "                \"_primary_term\": 1,\r\n" + //
            "                \"status\": 201\r\n" + //
            "            }\r\n" + //
            "        },\r\n" + //
            "        {\r\n" + //
            "            \"create\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"tt1392214\",\r\n" + //
            "                \"status\": 409,\r\n" + //
            "                \"error\": {\r\n" + //
            "                    \"type\": \"version_conflict_engine_exception\",\r\n" + //
            "                    \"reason\": \"[tt1392214]: version conflict, document already exists (current version [1])\",\r\n" + //
            "                    \"index\": \"movies\",\r\n" + //
            "                    \"shard\": \"0\",\r\n" + //
            "                    \"index_uuid\": \"yhizhusbSWmP0G7OJnmcLg\"\r\n" + //
            "                }\r\n" + //
            "            }\r\n" + //
            "        },\r\n" + //
            "        {\r\n" + //
            "            \"update\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"tt0816711\",\r\n" + //
            "                \"status\": 404,\r\n" + //
            "                \"error\": {\r\n" + //
            "                    \"type\": \"document_missing_exception\",\r\n" + //
            "                    \"reason\": \"[_doc][tt0816711]: document missing\",\r\n" + //
            "                    \"index\": \"movies\",\r\n" + //
            "                    \"shard\": \"0\",\r\n" + //
            "                    \"index_uuid\": \"yhizhusbSWmP0G7OJnmcLg\"\r\n" + //
            "                }\r\n" + //
            "            }\r\n" + //
            "        }\r\n" + //
            "    ]\r\n" + //
            "}";

        var response2 = "{\r\n" + //
            "    \"took\": 11,\r\n" + //
            "    \"errors\": true,\r\n" + //
            "    \"items\": [\r\n" + //
            "        {\r\n" + //
            "            \"index\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"tt0816711\",\r\n" + //
            "                \"_version\": 1,\r\n" + //
            "                \"result\": \"created\",\r\n" + //
            "                \"_shards\": {\r\n" + //
            "                    \"total\": 2,\r\n" + //
            "                    \"successful\": 1,\r\n" + //
            "                    \"failed\": 0\r\n" + //
            "                },\r\n" + //
            "                \"_seq_no\": 1,\r\n" + //
            "                \"_primary_term\": 1,\r\n" + //
            "                \"status\": 201\r\n" + //
            "            }\r\n" + //
            "        },\r\n" + //
            "        {\r\n" + //
            "            \"create\": {\r\n" + //
            "                \"_index\": \"movies\",\r\n" + //
            "                \"_id\": \"tt1392214\",\r\n" + //
            "                \"status\": 409,\r\n" + //
            "                \"error\": {\r\n" + //
            "                    \"type\": \"version_conflict_engine_exception\",\r\n" + //
            "                    \"reason\": \"[tt1392214]: version conflict, document already exists (current version [1])\",\r\n" + //
            "                    \"index\": \"movies\",\r\n" + //
            "                    \"shard\": \"0\",\r\n" + //
            "                    \"index_uuid\": \"yhizhusbSWmP0G7OJnmcLg\"\r\n" + //
            "                }\r\n" + //
            "            }\r\n" + //
            "        }\r\n" + //
            "    ]\r\n" + //
            "}";

        var bulkResponse = new HttpResponse(200, "", null, response);
        var bulkResponse2 = new HttpResponse(200, "", null, response2);
        var server500 = new HttpResponse(500, "", null, "{\"error\":\"Cannot Process Error!\"}");

        var restClient = mock(RestClient.class);
        when(restClient.postAsync(any(), any(), any()))
            .thenReturn(Mono.just(bulkResponse))
            .thenReturn(Mono.just(server500))
            .thenReturn(Mono.just(bulkResponse2))
            .thenReturn(Mono.just(server500));


        // Action
        var dockSection = mock(BulkDocSection.class);
        when(dockSection.getDocId()).thenReturn("tt1979320");
        when(dockSection.asBulkIndex()).thenReturn("BULK-INDEX");

        var dockSection2 = mock(BulkDocSection.class);
        when(dockSection2.getDocId()).thenReturn("tt0816711");
        when(dockSection2.asBulkIndex()).thenReturn("BULK-INDEX");

        var bulkDocs = List.of(dockSection, dockSection2);
        var openSearchClient = spy(new OpenSearchClient(restClient));
        doReturn(Retry.fixedDelay(4, Duration.ofMillis(10))).when(openSearchClient).getBulkRetryStrategy();

        var responseMono = openSearchClient.sendBulkRequest("myIndex", bulkDocs, mock(IRfsContexts.IRequestContext.class));

        StepVerifier.create(responseMono)
            .expectComplete()
            .verify();

        verify(restClient, times(3)).postAsync(any(), any(), any());
    }

    @SneakyThrows
    private Optional<ObjectNode> createIndex(RestClient restClient, String rawJson) {
        var openSearchClient = new OpenSearchClient(restClient);

        var body = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        return openSearchClient.createIndex("indexName", body, mock(ICheckedIdempotentPutRequestContext.class));
    }
}
