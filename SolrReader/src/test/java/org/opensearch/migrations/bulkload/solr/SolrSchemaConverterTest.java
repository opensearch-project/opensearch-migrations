package org.opensearch.migrations.bulkload.solr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    // --- Dynamic field tests ---

    @Test
    void convertsDynamicFieldsToTemplates() {
        var dynamicFields = MAPPER.createArrayNode();
        dynamicFields.add(dynField("*_s", "string"));
        dynamicFields.add(dynField("*_i", "pint"));
        dynamicFields.add(dynField("attr_*", "text_general"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(
            MAPPER.createArrayNode(), dynamicFields, null, null
        );

        var templates = mappings.get("dynamic_templates");
        assertNotNull(templates, "Should have dynamic_templates");
        assertThat("3 dynamic templates", templates.size(), equalTo(3));
    }

    @Test
    void dynamicFieldSuffixPatternMapsCorrectly() {
        var template = SolrSchemaConverter.buildDynamicTemplate("*_s", "keyword");
        assertNotNull(template);
        // Template should have a match pattern
        var inner = template.fields().next().getValue();
        assertThat(inner.get("match").asText(), equalTo("*_s"));
        assertThat(inner.get("mapping").get("type").asText(), equalTo("keyword"));
    }

    @Test
    void dynamicFieldPrefixPatternMapsCorrectly() {
        var template = SolrSchemaConverter.buildDynamicTemplate("attr_*", "text");
        assertNotNull(template);
        var inner = template.fields().next().getValue();
        assertThat(inner.get("match").asText(), equalTo("attr_*"));
        assertThat(inner.get("mapping").get("type").asText(), equalTo("text"));
    }

    // --- CopyField tests ---

    @Test
    void copyFieldAddsDestinationAsTextField() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("title", "text_general"));

        var copyFields = MAPPER.createArrayNode();
        var cf = MAPPER.createObjectNode();
        cf.put("source", "title");
        cf.put("dest", "text_all");
        copyFields.add(cf);

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields, null, copyFields, null);
        var properties = mappings.get("properties");

        assertThat("Original field present", properties.get("title").get("type").asText(), equalTo("text"));
        assertThat("CopyField dest added as text", properties.get("text_all").get("type").asText(), equalTo("text"));
    }

    @Test
    void copyFieldDoesNotOverrideExistingField() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("title", "text_general"));
        fields.add(field("text_all", "string")); // Explicit field

        var copyFields = MAPPER.createArrayNode();
        var cf = MAPPER.createObjectNode();
        cf.put("source", "title");
        cf.put("dest", "text_all");
        copyFields.add(cf);

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields, null, copyFields, null);
        // Explicit field type should win
        assertThat(mappings.get("properties").get("text_all").get("type").asText(), equalTo("keyword"));
    }

    // --- FieldType class resolution tests ---

    @Test
    void resolvesTypeViaFieldTypeClass() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("custom_date", "my_date_type"));
        fields.add(field("custom_str", "my_str_type"));

        var fieldTypes = MAPPER.createArrayNode();
        fieldTypes.add(fieldType("my_date_type", "solr.TrieDateField"));
        fieldTypes.add(fieldType("my_str_type", "solr.StrField"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields, null, null, fieldTypes);
        var properties = mappings.get("properties");

        assertThat("TrieDateField → date", properties.get("custom_date").get("type").asText(), equalTo("date"));
        assertThat("StrField → keyword", properties.get("custom_str").get("type").asText(), equalTo("keyword"));
    }

    @Test
    void resolvesTrieFieldTypes() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("old_int", "tint"));
        fields.add(field("old_long", "tlong"));
        fields.add(field("old_float", "tfloat"));

        var fieldTypes = MAPPER.createArrayNode();
        fieldTypes.add(fieldType("tint", "solr.TrieIntField"));
        fieldTypes.add(fieldType("tlong", "solr.TrieLongField"));
        fieldTypes.add(fieldType("tfloat", "solr.TrieFloatField"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields, null, null, fieldTypes);
        var properties = mappings.get("properties");

        assertThat(properties.get("old_int").get("type").asText(), equalTo("integer"));
        assertThat(properties.get("old_long").get("type").asText(), equalTo("long"));
        assertThat(properties.get("old_float").get("type").asText(), equalTo("float"));
    }

    // --- Date format tests ---

    @Test
    void dateFieldsIncludeFormat() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("created", "pdate"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields);
        var created = mappings.get("properties").get("created");

        assertThat(created.get("type").asText(), equalTo("date"));
        assertThat(created.get("format").asText(), equalTo(SolrSchemaConverter.OS_DATE_FORMAT));
    }

    @Test
    void dateFieldFromTrieDateFieldIncludesFormat() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("legacy_date", "my_trie_date"));

        var fieldTypes = MAPPER.createArrayNode();
        fieldTypes.add(fieldType("my_trie_date", "solr.TrieDateField"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields, null, null, fieldTypes);
        var legacyDate = mappings.get("properties").get("legacy_date");

        assertThat(legacyDate.get("type").asText(), equalTo("date"));
        assertThat(legacyDate.get("format").asText(), equalTo(SolrSchemaConverter.OS_DATE_FORMAT));
    }

    @Test
    void dynamicDateFieldIncludesFormat() {
        var template = SolrSchemaConverter.buildDynamicTemplate("*_dt", "date");
        assertNotNull(template);
        var inner = template.fields().next().getValue();
        assertThat(inner.get("mapping").get("format").asText(), equalTo(SolrSchemaConverter.OS_DATE_FORMAT));
    }

    @Test
    void nonDateFieldsDoNotIncludeFormat() {
        var fields = MAPPER.createArrayNode();
        fields.add(field("name", "string"));

        var mappings = SolrSchemaConverter.convertToOpenSearchMappings(fields);
        var name = mappings.get("properties").get("name");

        assertThat(name.get("type").asText(), equalTo("keyword"));
        assertTrue(name.get("format") == null || name.get("format").isMissingNode(),
            "Non-date fields should not have format");
    }

    // --- Helpers ---

    private static ObjectNode field(String name, String type) {
        var node = MAPPER.createObjectNode();
        node.put("name", name);
        node.put("type", type);
        return node;
    }

    private static ObjectNode dynField(String name, String type) {
        var node = MAPPER.createObjectNode();
        node.put("name", name);
        node.put("type", type);
        return node;
    }

    private static ObjectNode fieldType(String name, String className) {
        var node = MAPPER.createObjectNode();
        node.put("name", name);
        node.put("class", className);
        return node;
    }
}
