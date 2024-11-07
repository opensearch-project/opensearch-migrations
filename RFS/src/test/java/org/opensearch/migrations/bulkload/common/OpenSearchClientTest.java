package org.opensearch.migrations.bulkload.common;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator.BulkItemResponseEntry;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts.ICheckedIdempotentPutRequestContext;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.opensearch.migrations.bulkload.http.BulkRequestGenerator.itemEntryFailure;

@ExtendWith(MockitoExtension.class)
class OpenSearchClientTest {
    private static final String NODES_RESPONSE_OS_2_13_0 = "{\r\n" + //
                "    \"_nodes\": {\r\n" + //
                "        \"total\": 1,\r\n" + //
                "        \"successful\": 1,\r\n" + //
                "        \"failed\": 0\r\n" + //
                "    },\r\n" + //
                "    \"cluster_name\": \"336984078605:target-domain\",\r\n" + //
                "    \"nodes\": {\r\n" + //
                "        \"HDzrwdO8TneRQaxzx94uKA\": {\r\n" + //
                "            \"name\": \"74c8fa743d5e3626e3903c3b1d5450e0\",\r\n" + //
                "            \"version\": \"2.13.0\",\r\n" + //
                "            \"build_type\": \"tar\",\r\n" + //
                "            \"build_hash\": \"unknown\",\r\n" + //
                "            \"roles\": [\r\n" + //
                "                \"data\",\r\n" + //
                "                \"ingest\",\r\n" + //
                "                \"master\",\r\n" + //
                "                \"remote_cluster_client\"\r\n" + //
                "            ]\r\n" + //
                "        }\r\n" + //
                "    }\r\n" + //
                "}";
    private static final String CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_ENABLED = "{\"persistent\":{\"compatibility\":{\"override_main_response_version\":\"true\"}}}";
    private static final String CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED = "{\"persistent\":{\"compatibility\":{\"override_main_response_version\":\"false\"}}}";
    private static final String ROOT_RESPONSE_OS_1_0_0 = "{\"version\":{\"distribution\":\"opensearch\",\"number\":\"1.0.0\"}}";
    private static final String ROOT_RESPONSE_ES_7_10_2 = "{\"version\": {\"number\": \"7.10.2\"}}";
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();

    @Mock(strictness = Strictness.LENIENT)
    RestClient restClient;

    @Mock
    ConnectionContext connectionContext;

    @Mock
    FailedRequestsLogger failedRequestLogger;

    OpenSearchClient openSearchClient;

    @BeforeEach
    void beforeTest() {
        doReturn(connectionContext).when(restClient).getConnectionContext();
        openSearchClient = spy(new OpenSearchClient(restClient, failedRequestLogger));
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

    @Test
    void testGetClusterVersion_ES_7_10() {
        setupOkResponse(restClient, "", ROOT_RESPONSE_ES_7_10_2);
        setupOkResponse(restClient, "_cluster/settings", CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED);

        var version = openSearchClient.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("ES 7.10.2")));
        verify(restClient).getAsync("", null);
        verify(restClient).getAsync("_cluster/settings", null);
        verifyNoMoreInteractions(restClient);
    }

    @Test
    void testGetClusterVersion_OS_CompatibilityModeEnabled() {
        when(connectionContext.isAwsSpecificAuthentication()).thenReturn(true);
        setupOkResponse(restClient, "", ROOT_RESPONSE_ES_7_10_2);
        setupOkResponse(restClient, "_cluster/settings", CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_ENABLED);
        setupOkResponse(restClient, "_nodes/_all/nodes,version?format=json", NODES_RESPONSE_OS_2_13_0);

        var version = openSearchClient.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("AOS 2.13.0")));
        verify(restClient).getAsync("", null);
        verify(restClient).getAsync("_cluster/settings", null);
        verify(restClient).getAsync("_nodes/_all/nodes,version?format=json", null);
    }

    @Test
    void testGetClusterVersion_OS_CompatibilityModeDisableEnabled() {
        setupOkResponse(restClient, "", ROOT_RESPONSE_OS_1_0_0);
        setupOkResponse(restClient, "_cluster/settings", CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED);

        var version = openSearchClient.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("OS 1.0.0")));
        verify(restClient).getConnectionContext();
        verify(restClient).getAsync("", null);
        verifyNoMoreInteractions(restClient);
    }

    @Test
    void testGetClusterVersion_OS_CompatibilityModeFailure_UseFallback() {
        setupOkResponse(restClient, "", ROOT_RESPONSE_ES_7_10_2);

        var versionResponse = new HttpResponse(403, "Forbidden", Map.of(), "");
        when(restClient.getAsync("_cluster/settings", null)).thenReturn(Mono.just(versionResponse));

        var version = openSearchClient.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("ES 7.10.2")));
        verify(restClient).getAsync("", null);
        verify(restClient).getAsync("_cluster/settings", null);
        verifyNoMoreInteractions(restClient);
    }

    private void setupOkResponse(RestClient restClient, String url, String body) {
        var versionResponse = new HttpResponse(200, "OK", Map.of(), body);
        when(restClient.getAsync(url, null)).thenReturn(Mono.just(versionResponse));
    }

    @Test
    void testGetClusterVersion_OS_Serverless() {
        var versionResponse = new HttpResponse(404, "Not Found", Map.of(), "");
        when(restClient.getAsync("", null)).thenReturn(Mono.just(versionResponse));

        var version = openSearchClient.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("AOSS 2.x.x")));
        verify(restClient, times(1)).getAsync("", null);
        verifyNoMoreInteractions(restClient);
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

        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(bothDocsFail))
            .thenReturn(Mono.just(oneFailure))
            .thenReturn(Mono.just(server500))
            .thenReturn(Mono.just(finalDocSuccess));

        var bulkDocs = List.of(createBulkDoc(docId1), createBulkDoc(docId2));
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

        verify(restClient, times(4)).postAsync(any(), any(), any(), any());
        verifyNoInteractions(failedRequestLogger);
    }

    @Test
    void testBulkRequest_recordsTotalFailures() {
        var docId1 = "tt1979320";
        var docFails = bulkItemResponse(true, List.of(itemEntryFailure(docId1)));

        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(docFails));

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
        verify(restClient, times(maxAttempts)).postAsync(any(), any(), any(), any());
        verify(failedRequestLogger).logBulkFailure(any(), any(), any(), any());
        verifyNoMoreInteractions(failedRequestLogger);
    }

    private HttpResponse bulkItemResponse(boolean hasErrors, List<BulkItemResponseEntry> entries) {
        var responseBody = BulkRequestGenerator.bulkItemResponse(hasErrors, entries);
        return new HttpResponse(200, "", null, responseBody);
    }

    private BulkDocSection createBulkDoc(String docId) {
        var bulkDoc = mock(BulkDocSection.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(bulkDoc.getDocId()).thenReturn(docId);
        when(bulkDoc.asBulkIndexString()).thenReturn("BULK-INDEX\nBULK_BODY");
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

        when(restClient.supportsGzipCompression()).thenReturn(true);
        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(bulkSuccess));

        var bulkDoc = createBulkDoc(docId);
        var indexName = "testIndex";

        // Action
        openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class)
        ).block();

        // Assertions
        ArgumentCaptor<Map<String, List<String>>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restClient).postAsync(eq(indexName + "/_bulk"), any(), headersCaptor.capture(), any());

        Map<String, List<String>> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders.get("accept-encoding"), equalTo(List.of("gzip")));
        assertThat(capturedHeaders.get("content-encoding"), equalTo(List.of("gzip")));
    }

    @Test
    void testBulkRequest_doesNotAddGzipHeaders_whenNotSupported() {
        var docId = "tt1979320";
        var bulkSuccess = bulkItemResponse(false, List.of(itemEntry(docId)));

        when(restClient.supportsGzipCompression()).thenReturn(false);
        when(restClient.postAsync(any(), any(), any(), any())).thenReturn(Mono.just(bulkSuccess));

        var bulkDoc = createBulkDoc(docId);
        var indexName = "testIndex";

        // Action
        openSearchClient.sendBulkRequest(
            indexName,
            List.of(bulkDoc),
            mock(IRfsContexts.IRequestContext.class)
        ).block();

        // Assertions
        ArgumentCaptor<Map<String, List<String>>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restClient).postAsync(eq(indexName + "/_bulk"), any(), headersCaptor.capture(), any());

        Map<String, List<String>> capturedHeaders = headersCaptor.getValue();
        assertThat(capturedHeaders.get("accept-encoding"), equalTo(null));
        assertThat(capturedHeaders.get("content-encoding"), equalTo(null));
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

    @Test
    void testCheckCompatibilityModeFromResponse() {
        Function<Boolean, JsonNode> createCompatibilitySection = (Boolean value) ->
            OBJECT_MAPPER.createObjectNode()
                .<ObjectNode>set("compatibility", OBJECT_MAPPER.createObjectNode()
                    .put("override_main_response_version", value));
        
        BiFunction<Boolean, Boolean, HttpResponse> createSettingsResponse = (Boolean persistentVal, Boolean transientVal) -> {
            var body = OBJECT_MAPPER.createObjectNode()
                .<ObjectNode>set("persistent", createCompatibilitySection.apply(persistentVal))
                .set("transient", createCompatibilitySection.apply(transientVal))
                .toPrettyString();
            return new HttpResponse(200, "OK", null, body);
        };

        var bothTrue = openSearchClient.checkCompatibilityModeFromResponse(createSettingsResponse.apply(true, true));
        assertThat(bothTrue.block(), equalTo(true));
        var persistentTrue = openSearchClient.checkCompatibilityModeFromResponse(createSettingsResponse.apply(true, false));
        assertThat(persistentTrue.block(), equalTo(true));
        var transientTrue = openSearchClient.checkCompatibilityModeFromResponse(createSettingsResponse.apply(false, true));
        assertThat(transientTrue.block(), equalTo(true));
        var neitherTrue = openSearchClient.checkCompatibilityModeFromResponse(createSettingsResponse.apply(false, false));
        assertThat(neitherTrue.block(), equalTo(false));
    }
}
