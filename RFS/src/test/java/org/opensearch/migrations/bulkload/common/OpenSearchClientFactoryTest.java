package org.opensearch.migrations.bulkload.common;

import java.net.URI;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchClientFactoryTest {
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

    @Mock(strictness = Strictness.LENIENT)
    ConnectionContext connectionContext;

    @Mock
    FailedRequestsLogger failedRequestLogger;

    OpenSearchClientFactory openSearchClientFactory;

    @BeforeEach
    void beforeTest() {
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(connectionContext.isAwsSpecificAuthentication()).thenReturn(false);
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        openSearchClientFactory = spy(new OpenSearchClientFactory(connectionContext));
        openSearchClientFactory.client = restClient;
    }

    @Test
    void testGetClusterVersion_ES_7_10() {
        setupOkResponse(restClient, "", ROOT_RESPONSE_ES_7_10_2);
        setupOkResponse(restClient, "_cluster/settings?include_defaults=true", CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED);

        var version = openSearchClientFactory.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("ES 7.10.2")));
        verify(restClient).getAsync("", null);
        verify(restClient).getAsync("_cluster/settings?include_defaults=true", null);
        verifyNoMoreInteractions(restClient);
    }

    @Test
    void testGetClusterVersion_OS_CompatibilityModeEnabled() {
        when(connectionContext.isAwsSpecificAuthentication()).thenReturn(true);
        setupOkResponse(restClient, "", ROOT_RESPONSE_ES_7_10_2);
        setupOkResponse(restClient, "_cluster/settings?include_defaults=true", CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_ENABLED);
        setupOkResponse(restClient, "_nodes/_all/nodes,version?format=json", NODES_RESPONSE_OS_2_13_0);

        var version = openSearchClientFactory.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("AOS 2.13.0")));
        verify(restClient).getAsync("", null);
        verify(restClient).getAsync("_cluster/settings?include_defaults=true", null);
        verify(restClient).getAsync("_nodes/_all/nodes,version?format=json", null);
    }

    @Test
    void testGetClusterVersion_OS_CompatibilityModeDisableEnabled() {
        setupOkResponse(restClient, "", ROOT_RESPONSE_OS_1_0_0);
        setupOkResponse(restClient, "_cluster/settings?include_defaults=true", CLUSTER_SETTINGS_COMPATIBILITY_OVERRIDE_DISABLED);

        var version = openSearchClientFactory.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("OS 1.0.0")));
        verify(restClient).getConnectionContext();
        verify(restClient).getAsync("", null);
        verifyNoMoreInteractions(restClient);
    }

    @Test
    void testGetClusterVersion_OS_CompatibilityModeFailure_UseFallback() {
        setupOkResponse(restClient, "", ROOT_RESPONSE_ES_7_10_2);

        var versionResponse = new HttpResponse(403, "Forbidden", Map.of(), "");
        when(restClient.getAsync("_cluster/settings?include_defaults=true", null)).thenReturn(Mono.just(versionResponse));

        var version = openSearchClientFactory.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("ES 7.10.2")));
        verify(restClient).getAsync("", null);
        verify(restClient).getAsync("_cluster/settings?include_defaults=true", null);
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

        var version = openSearchClientFactory.getClusterVersion();

        assertThat(version, equalTo(Version.fromString("AOSS 2.x.x")));
        verify(restClient, times(1)).getAsync("", null);
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

        var bothTrue = openSearchClientFactory.checkCompatibilityModeFromResponse(createSettingsResponse.apply(true, true));
        assertThat(bothTrue.block(), equalTo(true));
        var persistentTrue = openSearchClientFactory.checkCompatibilityModeFromResponse(createSettingsResponse.apply(true, false));
        assertThat(persistentTrue.block(), equalTo(true));
        var transientTrue = openSearchClientFactory.checkCompatibilityModeFromResponse(createSettingsResponse.apply(false, true));
        assertThat(transientTrue.block(), equalTo(true));
        var neitherTrue = openSearchClientFactory.checkCompatibilityModeFromResponse(createSettingsResponse.apply(false, false));
        assertThat(neitherTrue.block(), equalTo(false));
    }
}
