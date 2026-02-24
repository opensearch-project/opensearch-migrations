package org.opensearch.migrations.bulkload.version_universal;

import java.util.Map;

import org.opensearch.migrations.bulkload.common.http.ConnectionContextTestParams;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RemoteReaderClientTest {

    private static final String LEGACY_TEMPLATE_VALUE = "{\"order\":0,\"index_patterns\":[\"blog*\"],\"settings\":{\"index\":{\"number_of_shards\":\"1\"}},\"mappings\":{\"_doc\":{\"_source\":{\"enabled\":true},\"properties\":{\"created_at\":{\"format\":\"EEE MMM dd HH:mm:ss Z yyyy\",\"type\":\"date\"},\"host_name\":{\"type\":\"keyword\"}}}},\"aliases\":{\"alias1\":{}}}";
    private static final String COMPONENT_TEMPLATE_VALUE = "{\"template\":{\"settings\":{\"index\":{\"number_of_shards\":\"1\",\"number_of_replicas\":\"1\"}},\"mappings\":{\"properties\":{\"author\":{\"type\":\"text\"}}},\"aliases\":{\"alias1\":{}}},\"version\":1}";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ConnectionContextTestParams CONNECTION_CONTEXT = ConnectionContextTestParams.builder().host("http://fakeHost").build();

    @Test
    public void testGetJsonForTemplateApis_WithSingleLegacyTemplate() throws JsonProcessingException {
        var jsonResponse = "{\"simple_index_template\":" + LEGACY_TEMPLATE_VALUE + "}";
        var result = getJsonResult(jsonResponse);
        var expected = parseJson(jsonResponse);
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testGetJsonForTemplateApis_WithMultipleLegacyTemplate() throws JsonProcessingException {
        var jsonResponse = "{\"simple_index_template\":" + LEGACY_TEMPLATE_VALUE +
                ",\"simple_index_template2\":" + LEGACY_TEMPLATE_VALUE + "}";
        var expected = parseJson(jsonResponse);
        var result = getJsonResult(jsonResponse);
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testGetJsonForTemplateApis_WithOneComponentTemplate() throws JsonProcessingException {
        var jsonResponse = "{\"component_templates\":[{\"name\":\"simple_index_template\", \"component_template\":" + COMPONENT_TEMPLATE_VALUE + "}]}";
        var expected = createExpectedFromComponentTemplates(jsonResponse);
        var result = getJsonResult(jsonResponse);
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testGetJsonForTemplateApis_WithMultipleComponentTemplate() throws JsonProcessingException {
        var jsonResponse = "{\"component_templates\":[{\"name\":\"simple_index_template\", \"component_template\":" + COMPONENT_TEMPLATE_VALUE + "}," +
                "{\"name\":\"simple_index_template2\", \"component_template\":" + COMPONENT_TEMPLATE_VALUE + "}]}";
        var expected = createExpectedFromComponentTemplates(jsonResponse);
        var result = getJsonResult(jsonResponse);
        Assertions.assertEquals(expected, result);
    }

    @Test
    public void testGetJsonForTemplateApis_WithInvalidComponentTemplate() {
        var jsonResponse = "{\"component_templates\":[\"invalid\"]}";
        Exception exception = Assertions.assertThrows(RemoteReaderClient.OperationFailed.class, () -> getJsonResult(jsonResponse));
        Assertions.assertEquals("Unable to get json response: Expected ObjectNode, got: STRING\n" +
                "Body:\n" +
                "HttpResponse(statusCode=200, statusText=OK, headers={}, body="+ jsonResponse+ ")", exception.getMessage());
    }

    private JsonNode getJsonResult(String jsonResponse) {
        var mockResponse = createMockResponse(jsonResponse);
        return createClient().getJsonForTemplateApis(mockResponse).block();
    }

    private RemoteReaderClient createClient() {
        return new RemoteReaderClient(CONNECTION_CONTEXT.toConnectionContext());
    }

    private HttpResponse createMockResponse(String jsonResponse) {
        return new HttpResponse(200, "OK", Map.of(), jsonResponse);
    }

    private JsonNode parseJson(String json) throws JsonProcessingException {
        return OBJECT_MAPPER.readTree(json);
    }

    private JsonNode createExpectedFromComponentTemplates(String jsonResponse) throws JsonProcessingException {
        var root = OBJECT_MAPPER.readTree(jsonResponse);
        var componentTemplates = root.get("component_templates");
        var expected = OBJECT_MAPPER.createObjectNode();
        componentTemplates.forEach(template -> expected
            .set(template.get("name").asText(), template.get("component_template")));
        return expected;
    }

}
