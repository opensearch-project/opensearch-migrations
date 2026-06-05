package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.Optional;
import java.util.Set;

import org.opensearch.migrations.AwarenessAttributeSettings;
import org.opensearch.migrations.MigrationMode;
import org.opensearch.migrations.bulkload.common.InvalidResponse;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.metadata.CreationResult;
import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexCreator_OS_2_11Test {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();
    private static final Optional<ObjectNode> INDEX_CREATE_SUCCESS = Optional.of(mock(ObjectNode.class));
    private static final String MIN_INDEX_JSON = "{ \"settings\": { } }";

    @Test
    void testCreate() throws Exception {
        // Setup
        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any())).thenReturn(INDEX_CREATE_SUCCESS);

        // Action
        var result = create(client, MIN_INDEX_JSON, "indexName");

        // Assertions
        assertThat(result.wasSuccessful(), equalTo(true));
        verify(client).createIndex(any(), any(), any());
    }

    @Test
    void testCreate_invalidResponse_noIllegalArguments() throws Exception {
        // Setup
        var invalidResponse = mock(InvalidResponse.class);
        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any())).thenThrow(invalidResponse);

        // Action
        var result = create(client, MIN_INDEX_JSON, "indexName");

        // Assertions
        assertThat(result.getException(), equalTo(invalidResponse));
        verify(client).createIndex(any(), any(), any());
    }

    @Test
    void testCreate_invalidResponse_unprocessableIllegalArguments() throws Exception {
        // Setup
        var invalidResponse = mock(InvalidResponse.class);
        when(invalidResponse.getIllegalArguments()).thenReturn(Set.of(
            "index.lifecycle.name", // Illegal argument for index is valid 
            "document.doc_id" // Unprocessable illegal argument - for document instead of index
        ));
        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any())).thenThrow(invalidResponse);

        // Action
        var result = create(client, MIN_INDEX_JSON, "indexName");

        // Assertions
        assertThat(result.getException(), equalTo(invalidResponse));
        verify(client).createIndex(any(), any(), any());
    }

    @Test
    void testCreate_withRetryToRemoveValues() throws Exception {
        // Setup
        var invalidResponse = mock(InvalidResponse.class);
        when(invalidResponse.getIllegalArguments()).thenReturn(Set.of("index.lifecycle.name", "index.field_array", "index.field_string", "index.field_object"));

        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any()))
            .thenThrow(invalidResponse)
            .thenReturn(INDEX_CREATE_SUCCESS);

        var elementThatShouldBeDeleted = "should-be-deleted";
        var rawJson = "{\r\n" + //
            "  \"aliases\" : { },\r\n" + //
            "  \"settings\" : {\r\n" + //
            "    \"lifecycle\": {\r\n" + //
            "      \"name\": \"{0}\"\r\n" + // 
            "    },\r\n" + //
            "    \"number_of_replicas\" : 1,\r\n" + //
            "    \"number_of_shards\" : \"1\",\r\n" + //
            "    \"field_array\" : [ \"{0}\", \"{0}\"],\r\n" + //
            "    \"field_string\" : \"{0}\",\r\n" + //
            "    \"field_object\" : { \"{0}\": \"{0}\"}\r\n" + //
            "  }\r\n" + //
            "}".replaceAll("\\{0\\}", elementThatShouldBeDeleted);

        // Action
        var result = create(client, rawJson, "indexName");

        // Assertions
        assertThat(result.wasSuccessful(), equalTo(true));

        var requestBodyCapture = ArgumentCaptor.forClass(ObjectNode.class);
        verify(client, times(2)).createIndex(any(), requestBodyCapture.capture(), any());

        var finalIndexBody = requestBodyCapture.getValue().toPrettyString();
        assertThat("Empty nodes are OK to send to the service", finalIndexBody, containsString("lifecycle"));
        assertThat(finalIndexBody, not(containsString("field_array")));
        assertThat(finalIndexBody, not(containsString("field_string")));
        assertThat(finalIndexBody, not(containsString("field_object")));
        assertThat("All instances of this value should be removed from the response", finalIndexBody, not(containsString(elementThatShouldBeDeleted)));
    }

    @Test
    void testCreate_withRetryToRemoveFlatKeyedKnnSettings() throws Exception {
        // Reproduces an ES 7.x → OS 3.x retail-products migration where
        // index-level knn settings are stored as flat keys with embedded dots
        // (e.g. settings["index.knn.algo_param.m"]). Before the fix, the strip
        // walked nested segments only and never removed the flat keys, causing
        // an infinite createIndex retry loop.
        var invalidResponse = mock(InvalidResponse.class);
        when(invalidResponse.getIllegalArguments()).thenReturn(Set.of(
            "index.knn.algo_param.ef_construction",
            "index.knn.algo_param.m",
            "index.knn.space_type"
        ));

        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any()))
            .thenThrow(invalidResponse)
            .thenReturn(INDEX_CREATE_SUCCESS);

        var rawJson = "{\n" +
            "  \"aliases\" : { },\n" +
            "  \"mappings\" : { \"properties\": { \"v\": { \"type\": \"knn_vector\", \"dimension\": 4 } } },\n" +
            "  \"settings\" : {\n" +
            "    \"index.knn\": \"true\",\n" +
            "    \"index.knn.algo_param.ef_construction\": \"128\",\n" +
            "    \"index.knn.algo_param.m\": \"24\",\n" +
            "    \"index.knn.space_type\": \"l2\",\n" +
            "    \"number_of_replicas\": 1,\n" +
            "    \"number_of_shards\": \"2\"\n" +
            "  }\n" +
            "}";

        var result = create(client, rawJson, "indexName");

        assertThat(result.wasSuccessful(), equalTo(true));

        var requestBodyCapture = ArgumentCaptor.forClass(ObjectNode.class);
        // Exactly one retry — the second call must succeed because all three
        // illegal args were stripped on the first failure.
        verify(client, times(2)).createIndex(any(), requestBodyCapture.capture(), any());

        var finalIndexBody = requestBodyCapture.getValue().toPrettyString();
        assertThat(finalIndexBody, not(containsString("algo_param.ef_construction")));
        assertThat(finalIndexBody, not(containsString("algo_param.m")));
        assertThat(finalIndexBody, not(containsString("space_type")));
        assertThat("index.knn flag should still be sent",
            finalIndexBody, containsString("index.knn"));
    }

    @Test
    void testCreate_withRetryToRemoveUnsupportedMappingParams() throws Exception {
        // Setup
        var invalidResponse = mock(InvalidResponse.class);
        when(invalidResponse.getUnsupportedMappingParameters()).thenReturn(Set.of("_all", "_parent"));

        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any()))
            .thenThrow(invalidResponse)
            .thenReturn(INDEX_CREATE_SUCCESS);

        var rawJson = "{\r\n" +
            "  \"aliases\" : { },\r\n" +
            "  \"mappings\" : {\r\n" +
            "    \"_all\": { \"enabled\": false },\r\n" +
            "    \"_parent\": { \"type\": \"parent_type\" },\r\n" +
            "    \"dynamic\": false,\r\n" +
            "    \"properties\": { \"field1\": { \"type\": \"text\" } }\r\n" +
            "  },\r\n" +
            "  \"settings\" : { }\r\n" +
            "}";

        // Action
        var result = create(client, rawJson, "indexName");

        // Assertions
        assertThat(result.wasSuccessful(), equalTo(true));

        var requestBodyCapture = ArgumentCaptor.forClass(ObjectNode.class);
        verify(client, times(2)).createIndex(any(), requestBodyCapture.capture(), any());

        var finalIndexBody = requestBodyCapture.getValue().toPrettyString();
        assertThat(finalIndexBody, not(containsString("_all")));
        assertThat(finalIndexBody, not(containsString("_parent")));
        assertThat("dynamic=false should be preserved", finalIndexBody, containsString("dynamic"));
        assertThat("properties should be preserved", finalIndexBody, containsString("field1"));
    }

    @Test
    void testCreate_withRetryToRemoveRemovedTokenFilters() throws Exception {
        // Setup: server complains that the legacy "standard" token filter was removed (ES 7+ / OpenSearch).
        var invalidResponse = mock(InvalidResponse.class);
        when(invalidResponse.getRemovedTokenFilters()).thenReturn(Set.of("standard"));

        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any()))
            .thenThrow(invalidResponse)
            .thenReturn(INDEX_CREATE_SUCCESS);

        var rawJson = "{\r\n" +
            "  \"aliases\": {},\r\n" +
            "  \"mappings\": {},\r\n" +
            "  \"settings\": {\r\n" +
            "    \"analysis\": {\r\n" +
            "      \"analyzer\": {\r\n" +
            "        \"custom_tokenized_string\": {\r\n" +
            "          \"type\": \"custom\",\r\n" +
            "          \"tokenizer\": \"standard\",\r\n" +
            "          \"filter\": [\"standard\", \"lowercase\", \"asciifolding\"]\r\n" +
            "        }\r\n" +
            "      }\r\n" +
            "    }\r\n" +
            "  }\r\n" +
            "}";

        // Action
        var result = create(client, rawJson, "indexName");

        // Assertions
        assertThat(result.wasSuccessful(), equalTo(true));

        var requestBodyCapture = ArgumentCaptor.forClass(ObjectNode.class);
        verify(client, times(2)).createIndex(any(), requestBodyCapture.capture(), any());

        var finalBody = requestBodyCapture.getValue();
        var filters = finalBody.get("settings")
            .get("analysis")
            .get("analyzer")
            .get("custom_tokenized_string")
            .get("filter");
        assertThat("filter array should still exist", filters.isArray(), equalTo(true));
        assertThat("filter array size after removal", filters.size(), equalTo(2));
        var remaining = new java.util.ArrayList<String>();
        filters.forEach(n -> remaining.add(n.asText()));
        assertThat("filter array should no longer contain 'standard'",
            remaining, not(org.hamcrest.Matchers.hasItem("standard")));
        assertThat("other filters preserved", remaining, org.hamcrest.Matchers.hasItems("lowercase", "asciifolding"));
        assertThat("tokenizer 'standard' is unrelated and should be preserved",
            finalBody.get("settings").get("analysis").get("analyzer")
                .get("custom_tokenized_string").get("tokenizer").asText(),
            equalTo("standard"));
    }

    @SneakyThrows
    private CreationResult create(OpenSearchClient client, String rawJson, String indexName) {
        var node = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        var indexId = "indexId";
        var indexData = new IndexMetadataData_OS_2_11(node, indexId, indexName);
        var indexCreator = new IndexCreator_OS_2_11(client);
        return indexCreator.create(indexData, MigrationMode.PERFORM, new AwarenessAttributeSettings(false, 0), mock(ICreateIndexContext.class));
    }
}
