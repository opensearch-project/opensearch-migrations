package org.opensearch.migrations.bulkload.version_os_2_11;

import java.util.Optional;
import java.util.Set;

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

    @SneakyThrows
    private CreationResult create(OpenSearchClient client, String rawJson, String indexName) {
        var node = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        var indexId = "indexId";
        var indexData = new IndexMetadataData_OS_2_11(node, indexId, indexName);
        var indexCreator = new IndexCreator_OS_2_11(client);
        return indexCreator.create(indexData, MigrationMode.PERFORM, mock(ICreateIndexContext.class));
    }
}
