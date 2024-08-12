package com.rfs.version_os_2_11;

import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.metadata.tracing.IMetadataMigrationContexts.ICreateIndexContext;

import com.rfs.common.InvalidResponse;
import com.rfs.common.OpenSearchClient;
import lombok.SneakyThrows;
import org.mockito.ArgumentCaptor;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexCreator_OS_2_11Test {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
        .enable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .build();
    @SuppressWarnings("unchecked")
    private static final Optional<ObjectNode> INDEX_CREATE_SUCCESS = mock(Optional.class);
    private static final String MIN_INDEX_JSON = "{ \"settings\": { } }";

    @Test
    void testCreate() throws Exception {
        // Setup
        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any())).thenReturn(INDEX_CREATE_SUCCESS);

        // Action
        var result = create(client, MIN_INDEX_JSON, "indexName");

        // Assertions
        assertThat(result, equalTo(INDEX_CREATE_SUCCESS));
        verify(client).createIndex(any(), any(), any());
    }

    @Test
    void testCreate_invalidResponse_noIllegalArguments() throws Exception {
        // Setup
        var invalidResponse = mock(InvalidResponse.class);
        var client = mock(OpenSearchClient.class);
        when(client.createIndex(any(), any(), any())).thenThrow(invalidResponse);

        // Action
        var exception = assertThrows(InvalidResponse.class, () -> create(client, MIN_INDEX_JSON, "indexName"));

        // Assertions
        assertThat(exception, equalTo(invalidResponse));
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
        var exception = assertThrows(InvalidResponse.class, () -> create(client, MIN_INDEX_JSON, "indexName"));

        // Assertions
        assertThat(exception, equalTo(invalidResponse));
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
        assertThat(result, equalTo(INDEX_CREATE_SUCCESS));

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
    private Optional<ObjectNode> create(OpenSearchClient client, String rawJson, String indexName) {
        var node = (ObjectNode) OBJECT_MAPPER.readTree(rawJson);
        var indexId = "indexId";
        var indexData = new IndexMetadataData_OS_2_11(node, indexName, indexId);
        var indexCreator = new IndexCreator_OS_2_11(client);
        return indexCreator.create(indexData, indexName, indexId, mock(ICreateIndexContext.class));
    }
}
