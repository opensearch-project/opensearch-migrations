package org.opensearch.migrations.bulkload.solr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolrSchemaConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void convertsBasicSolrFieldTypes() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("title", "text_general"));
        fields.add(field("count", "pint"));
        fields.add(field("price", "pfloat"));
        fields.add(field("id", "string"));
        fields.add(field("created", "pdate"));
        fields.add(field("active", "boolean"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields);
        var properties = mappings.get("properties");

        assertThat(properties.get("title").get("type").asText(), equalTo("text"));
        assertThat(properties.get("count").get("type").asText(), equalTo("integer"));
        assertThat(properties.get("price").get("type").asText(), equalTo("float"));
        assertThat(properties.get("id").get("type").asText(), equalTo("keyword"));
        assertThat(properties.get("created").get("type").asText(), equalTo("date"));
        assertThat(properties.get("active").get("type").asText(), equalTo("boolean"));
    }

    @Test
    void skipsInternalFields() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("title", "text_general"));
        fields.add(field("_version_", "plong"));
        fields.add(field("_root_", "string"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields);
        var properties = mappings.get("properties");

        assertNotNull(properties.get("title"));
        assertTrue(properties.get("_version_") == null || properties.get("_version_").isMissingNode());
        assertTrue(properties.get("_root_") == null || properties.get("_root_").isMissingNode());
    }

    @Test
    void handlesUnknownTypeAsText() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("custom", "my_custom_type"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields);
        assertThat(mappings.get("properties").get("custom").get("type").asText(), equalTo("text"));
    }

    @Test
    void handlesEmptyFields() {
        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(MAPPER.createArrayNode());
        assertNotNull(mappings.get("properties"));
        assertThat(mappings.get("properties").size(), equalTo(0));
    }

    @Test
    void handlesNullFields() {
        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(null);
        assertNotNull(mappings.get("properties"));
        assertThat(mappings.get("properties").size(), equalTo(0));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode field(String name, String type) {
        var node = MAPPER.createObjectNode();
        node.put("name", name);
        node.put("type", type);
        return node;
    }
}
