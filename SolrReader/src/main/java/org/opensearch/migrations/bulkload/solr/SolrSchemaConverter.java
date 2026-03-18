package org.opensearch.migrations.bulkload.solr;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Converts Solr schema field definitions to OpenSearch-compatible mappings.
 */
public final class SolrSchemaConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String> SOLR_TO_OS_TYPE = Map.ofEntries(
        Map.entry("string", "keyword"),
        Map.entry("strings", "keyword"),
        Map.entry("text_general", "text"),
        Map.entry("text_en", "text"),
        Map.entry("text_ws", "text"),
        Map.entry("pint", "integer"),
        Map.entry("plong", "long"),
        Map.entry("pfloat", "float"),
        Map.entry("pdouble", "double"),
        Map.entry("pdate", "date"),
        Map.entry("boolean", "boolean"),
        Map.entry("booleans", "boolean"),
        Map.entry("plongs", "long"),
        Map.entry("pfloats", "float"),
        Map.entry("pdoubles", "double"),
        Map.entry("pdates", "date"),
        Map.entry("pints", "integer"),
        Map.entry("int", "integer"),
        Map.entry("long", "long"),
        Map.entry("float", "float"),
        Map.entry("double", "double"),
        Map.entry("date", "date"),
        Map.entry("binary", "binary"),
        Map.entry("text", "text")
    );

    private SolrSchemaConverter() {}

    /**
     * Convert Solr schema fields array to OpenSearch mappings ObjectNode.
     */
    public static ObjectNode convertToOpenSearchMappings(JsonNode solrFields) {
        ObjectNode mappings = MAPPER.createObjectNode();
        ObjectNode properties = MAPPER.createObjectNode();

        if (solrFields != null && solrFields.isArray()) {
            for (var field : solrFields) {
                var name = field.path("name").asText();
                var type = field.path("type").asText();

                // Skip Solr internal fields
                if (name.startsWith("_") && !name.equals("id")) {
                    continue;
                }

                var osType = SOLR_TO_OS_TYPE.getOrDefault(type, "text");
                ObjectNode fieldMapping = MAPPER.createObjectNode();
                fieldMapping.put("type", osType);
                properties.set(name, fieldMapping);
            }
        }

        mappings.set("properties", properties);
        return mappings;
    }
}
